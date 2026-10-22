package cloudstreamweb.routes

import cloudstreamweb.errors.ErrorBody
import cloudstreamweb.errors.ProviderException
import cloudstreamweb.library.DownloadManager
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
 * Phase-4 download queue: enqueue a provider link, watch progress, cancel,
 * resume, delete. Files land in <dataDir>/Downloads.
 */
object DownloadsRoutes {

    @Serializable
    data class LinkBody(val url: String, val headers: Map<String, String> = emptyMap())

    @Serializable
    data class DownloadRequest(
        val provider: String? = null,
        val url: String? = null,
        val name: String,
        val link: LinkBody,
        /** optional e.g. "mkv" — extracted by the SPA from the extractor's link name */
        val extHint: String? = null,
    )

    @Serializable
    data class DownloadDto(
        val id: String,
        val provider: String? = null,
        val url: String? = null,
        val name: String,
        val state: String,
        val downloadedBytes: Long,
        val totalBytes: Long? = null,
        val detail: String? = null,
        val filePath: String? = null,
        val createdAt: String? = null,
    )

    private fun Db.DownloadRow.dto() = DownloadDto(
        id = id,
        provider = provider,
        url = url,
        name = name,
        state = state,
        downloadedBytes = downloadedBytes,
        totalBytes = totalBytes,
        detail = detail,
        filePath = filePath,
        createdAt = createdAt,
    )

    fun install(routing: Routing) = with(routing) {

        get("/api/downloads") {
            call.respond(Db.listDownloads().map { it.dto() })
        }

        post("/api/downloads") {
            val req = call.receive<DownloadRequest>()
            val linkUrl = req.link.url
            if (linkUrl.isBlank() || (!linkUrl.startsWith("http://") && !linkUrl.startsWith("https://"))) {
                throw ProviderException("REPO_INVALID", "link.url must be an http(s) URL")
            }
            if (req.name.isBlank()) {
                throw ProviderException("REPO_INVALID", "name is required")
            }
            val row = DownloadManager.enqueue(
                provider = req.provider,
                url = req.url,
                name = req.name,
                linkUrl = linkUrl,
                headers = req.link.headers,
                extHint = req.extHint,
            )
            call.respond(HttpStatusCode.Created, row.dto())
        }

        /** Resume/retry: continues from the stored byte offset (ranges when supported). */
        post("/api/downloads/{id}/resume") {
            val id = call.parameters["id"]
                ?: throw ProviderException("REPO_INVALID", "missing id")
            val row = Db.getDownload(id)
                ?: throw ProviderException("ENTRY_NOT_FOUND", "no download $id")
            if (row.state == "DONE") {
                throw ProviderException("REPO_INVALID", "download already finished")
            }
            DownloadManager.resume(id)
            call.respond(Db.getDownload(id)?.dto() ?: row.dto())
        }

        /** Stop without deleting anything (partial file kept for later resume). */
        post("/api/downloads/{id}/cancel") {
            val id = call.parameters["id"]
                ?: throw ProviderException("REPO_INVALID", "missing id")
            DownloadManager.cancel(id)
            call.respond(Db.listDownloads().firstOrNull { it.id == id }?.dto()
                ?: throw ProviderException("ENTRY_NOT_FOUND", "no download $id"))
        }

        delete("/api/downloads/{id}") {
            val id = call.parameters["id"]
                ?: throw ProviderException("REPO_INVALID", "missing id")
            DownloadManager.delete(id)
            call.respond(HttpStatusCode.NoContent)
        }
    }
}
