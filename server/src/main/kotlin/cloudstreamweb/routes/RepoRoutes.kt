package cloudstreamweb.routes

import cloudstreamweb.catalog.CatalogEntry
import cloudstreamweb.catalog.RepoEntry
import cloudstreamweb.catalog.RepoFetcher
import cloudstreamweb.catalog.RepoUrl
import cloudstreamweb.persistence.Db
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Routing
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Serializable
data class CreateRepoRequest(val input: String)

@Serializable
data class CreateRepoResponse(val url: String, val name: String?, val description: String?, val manifestVersion: Int)

@Serializable
data class PluginView(
    val url: String,
    val name: String,
    val internalName: String,
    val status: Int,
    val version: Int,
    val apiVersion: Int,
    val language: String? = null,
    val iconUrl: String? = null,
    val description: String? = null,
    val tvTypes: List<String>? = null,
    val authors: List<String> = emptyList(),
    val installed: Boolean = false,
    val loadState: String = "NONE",
)

@Serializable
data class ErrorBody(val error: String, val detail: String? = null)

/**
 * Phase 1 endpoints: catalog, add/remove repos, list repos, browse plugins.
 * (Installation of plugins is Phase 2.)
 */
object RepoRoutes {
    private val fetcher = RepoFetcher()

    fun install(routing: Routing) = with(routing) {
        get("/api/catalog") {
            val entries: List<CatalogEntry> = withContext(Dispatchers.IO) { fetcher.catalog() }
            call.respond(entries.sortedBy { !it.verified })
        }

        post("/api/repos") {
            val req = call.receive<CreateRepoRequest>()
            if (req.input.isBlank()) {
                call.respond(HttpStatusCode.BadRequest, ErrorBody("REPO_INVALID", "empty input"))
                return@post
            }
            val manifestUrl = RepoUrl.resolve(req.input, fetcherHttp())
            if (manifestUrl == null) {
                call.respond(HttpStatusCode.BadRequest, ErrorBody("REPO_INVALID", "not a known repo URL or shortcode"))
                return@post
            }
            try {
                val manifest = withContext(Dispatchers.IO) { fetcher.fetchManifest(manifestUrl) }
                val entry = RepoEntry(
                    url = manifestUrl,
                    name = manifest.name,
                    description = manifest.description,
                    iconUrl = manifest.iconUrl,
                    manifestVersion = manifest.manifestVersion,
                )
                withContext(Dispatchers.IO) { Db.upsertRepository(entry) }
                call.respond(CreateRepoResponse(manifestUrl, manifest.name, manifest.description, manifest.manifestVersion))
            } catch (t: Throwable) {
                call.respond(HttpStatusCode.BadGateway, ErrorBody("REPO_PARSE_FAILED", "${t.message}"))
            }
        }

        get("/api/repos") {
            call.respond(withContext(Dispatchers.IO) { Db.getRepositories() })
        }

        delete("/api/repos") {
            val url = call.request.queryParameters["url"]
            if (url.isNullOrBlank()) {
                call.respond(HttpStatusCode.BadRequest, ErrorBody("REPO_INVALID", "missing url param"))
                return@delete
            }
            withContext(Dispatchers.IO) { Db.deleteRepository(url) }
            call.respond(HttpStatusCode.NoContent, "")
        }

        get("/api/repos/{b64}/plugins") {
            val b64 = call.parameters["b64"]
            val manifestUrl = b64?.let(::decodeUrlParam)
            if (manifestUrl == null) {
                call.respond(HttpStatusCode.BadRequest, ErrorBody("REPO_INVALID", "bad base64 param"))
                return@get
            }
            try {
                val plugins = withContext(Dispatchers.IO) { fetcher.repoPlugins(manifestUrl) }
                // installed-state lookup lands here in Phase 2 (registry)
                call.respond(plugins.map { p ->
                    PluginView(
                        url = p.url, name = p.name, internalName = p.internalName,
                        status = p.status, version = p.version, apiVersion = p.apiVersion,
                        language = p.language, iconUrl = p.iconUrl, description = p.description,
                        tvTypes = p.tvTypes, authors = p.authors,
                    )
                })
            } catch (t: Throwable) {
                call.respond(HttpStatusCode.BadGateway, ErrorBody("REPO_PARSE_FAILED", "${t.message}"))
            }
        }
    }

    private fun fetcherHttp() = cloudstreamweb.catalog.OkHttp()

    fun encodeUrlParam(url: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(url.toByteArray())

    fun decodeUrlParam(b64: String): String? = try {
        String(Base64.getUrlDecoder().decode(b64))
    } catch (t: Throwable) {
        null
    }
}
