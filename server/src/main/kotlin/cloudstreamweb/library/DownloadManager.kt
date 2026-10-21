package cloudstreamweb.library

import cloudstreamweb.RuntimeConfig
import cloudstreamweb.persistence.Db
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Phase-4 download queue: provider links stream to the user's disk with
 * progress persisted in SQLite (per-1%), automatic resume via HTTP ranges and
 * cancellation. Replays the same headers the extractor demands.
 */
object DownloadManager {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = ConcurrentHashMap<String, Job>()
    private const val CONCURRENCY = 2
    private val sem = Semaphore(CONCURRENCY)
    private val json = kotlinx.serialization.json.Json

    private val client = okhttp3.OkHttpClient.Builder()
        .connectTimeout(java.time.Duration.ofSeconds(20))
        .readTimeout(java.time.Duration.ofMinutes(2))
        .followRedirects(true)
        .build()

    fun enqueue(
        provider: String?,
        url: String?,
        name: String,
        linkUrl: String,
        headers: Map<String, String>,
        extHint: String? = null,
    ): Db.DownloadRow {
        val headersJson = json.encodeToString(
            kotlinx.serialization.serializer<Map<String, String>>(), headers,
        )
        val row = Db.createDownload(
            id = UUID.randomUUID().toString().substring(0, 8),
            provider = provider,
            url = url,
            name = name,
            linkUrl = linkUrl,
            headersJson = headersJson,
        )
        start(row, headers, extHint)
        return row
    }

    private fun start(row: Db.DownloadRow, headers: Map<String, String>, extHint: String?) {
        jobs[row.id] = scope.launch {
            sem.withPermit { run(row.id, row.name, row.provider, row.linkUrl, headers, extHint) }
        }
    }

    /** Retry a PAUSED/FAILED row (resume with ranges when the CDN allows it). */
    fun resume(id: String) {
        val row = Db.getDownload(id) ?: return
        Db.setDownloadProgress(id, row.downloadedBytes, "QUEUED", null)
        // extHint not needed on resume: the recorded filePath is reused
        start(row, parseHeaders(Db.getDownloadHeaders(id)), null)
    }

    private fun parseHeaders(raw: String?): Map<String, String> =
        if (raw.isNullOrBlank()) emptyMap()
        else runCatching {
            json.decodeFromString(
                kotlinx.serialization.serializer<Map<String, String>>(), raw,
            )
        }.getOrDefault(emptyMap())

    fun cancel(id: String) {
        jobs.remove(id)?.cancel()
        val row = Db.getDownload(id) ?: return
        if (row.state == "RUNNING" || row.state == "QUEUED") {
            Db.setDownloadProgress(id, row.downloadedBytes, "PAUSED", "cancelled")
        }
    }

    /** Delete row (+ file already on disk). */
    fun delete(id: String) {
        cancel(id)
        val row = Db.getDownload(id) ?: return
        row.filePath?.let { p ->
            try { Files.deleteIfExists(Path.of(p)) } catch (_: Throwable) {}
        }
        Db.deleteDownloadRow(id)
    }

    private suspend fun run(
        id: String,
        name: String,
        provider: String?,
        linkUrl: String,
        headers: Map<String, String>,
        extHint: String? = null,
    ) = withContext(Dispatchers.IO) {
        val dir = RuntimeConfig.dataDir.resolve("Downloads")
        Files.createDirectories(dir)
        val existing = Db.getDownload(id)
        val resumeFrom = existing?.downloadedBytes ?: 0L

        val builder = okhttp3.Request.Builder().url(linkUrl)
        headers.forEach { (k, v) -> builder.header(k, v) }
        builder.header("Accept-Encoding", "identity")
        if (resumeFrom > 0) builder.header("Range", "bytes=$resumeFrom-")

        val target = dir.resolve(fileName(name, provider, linkUrl, contentTypeGuess = null))
        Db.setDownloadProgress(id, resumeFrom, "RUNNING", null)

        val resp = try {
            client.newCall(builder.build()).execute()
        } catch (t: Throwable) {
            Db.setDownloadFailed(id, target.toString(), resumeFrom, "connect: ${t.message?.take(160)}")
            return@withContext
        }

        resp.use { response ->
            try {
                if (!response.isSuccessful) {
                    Db.setDownloadFailed(id, target.toString(), resumeFrom, "upstream ${response.code}")
                    return@use
                }
                val contentRange = response.header("Content-Range")
                var bodyOffset = if (response.code == 206 &&
                    contentRange?.startsWith("bytes $resumeFrom-") == true
                ) resumeFrom else 0L

                val total = when {
                    contentRange?.contains('/') == true ->
                        contentRange.substringAfter('/').toLongOrNull()
                    response.header("Content-Length") != null ->
                        response.header("Content-Length")!!.toLongOrNull()?.plus(bodyOffset)
                    else -> null
                }

                // Filename: reuse the recorded path on resume (so a partial file
                // always matches), else decide once from URL/name/content type.
                // Same inputs ⇒ same response type ⇒ deterministic for later resumes.
                val recorded = existing?.filePath
                val file = when {
                    bodyOffset > 0 && recorded != null -> Path.of(recorded)
                    recorded != null && Files.exists(Path.of(recorded)) && !Files.isDirectory(Path.of(recorded)) && bodyOffset == 0L ->
                        Path.of(recorded)
                    else -> dir.resolve(fileName(name, provider, linkUrl, response.header("Content-Type"), extHint))
                }
                // never append into a file that isn't actually there — restart cleanly
                if (bodyOffset > 0 && !Files.exists(file)) bodyOffset = 0L

                // persist the resolved path immediately so resumes reproduce it
                Db.setDownloadProgress(id, bodyOffset, "RUNNING", null, file.toString())

                RandomAccessFile(file.toFile(), "rw").use { raf ->
                    if (bodyOffset > 0) {
                        raf.seek(bodyOffset)
                    } else {
                        raf.setLength(0)
                    }
                    val buf = ByteArray(64 * 1024)
                    var written = bodyOffset
                    var lastReport = -1L
                    response.body?.byteStream()?.use { input ->
                        while (true) {
                            ensureActive()
                            val n = input.read(buf)
                            if (n < 0) break
                            if (n > 0) {
                                raf.write(buf, 0, n)
                                written += n
                                val reportEvery = if (total != null) {
                                    maxOf(1, total / 50) // ~2% steps
                                } else 1 shl 20
                                if (written / reportEvery != lastReport) {
                                    lastReport = written / reportEvery
                                    Db.setDownloadProgress(id, written, "RUNNING", null)
                                }
                            }
                        }
                    }

                    val done = if (total != null) written >= total else true
                    if (done) {
                        Db.setDownloadDone(id, file.toString(), written)
                    } else {
                        Db.setDownloadProgress(id, written, "PAUSED", "stream ended early; resume available")
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                Db.setDownloadProgress(id, Db.getDownload(id)?.downloadedBytes ?: 0, "PAUSED", "cancelled")
                throw e
            } catch (t: Throwable) {
                Db.setDownloadFailed(
                    id,
                    target.toString(),
                    Db.getDownload(id)?.downloadedBytes ?: 0L,
                    "io: ${t.message?.take(160)}",
                )
            }
        }
    }

    internal fun fileName(
        name: String,
        provider: String?,
        linkUrl: String,
        contentTypeGuess: String?,
        extHint: String? = null,
    ): String {
        val safe = name.replace(Regex("[^a-zA-Z0-9 ._-]"), "_")
            .replace(Regex("_{2,}"), "_").trim().take(120).ifBlank { "download" }
        val linkExt = linkUrl.substringBefore('?').substringAfterLast('/').substringAfterLast('.').lowercase()
        val nameExt = Regex("""\.(mp4|mkv|webm|avi|mov|ts|m3u8)\b""", RegexOption.IGNORE_CASE)
            .find(name)?.value?.substring(1)?.lowercase()
        val hint = extHint?.trim()?.trimStart('.')?.lowercase()
        val ext = when {
            linkExt.length in 2..4 && linkExt.all { it in 'a'..'z' } -> linkExt
            nameExt != null -> nameExt
            hint != null && hint.length in 2..4 && hint.all { it in 'a'..'z' } -> hint
            contentTypeGuess?.contains("matroska") == true -> "mkv"
            contentTypeGuess?.contains("mp4") == true -> "mp4"
            contentTypeGuess?.contains("webm") == true -> "webm"
            contentTypeGuess?.contains("mpegurl") == true -> "m3u8"
            else -> "bin"
        }
        return "$safe${provider?.let { " [$it]" } ?: ""}.$ext"
    }
}
