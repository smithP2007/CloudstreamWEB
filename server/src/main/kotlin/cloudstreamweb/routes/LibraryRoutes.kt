package cloudstreamweb.routes

import cloudstreamweb.persistence.Db
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Routing
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable

/**
 * Library parity (Phase 4): bookmarks, watch history and continue-watching.
 * All state lives in SQLite in the user's data dir — no upstream dependency.
 */
object LibraryRoutes {

    @Serializable
    data class BookmarkRequest(
        val provider: String,
        val url: String,
        val name: String,
        val poster: String? = null,
        val type: String? = null,
        val sourcePlugin: String? = null,
    )

    @Serializable
    data class HistoryRequest(
        val provider: String,
        val url: String,
        val episodeData: String,
        val name: String,
        val episodeLabel: String? = null,
        val poster: String? = null,
        val position: Double = 0.0,
        val duration: Double? = null,
        val finished: Boolean = false,
    )

    @Serializable
    data class BookmarkDto(
        val provider: String,
        val url: String,
        val name: String,
        val poster: String? = null,
        val type: String? = null,
        val sourcePlugin: String? = null,
        val createdAt: String? = null,
    )

    @Serializable
    data class HistoryDto(
        val provider: String,
        val url: String,
        val episodeData: String,
        val name: String,
        val episodeLabel: String? = null,
        val poster: String? = null,
        val position: Double,
        val duration: Double? = null,
        val finished: Boolean,
        val updatedAt: String? = null,
    )

    private fun Db.BookmarkRow.dto() = BookmarkDto(provider, url, name, poster, type, sourcePlugin, createdAt)
    private fun Db.HistoryRow.dto() = HistoryDto(provider, url, episodeData, name, episodeLabel, poster, position, duration, finished, updatedAt)

    fun install(routing: Routing) = with(routing) {

        // ---- bookmarks -------------------------------------------------
        get("/api/library/bookmarks") {
            call.respond(Db.listBookmarks().map { it.dto() })
        }

        post("/api/library/bookmarks") {
            val req = call.receive<BookmarkRequest>()
            if (req.provider.isBlank() || req.url.isBlank()) {
                throw ProviderException("REPO_INVALID", "provider and url are required")
            }
            Db.upsertBookmark(req.provider, req.url, req.name, req.poster, req.type, req.sourcePlugin)
            call.respond(BookmarkDto(req.provider, req.url, req.name, req.poster, req.type, req.sourcePlugin))
        }

        get("/api/library/bookmark") {
            val provider = call.request.queryParameters["provider"] ?: ""
            val url = call.request.queryParameters["url"] ?: ""
            call.respond(mapOf("bookmarked" to Db.isBookmarked(provider, url)))
        }

        delete("/api/library/bookmarks") {
            val provider = call.request.queryParameters["provider"]
                ?: throw ProviderException("REPO_INVALID", "missing provider")
            val url = call.request.queryParameters["url"]
                ?: throw ProviderException("REPO_INVALID", "missing url")
            Db.deleteBookmark(provider, url)
            call.respond(HttpStatusCode.NoContent)
        }

        // ---- history ----------------------------------------------------
        get("/api/library/history") {
            val activeOnly = call.request.queryParameters["active"] == "1"
            val limit = (call.request.queryParameters["limit"]?.toIntOrNull() ?: 50).coerceIn(1, 200)
            call.respond(Db.listHistory(activeOnly, limit).map { it.dto() })
        }

        /** Entry lookup for the player: resume position for a concrete episode. */
        get("/api/library/history/entry") {
            val provider = call.request.queryParameters["provider"] ?: ""
            val url = call.request.queryParameters["url"] ?: ""
            val episodeData = call.request.queryParameters["data"] ?: ""
            if (provider.isBlank() || url.isBlank() || episodeData.isBlank()) {
                throw ProviderException("REPO_INVALID", "provider, url and data are required")
            }
            val row = Db.getHistoryEntry(provider, url, episodeData)
            if (row == null) call.respond(HttpStatusCode.NotFound, ErrorBody("ENTRY_NOT_FOUND", "no history entry"))
            else call.respond(row.dto())
        }

        post("/api/library/history") {
            val req = call.receive<HistoryRequest>()
            if (req.provider.isBlank() || req.url.isBlank() || req.episodeData.isBlank()) {
                throw ProviderException("REPO_INVALID", "provider, url and episodeData are required")
            }
            val position = req.position.coerceAtLeast(0.0)
            val finished = req.finished ||
                (req.duration != null && req.duration > 0 && position >= req.duration - 45.0)
            Db.upsertHistory(
                req.provider, req.url, req.episodeData, req.name, req.episodeLabel,
                req.poster, position, req.duration, finished,
            )
            // history is the backing store of Continue Watching (upstream-parity)
            call.respond(HttpStatusCode.NoContent)
        }

        delete("/api/library/history") {
            val provider = call.request.queryParameters["provider"]
            val url = call.request.queryParameters["url"]
            if (provider != null && url != null) {
                val episodeData = call.request.queryParameters["data"]
                Db.deleteHistory(provider, url, episodeData)
            } else {
                Db.deleteAllHistory()
            }
            call.respond(HttpStatusCode.NoContent)
        }

        // ---- continue watching = history; finished=false, position>5 -------
        get("/api/library/continue") {
            val limit = (call.request.queryParameters["limit"]?.toIntOrNull() ?: 20).coerceIn(1, 100)
            call.respond(Db.listHistory(activeOnly = true, limit = limit).map { it.dto() })
        }
    }
}
