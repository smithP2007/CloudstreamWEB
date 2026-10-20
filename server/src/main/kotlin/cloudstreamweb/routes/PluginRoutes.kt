package cloudstreamweb.routes

import cloudstreamweb.persistence.Db
import cloudstreamweb.plugins.ExtensionRuntime
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

@Serializable
data class InstallRequest(val repositoryUrl: String, val internalName: String)

@Serializable
data class JobResponse(val jobId: String)

@Serializable
data class InternalNameRequest(val internalName: String)

@Serializable
data class ProviderView(
    val name: String,
    val mainUrl: String,
    val sourcePlugin: String? = null,
    val javaType: String,
    val tvTypes: List<String> = emptyList(),
    val hasMainPage: Boolean = false,
)

@Serializable
data class InstalledView(
    val internalName: String,
    val repositoryUrl: String,
    val pluginUrl: String,
    val version: Int,
    val name: String,
    val loadState: String,
    val loadDetail: String? = null,
)

object PluginRoutes {
    fun install(routing: Routing) = with(routing) {
        post("/api/plugins/install") {
            val req = call.receive<InstallRequest>()
            if (req.repositoryUrl.isBlank() || req.internalName.isBlank()) {
                call.respond(HttpStatusCode.BadRequest, ErrorBody("REPO_INVALID", "repositoryUrl and internalName required"))
                return@post
            }
            val job = ExtensionRuntime.beginInstall(req.repositoryUrl, req.internalName)
            call.respond(JobResponse(jobId = job.id))
        }

        get("/api/plugins/install/{jobId}") {
            val id = call.parameters["jobId"] ?: ""
            val job = ExtensionRuntime.jobView(id)
            if (job == null) {
                call.respond(HttpStatusCode.NotFound, ErrorBody("ENTRY_NOT_FOUND", "unknown job $id"))
            } else {
                call.respond(job)
            }
        }

        post("/api/plugins/uninstall") {
            val req = call.receive<InternalNameRequest>()
            withContext(Dispatchers.IO) { ExtensionRuntime.uninstall(req.internalName) }
            call.respond(HttpStatusCode.NoContent, "")
        }

        get("/api/plugins/installed") {
            val rows = withContext(Dispatchers.IO) { Db.listInstalled() }
            call.respond(rows.map {
                InstalledView(
                    internalName = it.internalName,
                    repositoryUrl = it.repositoryUrl,
                    pluginUrl = it.pluginUrl,
                    version = it.version,
                    name = it.pluginName,
                    loadState = it.loadState,
                    loadDetail = it.loadDetail,
                )
            })
        }

        get("/api/providers") {
            val providers = com.lagradost.cloudstream3.APIHolder.allProviders.map { api ->
                ProviderView(
                    name = api.name,
                    mainUrl = api.mainUrl,
                    sourcePlugin = api.sourcePlugin,
                    javaType = api.javaClass.simpleName,
                    tvTypes = (api.supportedTypes.takeIf { it.isNotEmpty() } ?: emptySet()).map { it.name },
                    hasMainPage = api.hasMainPage,
                )
            }
            call.respond(providers)
        }
    }
}
