package cloudstreamweb.routes

import cloudstreamweb.installApiContract
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

/**
 * Phase 5: the optional LAN token (CS_WEB_LAN_TOKEN). /api routes answer 401
 * without the token, /api/health and non-API paths stay open, and both
 * Authorization: Bearer and a token query parameter authenticate (the latter
 * for video/track sources).
 */
class LanTokenAuthTest {

    private fun err(body: String) =
        Json.parseToJsonElement(body).jsonObject["error"]!!.jsonPrimitive.content

    private fun Application.bootWithToken() {
        installApiContract(lanToken = "s3cret")
        routing {
            get("/api/health") { call.respondText("ok") }
            LibraryRoutes.install(this)
        }
    }

    @Test
    fun `token required for api routes when configured`() = testApplication {
        application { bootWithToken() }

        // unauthenticated -> 401 + typed body
        val denied = client.get("/api/library/bookmarks")
        assertEquals(HttpStatusCode.Unauthorized, denied.status)
        assertEquals("UNAUTHORIZED", err(denied.bodyAsText()))

        // health stays open for probes
        assertEquals(HttpStatusCode.OK, client.get("/api/health").status)

        // static app paths are not API: no 401 (SPA shell serves / 404 here)
        assertNotEquals(HttpStatusCode.Unauthorized, client.get("/").status)

        // Authorization: Bearer
        val viaHeader = client.get("/api/library/bookmarks") {
            header(HttpHeaders.Authorization, "Bearer s3cret")
        }
        assertEquals(HttpStatusCode.OK, viaHeader.status)

        // ?token= (media sources cannot send headers)
        val viaQuery = client.get("/api/library/bookmarks?token=s3cret")
        assertEquals(HttpStatusCode.OK, viaQuery.status)

        // wrong token still 401
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/library/bookmarks?token=nope").status)
    }

    @Test
    fun `no token configured means everything passes`() = testApplication {
        application {
            installApiContract(lanToken = null)
            routing { get("/api/health") { call.respondText("ok") } }
        }
        assertEquals(HttpStatusCode.OK, client.get("/api/health").status)
    }
}
