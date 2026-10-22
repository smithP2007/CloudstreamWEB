package cloudstreamweb.routes

import cloudstreamweb.engine.Capabilities
import cloudstreamweb.engine.PlaybackEngine
import cloudstreamweb.engine.PlaybackSettings
import cloudstreamweb.engine.Remux
import cloudstreamweb.engine.SubtitleCodec
import cloudstreamweb.engine.passthrough
import cloudstreamweb.engine.respondRemux
import cloudstreamweb.errors.ErrorCodes
import cloudstreamweb.errors.ProviderException
import io.ktor.http.ContentType
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get

/**
 * Playback bridge routes (M3): thin adapters only. The mechanics — shared
 * OkHttp client, header replay, Range forwarding, the streaming pump, manifest
 * rewriting, subtitle conversion, linkId hashing, audio probing + remux —
 * live in cloudstreamweb.engine.*. This file decides WHICH branch serves a
 * request and turns upstream status into typed errors:
 *
 *   403 -> UPSTREAM_403 (SPA auto re-extracts once)
 *   410 -> URL_EXPIRED  (SPA auto re-extracts once)
 *   else -> PROVIDER_ERROR
 *
 *   GET /api/stream?url=<upstream>&h=<b64-headers>[&audio=<ordinal>][&start=<sec>][&raw=1]
 *
 * Direct-file branches:
 *  - `audio=<ordinal>`            -> ffmpeg remux (`-c copy`) into fragmented
 *     MP4 (501 FFMPEG_MISSING when ffmpeg is absent).
 *  - mkv container + audioRemux toggle on (no audio param, no `raw=1`)
 *                                 -> DEFAULT remux with the mux's default
 *     audio: Chromium has no Matroska container support, so a muxed mkv is
 *     unplayable without this.
 *  - otherwise                    -> byte passthrough (headers + Range 1:1).
 *
 * `raw=1` (self-connections from ffmpeg/ffprobe) forces passthrough: they
 * must receive the ORIGINAL container or we would remux our own remux.
 * HLS (m3u8) is detected by content type / extension and rewritten instead.
 */
object StreamProxy {

    private fun codeForUpstream(status: Int): String = when (status) {
        403 -> ErrorCodes.UPSTREAM_403
        410 -> ErrorCodes.URL_EXPIRED
        else -> ErrorCodes.PROVIDER_ERROR
    }

    private fun requireHttpUrl(rawUrl: String?) {
        if (rawUrl == null) throw ProviderException("REPO_INVALID", "missing url")
        if (!rawUrl.startsWith("http://") && !rawUrl.startsWith("https://")) {
            throw ProviderException("REPO_INVALID", "only http(s) links can be streamed")
        }
    }

    fun install(routing: Routing) = with(routing) {
        get("/api/stream/audio-tracks") {
            val rawUrl = call.request.queryParameters["url"]
            requireHttpUrl(rawUrl)
            val url = rawUrl!!
            val headers = PlaybackEngine.decodeHeaders(call.request.queryParameters["h"])
            call.respond(Remux.probeAudio(url, headers, PlaybackEngine.linkId(url, headers)))
        }

        get("/api/stream") {
            val rawUrl = call.request.queryParameters["url"]
            requireHttpUrl(rawUrl)
            val url = rawUrl!!
            val rawMode = call.request.queryParameters["raw"] == "1"

            val headers = PlaybackEngine.decodeHeaders(call.request.queryParameters["h"])
            val headersB64 = PlaybackEngine.headersEncoded(headers)
            val range = call.request.headers["Range"]

            val resp = PlaybackEngine.open(PlaybackEngine.buildUpstream(url, headers, range))
            try {
                if (!resp.isSuccessful) {
                    throw ProviderException(
                        codeForUpstream(resp.code),
                        "upstream ${resp.code} for ${url.take(120)}",
                    )
                }
                val contentType = (resp.header("Content-Type") ?: "application/octet-stream")
                    .substringBefore(';')

                // ---- HLS: rewrite every nested reference -----------------------
                val isPlaylist = contentType.contains("mpegurl") || url.substringBefore('?').endsWith(".m3u8")
                if (isPlaylist) {
                    val text = resp.body?.string() ?: ""
                    val baseUrl = resp.request.url.toString()
                    resp.close()
                    call.respondText(
                        PlaybackEngine.rewritePlaylist(text, baseUrl, headersB64),
                        ContentType.parse(contentType),
                    )
                } else {
                    // ---- file: one of remux(default)/remux(explicit)/passthrough
                    val audioParam = call.request.queryParameters["audio"]?.toIntOrNull()
                    if (audioParam != null && audioParam < 0) {
                        throw ProviderException("REPO_INVALID", "audio must be a >= 0 ordinal")
                    }
                    val start = call.request.queryParameters["start"]?.toDoubleOrNull()
                    val caps = Capabilities.probe()
                    val settings = PlaybackSettings.current()

                    val defaultMkvRemux = !rawMode && audioParam == null &&
                        settings.audioRemux && caps.audioRemuxAvailable &&
                        contentType.contains("matroska")

                    when {
                        audioParam != null -> {
                            // EXPLICIT language pick — ffmpeg alone can map 0:a:<ordinal>
                            if (!caps.liveTranscodeAvailable) {
                                throw ProviderException(
                                    ErrorCodes.FFMPEG_MISSING,
                                    "audio switching needs ffmpeg (scripts/bootstrap.sh)",
                                )
                            }
                            resp.close()
                            call.respondRemux(
                                Remux.buildRemuxCommand(Remux.selfProxyInput(url, headers), audioParam, start),
                                "remux",
                            )
                        }
                        defaultMkvRemux -> {
                            val tracks = Remux.probeAudio(url, headers, PlaybackEngine.linkId(url, headers))
                            resp.close()
                            call.respondRemux(
                                Remux.buildRemuxCommand(
                                    Remux.selfProxyInput(url, headers),
                                    Remux.defaultAudioOrdinal(tracks),
                                    start,
                                ),
                                "remux",
                            )
                        }
                        else -> call.passthrough(resp)
                    }
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
            requireHttpUrl(rawUrl)
            val url = rawUrl!!
            val headers = PlaybackEngine.decodeHeaders(call.request.queryParameters["h"])

            val resp = PlaybackEngine.open(PlaybackEngine.buildUpstream(url, headers))
            resp.use {
                if (!it.isSuccessful) {
                    throw ProviderException(codeForUpstream(it.code), "upstream ${it.code} for ${url.take(120)}")
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
