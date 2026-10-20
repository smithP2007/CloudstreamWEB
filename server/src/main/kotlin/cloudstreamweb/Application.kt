package cloudstreamweb

import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.response.respondText
import io.ktor.server.routing.routing
import io.ktor.server.routing.get
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Files

private const val APP_VERSION = "0.1.0-phase0"

/**
 * Phase 0 entry point.
 * Minimal but functional: boots Ktor, exposes /api/health, and serves the SPA
 * from web/dist when present. Sidebar/pages become real in later phases.
 */
fun main() {
    val dataDir = Config.ensureDataDir()
    System.err.println("cloud-stream-web $APP_VERSION starting; data dir: $dataDir; binding ${Config.host}:${Config.port}")

    val staticRoot = PathProvider.spaDirectory() // java.nio.file.Path to web/dist (may be null)

    embeddedServer(
        Netty,
        host = Config.host,
        port = Config.port,
        watchPaths = emptyList(),
    ) {
        routing {
            get("/api/health") {
                // Force-load the upstream plugin API classes here so a missing/incompatible
                // artifact fails loudly at startup instead of at plugin load time.
                val apiProbe = ApiProbe.probe()
                call.respondText(
                    Json.encodeToString(
                        buildJsonObject {
                            put("ok", apiProbe.ok)
                            put("version", APP_VERSION)
                            put("libraryAvailable", apiProbe.detail)
                        }
                    ),
                    io.ktor.http.ContentType.Application.Json
                )
            }

            if (staticRoot != null) {
                Spas.addSpaRouting(this, staticRoot)
            } else {
                get("/") {
                    call.respondText(
                        "cloud-stream-web backend is running, but web/dist was not built yet.\n" +
                            "Run scripts/run.sh (or: cd web && npm i && npm run build).",
                        io.ktor.http.ContentType.Text.Plain
                    )
                }
            }
        }
    }.start(wait = true)
}

/** Small indirection so the static setup stays testable without Ktor. */
private object PathProvider {
    fun spaDirectory(): java.nio.file.Path? {
        // 1) explicit override (used by scripts/run.sh)
        System.getenv("CS_WEB_STATIC_DIR")?.let { p ->
            if (Files.isDirectory(java.nio.file.Path.of(p))) return java.nio.file.Path.of(p)
        }
        // 2) conventional location relative to repo root / application dir
        val candidates = listOf(
            java.nio.file.Path.of("web/dist"),
            java.nio.file.Path.of("../web/dist"),
        )
        return candidates.firstOrNull { Files.isDirectory(it) }
    }
}
