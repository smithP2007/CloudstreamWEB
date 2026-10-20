package cloudstreamweb

import io.ktor.server.http.content.staticFiles
import io.ktor.server.routing.Routing
import java.io.File
import java.nio.file.Path

/**
 * SPA hosting: serves all concrete files from web/dist and falls back to
 * index.html for unknown paths so client-side routes survive hard refresh.
 * Registered AFTER the /api routes, so unknown API paths degrade gracefully.
 */
object Spas {
    fun addSpaRouting(routing: Routing, staticRoot: Path) {
        val rootDir = staticRoot.toFile()
        routing.staticFiles("/", rootDir) {
            default("index.html")
        }
    }
}
