package cloudstreamweb.engine

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.header
import io.ktor.server.response.respondOutputStream
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import cloudstreamweb.errors.ProviderException
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * M1 "Unified PlaybackEngine": ONE place owns the mechanics of talking to
 * provider/upstream media endpoints:
 *
 *  - the shared OkHttp client (timeout policy lies here),
 *  - header replay + Range forward + Accept-Encoding identity (request build),
 *  - binary passthrough streaming (respondOutputStream pump),
 *  - manifest rewriting (nested HLS references → proxied refs),
 *  - proxy-ref URL builders used by the SPA-facing metadata,
 *  - `linkId` — stable hash of (url, headers) used as the cache key namespace
 *    by probe/remux/ffprobe caches in later milestones (they own their TTLs;
 *    nothing here pages memory or hits disk).
 *
 * Routes (`/api/stream`, `/api/subtitle`) and the download adapter become thin
 * adapters over this object; the REST contract and DTOs stay untouched.
 */
object PlaybackEngine {

    /**
     * Long first-byte/read timeout: relays like workers.dev can be slow to
     * hand back the first bytes of a big file; 20s proved too tight.
     */
    private val client = okhttp3.OkHttpClient.Builder()
        .connectTimeout(java.time.Duration.ofSeconds(20))
        .readTimeout(java.time.Duration.ofSeconds(90))
        .callTimeout(java.time.Duration.ofSeconds(0))
        .followRedirects(true)
        .build()

    fun sharedClient(): okhttp3.OkHttpClient = client

    /** Stable identity of a playable link: url + sorted header pairs, sha-256/16. */
    fun linkId(url: String, headers: Map<String, String> = emptyMap()): String {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(url.toByteArray(StandardCharsets.UTF_8))
        headers.entries.sortedBy { it.key }.forEach { (k, v) ->
            md.update("|".toByteArray()); md.update(k.toByteArray()); md.update(":".toByteArray()); md.update(v.toByteArray())
        }
        return java.math.BigInteger(1, md.digest()).toString(16).padStart(32, '0').take(16)
    }

    /**
     * Upstream request for a provider link: replays the extractor-set headers,
     * forwards the client's Range 1:1, denies gzip (byte ranges must map 1:1
     * onto the source bytes).
     */
    fun buildUpstream(
        url: String,
        headers: Map<String, String> = emptyMap(),
        range: String? = null,
        head: Boolean = false,
    ) = okhttp3.Request.Builder().url(url).apply {
        headers.forEach { (k, v) -> header(k, v) }
        if (range != null) header("Range", range)
        header("Accept-Encoding", "identity")
        if (head) head()
    }.build()

    /** Open an upstream response with the shared client (caller closes it). */
    fun open(request: okhttp3.Request): okhttp3.Response = client.newCall(request).execute()

    const val PROXIED_PATH = "/api/stream"

    /** The exact header set we replay upstream (base64url-JSON "h" param). */
    private val json = kotlinx.serialization.json.Json

    fun decodeHeaders(hB64: String?): Map<String, String> {
        if (hB64.isNullOrBlank()) return emptyMap()
        val text = String(java.util.Base64.getUrlDecoder().decode(hB64), StandardCharsets.UTF_8)
        return try {
            json.parseToJsonElement(text).jsonObject.entries
                .map { it.key to it.value.jsonPrimitive.content }
                .toMap()
        } catch (t: Throwable) {
            throw ProviderException("REPO_INVALID", "bad header blob: ${t.message}")
        }
    }

    fun headersEncoded(headers: Map<String, String>): String {
        val text = "{" + headers.entries.joinToString(",") { (k, v) ->
            "\"" + k.replace("\"", "\\\"") + "\":\"" + v.replace("\"", "\\\"") + "\""
        } + "}"
        return java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString(text.toByteArray(StandardCharsets.UTF_8))
    }

    fun proxied(url: String, headersB64: String): String {
        val enc = URLEncoder.encode(url, StandardCharsets.UTF_8)
        return if (headersB64.isBlank()) "$PROXIED_PATH?url=$enc" else "$PROXIED_PATH?url=$enc&h=$headersB64"
    }

    /** URL of a proxied subtitle track (same header replay, converted to VTT). */
    fun subtitleProxied(url: String, headers: Map<String, String>): String {
        val h = headersEncoded(headers)
        val enc = URLEncoder.encode(url, StandardCharsets.UTF_8)
        return if (h.isBlank()) "/api/subtitle?url=$enc" else "/api/subtitle?url=$enc&h=$h"
    }

    // ---- manifest rewriting -----------------------------------------------------

    private val uriAttrRegex = Regex("URI=\"([^\"]+)\"")

    fun rewritePlaylist(text: String, baseUrl: String, headersB64: String): String =
        text.lines().joinToString("\n") { line ->
            when {
                line.isBlank() -> line
                line.startsWith("#") -> line.replace(uriAttrRegex) { m ->
                    resolveUpstream(baseUrl, m.groupValues[1])
                        ?.let { "URI=\"${proxied(it, headersB64)}\"" }
                        ?: m.value
                }
                else -> resolveUpstream(baseUrl, line.trim())
                    ?.let { proxied(it, headersB64) }
                    ?: line
            }
        }

    private fun resolveUpstream(base: String, ref: String): String? {
        if (ref.startsWith("data:") || ref.startsWith("javascript:")) return null
        return try {
            URI(base).resolve(ref).toString()
        } catch (_: Throwable) {
            null
        }
    }

}

/**
 * Binary passthrough from a live upstream [resp] into this call. The
 * extension is top-level (not a member of [PlaybackEngine]) so route adapters
 * can import it; it lives here because streaming mechanics belong to the
 * engine.
 *
 * Ktor 3 drains streaming content AFTER the route handler returns — an outer
 * `finally { resp.close() }` would hand the player 0 bytes; the pump below
 * closes the upstream body in its own finally. Client aborts and truncations
 * MUST go through this function — that path is what killed the old
 * respondBytesWriter bridge (ChannelWriterContent CCE on client aborts).
 */
suspend fun ApplicationCall.passthrough(resp: okhttp3.Response, requireOk: Boolean = true) {
    if (requireOk) {
        check(resp.isSuccessful) { "passthrough requires a successful response (got ${resp.code})" }
    }
    resp.header("Content-Range")?.let { response.header("Content-Range", it) }
    response.header("Accept-Ranges", resp.header("Accept-Ranges") ?: "bytes")

    respondOutputStream(
        ContentType.parse((resp.header("Content-Type") ?: "application/octet-stream").substringBefore(';')),
        HttpStatusCode.fromValue(resp.code),
        resp.header("Content-Length")?.toLongOrNull(),
    ) {
        val out = this
        try {
            resp.body?.byteStream()?.use { input ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                }
                out.flush()
            }
        } finally {
            runCatching { resp.close() }
        }
    }
}
