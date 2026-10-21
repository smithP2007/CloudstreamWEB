package cloudstreamweb

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.path
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.routing
import io.ktor.server.routing.get
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path

@Serializable
data class HealthBody(val ok: Boolean, val version: String, val libraryAvailable: String)

private const val APP_VERSION = "0.2.0-phase1"

/** Small indirection so the static setup stays testable without Ktor. */
object PathProvider {
    fun spaDirectory(): Path? {
        // 1) explicit override (used by scripts/run.sh)
        System.getenv("CS_WEB_STATIC_DIR")?.let { p ->
            if (Files.isDirectory(Path.of(p))) return Path.of(p)
        }
        // 2) conventional locations relative to repo root / working dir
        val candidates = listOf(
            Path.of("web/dist"),
            Path.of("../web/dist"),
        )
        return candidates.firstOrNull { Files.isDirectory(it) }
    }
}

/**
 * Entry point. Boots Ktor on RuntimeConfig.host:port, exposes the API and
 * serves the SPA from web/dist when it exists. API routes come FIRST so
 * static fallback never shadows them.
 */
fun main() {
    val dataDir = RuntimeConfig.ensureDataDir()
    System.err.println("cloud-stream-web $APP_VERSION starting; data dir: $dataDir; binding ${RuntimeConfig.host}:${RuntimeConfig.port}")

    // Fail fast if the upstream artifact is broken/missing
    val probe = ApiProbe.probe()
    check(probe.ok) { "Upstream plugin API failed to load: ${probe.detail}" }

    // Reload previously-installed plugins in the background
    cloudstreamweb.plugins.ExtensionRuntime.restoreAll()

    val staticRoot = PathProvider.spaDirectory()

    embeddedServer(
        Netty,
        host = RuntimeConfig.host,
        port = RuntimeConfig.port,
        watchPaths = emptyList(),
    ) {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true; encodeDefaults = true })
        }

        // Typed error contract for the SPA + full stack traces in the server log
        install(StatusPages) {
            exception<cloudstreamweb.routes.ProviderException> { call, cause ->
                val status = when (cause.kind) {
                    "REPO_INVALID" -> HttpStatusCode.BadRequest
                    "PROVIDER_NOT_FOUND", "ENTRY_NOT_FOUND" -> HttpStatusCode.NotFound
                    else -> HttpStatusCode.BadGateway
                }
                call.respond(status, cloudstreamweb.routes.ErrorBody(cause.kind, cause.message))
            }
            exception<Throwable> { call, cause ->
                System.err.println("[api-error] ${call.request.path()}")
                cause.printStackTrace()
                call.respond(
                    HttpStatusCode.InternalServerError,
                    cloudstreamweb.routes.ErrorBody(
                        "INTERNAL",
                        (cause.message ?: cause.javaClass.simpleName).take(300),
                    )
                )
            }
        }

        routing {
            get("/api/health") {
                call.respond(HealthBody(ok = true, version = APP_VERSION, libraryAvailable = probe.detail))
            }

            cloudstreamweb.routes.RepoRoutes.install(this)
            cloudstreamweb.routes.PluginRoutes.install(this)
            cloudstreamweb.routes.ContentRoutes.install(this)
            cloudstreamweb.routes.StreamProxy.install(this)

            if (staticRoot != null) {
                Spas.addSpaRouting(this, staticRoot)
            } else {
                get("/") {
                    call.respondText(
                        "cloud-stream-web backend is running, but web/dist was not built yet.\n" +
                            "Run scripts/run.sh (or: cd web && npm i && npm run build).",
                        ContentType.Text.Plain
                    )
                }
            }
        }
    }.start(wait = true)
}
