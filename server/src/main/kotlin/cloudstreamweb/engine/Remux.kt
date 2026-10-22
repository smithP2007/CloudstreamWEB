package cloudstreamweb.engine

import cloudstreamweb.RuntimeConfig
import cloudstreamweb.errors.ErrorCodes
import cloudstreamweb.errors.ProviderException
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondOutputStream
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.TimeUnit

/**
 * M3 audio remux + source probing.
 *
 * The truth requirement: probing and remuxing MUST see exactly what a browser
 * would see. Both therefore go THROUGH the engine's own /api/stream proxy
 * (self-connection, same header replay / Range semantics), never straight at
 * the hoster with hand-rolled headers.
 *
 * - `probeAudio` runs ffprobe over an mkv/webm file link and reports the audio
 *   streams (language/title/channels) — cached per `linkId` in
 *   `cache/probe/<id>.json` (TTL 6h; links are usually signed and ephemeral,
 *   so a new extraction naturally produces a new key).
 * - `buildRemuxCommand` Produces the ffmpeg argv for a streamable remux:
 *   video+one audio stream, `-c copy` (no transcode — fast, lossless), out as
 *   fragmented MP4 on stdout so Chromium's <video> plays it natively. The
 *   10-bit/codec-level cases a copy cannot fix belong to M5 live transcode.
 */
object Remux {

    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    data class AudioTrackDto(
        val ordinal: Int,          // 0-based audio-stream ordinal (also the ?audio= param)
        val streamIndex: Int,      // absolute file stream index (informational)
        val lang: String? = null,  // ISO-639 code as muxed (hin/eng/kor/und...)
        val title: String? = null, // track title metadata, when present
        val codec: String? = null, // aac/ac3/eac3/dts/...
        val channels: Int? = null,
        val isDefault: Boolean = false,
    )

    // ---- probing -------------------------------------------------------------

    private val probeDir: Path
        get() = RuntimeConfig.dataDir.resolve("cache").resolve("probe")

    private const val PROBE_TTL_MS = 6L * 60 * 60 * 1000

    /**
     * Audio streams of a FILE link, probed through the engine proxy and cached
     * by linkId. Never throws for "bad content" — returns emptyList and lets
     * the caller decide; missing TOOLING is the caller's responsibility to
     * check first (FFMPEG_MISSING).
     */
    fun probeAudio(rawUrl: String, headers: Map<String, String>, linkId: String): List<AudioTrackDto> {
        val tools = Capabilities.resolveTools()
        val ffprobe = tools.ffprobe ?: throw ProviderException(
            cloudstreamweb.errors.ErrorCodes.FFMPEG_MISSING,
            "ffprobe is not installed (run scripts/bootstrap.sh or set CS_WEB_FFMPEG)",
        )

        val cache = probeDir.resolve("$linkId.json")
        if (Files.isRegularFile(cache) &&
            System.currentTimeMillis() - Files.getLastModifiedTime(cache).toMillis() < PROBE_TTL_MS
        ) {
            return runCatching { parseProbeJson(Files.readString(cache)) }.getOrElse { emptyList() }
        }

        Files.createDirectories(probeDir)
        val stdout = runProbe(
            ffprobe,
            listOf(
                "-v", "error",
                "-print_format", "json",
                "-show_streams",
                "-select_streams", "a",
                selfProxyInput(rawUrl, headers),
            ),
        ) ?: throw ProviderException(
            cloudstreamweb.errors.ErrorCodes.PROBE_TIMEOUT,
            "ffprobe failed for ${rawUrl.take(120)}",
        )
        // only cache probes we could parse into tracks; an empty parse (no
        // audio streams) is a legitimate file shape, cache it too
        return parseProbeJson(stdout).also {
            runCatching { Files.writeString(cache, stdout) }
        }
    }

    /** Cache-first read for the route; falls back to a live probe when stale/missing. */
    fun cachedTracks(rawUrl: String, headers: Map<String, String>, linkId: String): List<AudioTrackDto> =
        probeAudio(rawUrl, headers, linkId)

    fun parseProbeJson(stdout: String): List<AudioTrackDto> {
        val streams = json.parseToJsonElement(stdout).jsonObject["streams"]?.jsonArray ?: return emptyList()
        return streams.mapNotNull { el ->
            val s = el.jsonObject
            val tags = s["tags"]?.jsonObject
            val disp = s["disposition"]?.jsonObject
            s["index"]?.jsonPrimitive?.content?.toIntOrNull()?.let { streamIndex ->
                AudioTrackDto(
                    ordinal = 0, // replaced below by enumeration order
                    streamIndex = streamIndex,
                    lang = tags?.get("language")?.jsonPrimitive?.content,
                    title = tags?.get("title")?.jsonPrimitive?.content,
                    codec = s["codec_name"]?.jsonPrimitive?.content,
                    channels = s["channels"]?.jsonPrimitive?.content?.toIntOrNull(),
                    isDefault = disp?.get("default")?.jsonPrimitive?.content == "1",
                )
            }
        }.mapIndexed { i, t -> t.copy(ordinal = i) }
    }

    // ---- ffmpeg command building ----------------------------------------------

    /**
     * Remux argv: input through the engine proxy (header replay + Range are
     * browser-identical), fast-seek then -c copy into fragmented MP4 on stdout.
     */
    fun buildRemuxCommand(
        proxyInputUrl: String,
        audioOrdinal: Int,
        startSeconds: Double? = null,
    ): List<String> {
        val tools = Capabilities.resolveTools()
        val ffmpeg = tools.ffmpeg ?: throw ProviderException(
            cloudstreamweb.errors.ErrorCodes.FFMPEG_MISSING,
            "ffmpeg is not installed (run scripts/bootstrap.sh or set CS_WEB_FFMPEG)",
        )
        return buildList {
            add(ffmpeg.toString())
            add("-nostdin"); add("-hide_banner"); add("-loglevel"); add("error")
            if (startSeconds != null && startSeconds > 0.5) {
                add("-ss"); add(formatSeconds(startSeconds))
            }
            add("-i"); add(proxyInputUrl)
            add("-map"); add("0:v:0")
            add("-map"); add("0:a:$audioOrdinal")
            add("-c"); add("copy")
            add("-f"); add("mp4")
            add("-movflags"); add("empty_moov+frag_keyframe+default_base_moof")
            add("-max_muxing_queue_size"); add("2048")
            add("pipe:1")
        }
    }

    private fun formatSeconds(s: Double): String = String.format(java.util.Locale.ROOT, "%.2f", s)

    /**
     * Default audio for `?audio=`-less remux: the mux's own default track,
     * falling back to the first audio stream.
     */
    fun defaultAudioOrdinal(tracks: List<AudioTrackDto>): Int =
        tracks.firstOrNull { it.isDefault }?.ordinal
            ?: tracks.firstOrNull()?.ordinal
            ?: 0

    // ---- plumbing --------------------------------------------------------------

    /**
     * Input URL for ffmpeg/ffprobe: our own /api/stream bridge on loopback, so
     * every request ffmpeg makes gets the same header replay/Range treatment a
     * browser gets (probe truth == playback truth).
     */
    fun selfProxyInput(rawUrl: String, headers: Map<String, String>): String {
        val host = if (RuntimeConfig.host == "0.0.0.0" || RuntimeConfig.host.isBlank()) "127.0.0.1" else RuntimeConfig.host
        val base = selfBaseUrlOverride ?: "http://$host:${RuntimeConfig.port}"
        val b64 = PlaybackEngine.headersEncoded(headers)
        val auth = RuntimeConfig.lanToken?.let { "&token=" + java.net.URLEncoder.encode(it, "UTF-8") } ?: ""
        // raw=1: the self-connection must receive the ORIGINAL container bytes —
        // otherwise the stream route would remux our remux (nested ffmpeg, and
        // ffprobe would read a remux that needs another remux).
        return "$base${PlaybackEngine.proxied(rawUrl, b64)}&raw=1$auth"
    }

    /** Tests boot real servers on ephemeral ports; production never sets this. */
    @Volatile
    var selfBaseUrlOverride: String? = null

    private fun runProbe(bin: Path, args: List<String>): String? = runCatching {
        val proc = ProcessBuilder(listOf(bin.toString()) + args)
            .redirectErrorStream(false)
            .start()
        val out = proc.inputStream.bufferedReader().readText()
        val err = proc.errorStream.bufferedReader().readText()
        val ok = proc.waitFor(300, TimeUnit.SECONDS) && proc.exitValue() == 0
        if (!ok) {
            System.err.println("[remux-probe] exit=${if (proc.isAlive) "timeout" else proc.exitValue()} err=${err.take(300)}")
            return@runCatching null
        }
        out.ifBlank { null }
    }.getOrNull()

    /** Human dump for offline re-probing (also used by local scripts). */
    fun dumpProbeCache(): List<File> =
        probeDir.toFile().listFiles()?.sortedBy { it.name } ?: emptyList()
}

/**
 * Spawn ffmpeg per [command] (built by [Remux.buildRemuxCommand]) and stream
 * its stdout into this call as fragmented MP4. The process is destroyed on
 * client abort and its stderr is mirrored to the server log under [logTag].
 * (Top-level extension for the same reason as engine.passthrough.)
 */
suspend fun ApplicationCall.respondRemux(
    command: List<String>,
    logTag: String,
    logFile: Path? = null,
) {
    val builder = ProcessBuilder(command).redirectErrorStream(false)
    val proc = builder.start()
    System.err.println("[$logTag] spawn ok pid=${proc.pid()} argv=${command.take(8)}")
    val errThread = Thread {
        runCatching {
            proc.errorStream.bufferedReader().forEachLine { line ->
                System.err.println("[$logTag] $line")
                logFile?.let { runCatching { Files.writeString(it, "$line\n", java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND) } }
            }
        }
    }.apply { isDaemon = true; start() }
    try {
        respondOutputStream(
            ContentType.parse("video/mp4"),
            HttpStatusCode.OK,
            null, // fragmented mp4 has no meaningful total length
        ) {
            val out = this
            try {
                proc.inputStream.use { input ->
                    var first = true
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (first && n >= 0) {
                            System.err.println("[$logTag] first byte from ffmpeg: n=$n t=${System.currentTimeMillis() / 1000}")
                        }
                        first = false
                        if (n < 0) break
                        out.write(buf, 0, n)
                    }
                    out.flush()
                }
                System.err.println("[$logTag] pump done exit=${runCatching { proc.waitFor(5, TimeUnit.SECONDS); if (proc.isAlive) -1 else proc.exitValue() }}")
            } catch (t: Throwable) {
                System.err.println("[$logTag] pump aborted t=${t.javaClass.simpleName} msg=${t.message}")
                throw t
            } finally {
                runCatching { proc.destroy() }
            }
        }
    } catch (t: Throwable) {
        runCatching { proc.destroyForcibly() }
        if (PlaybackEngine.isClientAbort(t)) {
            System.err.println("[$logTag] client disconnected — remux aborted")
        } else {
            throw t
        }
    } finally {
        // let the stderr mirror drain the tail (usually -loglevel error lines)
        runCatching { errThread.join(1000) }
    }
}
