package cloudstreamweb.routes

import cloudstreamweb.engine.PlaybackEngine
import cloudstreamweb.engine.SubtitleCodec
import cloudstreamweb.engine.passthrough
import cloudstreamweb.errors.ProviderException
import io.ktor.http.ContentType
import io.ktor.server.response.respondText
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get

/**
 * Playback bridge routes (M1): thin adapters only. The mechanics — shared
 * OkHttp client, header replay, Range forwarding, the streaming pump, manifest
 * rewriting, subtitle conversion, linkId hashing — live in
 * cloudstreamweb.engine.PlaybackEngine / SubtitleCodec. This file decides
 * WHICH branch serves a request and turns upstream status into typed errors.
 *
 *   GET /api/stream?url=<upstream>&h=<base64url json object of headers>
 *
 * Browsers cannot send the Referer/User-Agent headers many extractors
 * require, and HLS playlists contain nested upstream URLs; the bridge relays
 * provider links with their original headers, forwards Range, and rewrites
 * m3u8 playlists so every nested segment also goes through the proxy.
 */
object StreamProxy {

    fun install(routing: Routing) = with(routing) {
        get("/api/stream") {
            val rawUrl = call.request.queryParameters["url"]
                ?: throw ProviderException("REPO_INVALID", "missing url")
            if (!rawUrl.startsWith("http://") && !rawUrl.startsWith("https://")) {
                throw ProviderException("REPO_INVALID", "only http(s) links can be streamed")
            }
            val headers = PlaybackEngine.decodeHeaders(call.request.queryParameters["h"])
            val headersB64 = PlaybackEngine.headersEncoded(headers)
            val range = call.request.headers["Range"]

            val resp = PlaybackEngine.open(PlaybackEngine.buildUpstream(rawUrl, headers, range))
            try {
                if (!resp.isSuccessful) {
                    throw ProviderException("PROVIDER_ERROR", "upstream ${resp.code} for ${rawUrl.take(120)}")
                }
                val contentType = (resp.header("Content-Type") ?: "application/octet-stream")
                    .substringBefore(';')

                // ---- m3u8: rewrite every nested reference -----------------------
                if (contentType.contains("mpegurl") || rawUrl.substringBefore('?').endsWith(".m3u8")) {
                    val text = resp.body?.string() ?: ""
                    val baseUrl = resp.request.url.toString()
                    resp.close()
                    call.respondText(
                        PlaybackEngine.rewritePlaylist(text, baseUrl, headersB64),
                        ContentType.parse(contentType),
                    )
                } else {
                    call.passthrough(resp)
                }
            } catch (t: Throwable) {
                // Streaming content is drained after this handler returns, so the
                // body was already closed by the pump; closing again is a no-op.
                // This catch only covers failures before/around the respond call.
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
            val headers = PlaybackEngine.decodeHeaders(call.request.queryParameters["h"])

            val resp = PlaybackEngine.open(PlaybackEngine.buildUpstream(rawUrl, headers))
            resp.use {
                if (!it.isSuccessful) {
                    throw ProviderException("PROVIDER_ERROR", "upstream ${it.code} for ${rawUrl.take(120)}")
                }
                // subtitles are small text payloads; read whole body for conversion
                val bytes = it.body?.bytes() ?: ByteArray(0)
                val raw = String(bytes, java.nio.charset.StandardCharsets.UTF_8)
                call.respondText(
                    SubtitleCodec.toVtt(raw),
                    ContentType.parse("text/vtt; charset=UTF-8"),
                )
            }
        }
    }
}
