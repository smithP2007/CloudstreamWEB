package cloudstreamweb.routes

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.header
import io.ktor.server.response.respondOutputStream
import io.ktor.server.response.respondText
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Playback bridge: browsers cannot send the Referer/User-Agent headers many
 * extractors require, and HLS playlists contain nested upstream URLs. This
 * proxy relays provider links with their original headers, forwards Range
 * requests, and rewrites m3u8 playlists so every nested segment also goes
 * through the proxy.
 *
 *   GET /api/stream?url=<upstream>&h=<base64url json object of headers>
 *
 * Accept-Encoding is forced to identity on the upstream request so ranges map
 * 1:1 onto the source bytes.
 */
object StreamProxy {

    private val client = okhttp3.OkHttpClient.Builder()
        .connectTimeout(java.time.Duration.ofSeconds(20))
        .readTimeout(java.time.Duration.ofSeconds(60))
        .followRedirects(true)
        .build()

    private val json = Json

    fun install(routing: Routing) = with(routing) {
        get("/api/stream") {
            val rawUrl = call.request.queryParameters["url"]
                ?: throw ProviderException("REPO_INVALID", "missing url")
            if (!rawUrl.startsWith("http://") && !rawUrl.startsWith("https://")) {
                throw ProviderException("REPO_INVALID", "only http(s) links can be streamed")
            }
            val headers = decodeHeaders(call.request.queryParameters["h"])
            val headersB64 = headersEncoded(headers)

            val upstream = okhttp3.Request.Builder().url(rawUrl).apply {
                headers.forEach { (k, v) -> header(k, v) }
                call.request.headers["Range"]?.let { header("Range", it) }
                header("Accept-Encoding", "identity")
            }.build()

            val resp = client.newCall(upstream).execute()
            try {
                if (!resp.isSuccessful) {
                    throw ProviderException(
                        "PROVIDER_ERROR",
                        "upstream ${resp.code} for ${rawUrl.take(120)}",
                    )
                }
                val contentType = (resp.header("Content-Type") ?: "application/octet-stream")
                    .substringBefore(';')

                // ---- m3u8: rewrite every nested reference -----------------------
                if (contentType.contains("mpegurl") || rawUrl.substringBefore('?').endsWith(".m3u8")) {
                    val text = resp.body?.string() ?: ""
                    resp.close()
                    call.respondText(rewritePlaylist(text, resp.request.url.toString(), headersB64), ContentType.parse(contentType))
                } else {
                    // ---- passthrough with range metadata --------------------------
                    resp.header("Content-Range")?.let { call.response.header("Content-Range", it) }
                    call.response.header("Accept-Ranges", resp.header("Accept-Ranges") ?: "bytes")

                    // Ktor 3 drains streaming content AFTER the route handler returns:
                    // the upstream okhttp body must be closed by the pump itself (the
                    // block's finally), never by an outer finally — otherwise the
                    // engine sees EOF immediately and the player receives 0 bytes.
                    call.respondOutputStream(
                        ContentType.parse(contentType),
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
            } catch (t: Throwable) {
                // Only the non-streaming paths can still be in flight here; the
                // streaming block closes resp itself. A close() on an already-
                // closed okhttp body is a no-op, so this is always safe.
                runCatching { resp.close() }
                throw t
            }
        }

        get("/api/subtitle") {
            // Subtitle tracks: browsers need WebVTT; many providers ship SRT (or
            // already-VTT text). Convert on the way through so <track> just works.
            val rawUrl = call.request.queryParameters["url"]
                ?: throw ProviderException("REPO_INVALID", "missing url")
            if (!rawUrl.startsWith("http://") && !rawUrl.startsWith("https://")) {
                throw ProviderException("REPO_INVALID", "only http(s) links can be streamed")
            }
            val headers = decodeHeaders(call.request.queryParameters["h"])

            val upstream = okhttp3.Request.Builder().url(rawUrl).apply {
                headers.forEach { (k, v) -> header(k, v) }
                header("Accept-Encoding", "identity")
            }.build()

            val resp = client.newCall(upstream).execute()
            resp.use {
                if (!it.isSuccessful) {
                    throw ProviderException(
                        "PROVIDER_ERROR",
                        "upstream ${it.code} for ${rawUrl.take(120)}",
                    )
                }
                // subtitles are small text payloads; read whole body for conversion
                val bytes = it.body?.bytes() ?: ByteArray(0)
                val raw = String(bytes, StandardCharsets.UTF_8)
                call.respondText(
                    toVtt(raw),
                    ContentType.parse("text/vtt; charset=UTF-8"),
                )
            }
        }
    }

    /**
     * The exact header set we replay upstream (base64url-JSON "h" param).
     */
    fun decodeHeaders(hB64: String?): Map<String, String> {        if (hB64.isNullOrBlank()) return emptyMap()
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

    // ---- playlist rewriting -------------------------------------------------

    private val uriAttr = Regex("URI=\"([^\"]+)\"")

    fun rewritePlaylist(text: String, baseUrl: String, headersB64: String): String =
        text.lines().joinToString("\n") { line ->
            when {
                line.isBlank() -> line
                line.startsWith("#") -> line.replace(uriAttr) { m ->
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

    fun proxied(url: String, headersB64: String): String {
        val enc = URLEncoder.encode(url, StandardCharsets.UTF_8)
        return if (headersB64.isBlank()) "/api/stream?url=$enc"
        else "/api/stream?url=$enc&h=$headersB64"
    }

    /** URL of a proxied subtitle track (same header replay, converted to VTT). */
    fun subtitleProxied(url: String, headers: Map<String, String>): String {
        val h = headersEncoded(headers)
        val enc = URLEncoder.encode(url, StandardCharsets.UTF_8)
        return if (h.isBlank()) "/api/subtitle?url=$enc" else "/api/subtitle?url=$enc&h=$h"
    }

    // ---- subtitle conversion --------------------------------------------------

    /** WEBVTT header + comma→dot timestamps; VTT input passes through untouched. */
    private val srtStampRegex = Regex("""(\d{2}):(\d{2}):(\d{2}),(\d{3})""")

    fun toVtt(raw: String): String {
        val text = raw.replace("\uFEFF", "")
        if (text.trimStart().startsWith("WEBVTT")) return text
        val converted = srtStampRegex.replace(text) { m ->
            "${m.groupValues[1]}:${m.groupValues[2]}:${m.groupValues[3]}.${m.groupValues[4]}"
        }
        return "WEBVTT\n\n" + converted.trimStart('\n', '\r')
    }
}
