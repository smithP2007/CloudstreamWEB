package cloudstreamweb.routes

import cloudstreamweb.engine.Capabilities
import cloudstreamweb.engine.PlaybackSettings
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import io.ktor.server.routing.post

/**
 * M2 system routes:
 *
 *   GET  /api/capabilities -> what the host can contribute (ffmpeg, ffprobe,
 *                              GPU accelerators); SPA grays out dependent
 *                              toggles from this truth.
 *   GET  /api/settings     -> the five playback toggles (PlaybackSettings).
 *   POST /api/settings     -> partial patch {audioRemux?, probeSources?,
 *                              liveTranscode?, gpuAccel?, convertAfterDownload?}
 *                              -> the FULL updated settings DTO. Unknown
 *                              values 400 with REPO_INVALID.
 *
 * All three sit behind the LAN token guard like every other /api route.
 */
object SystemRoutes {

    fun install(routing: Routing) = with(routing) {
        get("/api/capabilities") {
            call.respond(Capabilities.probe())
        }

        get("/api/settings") {
            call.respond(PlaybackSettings.current())
        }

        post("/api/settings") {
            val patch = call.receive<PlaybackSettings.SettingsPatch>()
            call.respond(PlaybackSettings.update(patch))
        }
    }
}
