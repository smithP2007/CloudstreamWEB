package cloudstreamweb.routes

import cloudstreamweb.installApiContract
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.application.Application
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * M2: /api/capabilities reflects host truth; /api/settings is the five-toggle
 * contract (GET full body, POST partial patch). The settings namespace is a
 * per-machine singleton, so every test resets it to factory defaults first —
 * test re-runs stay deterministic.
 */
class SystemRoutesTest {

    private val json = Json { ignoreUnknownKeys = true }

    @BeforeEach
    fun resetSettings() {
        cloudstreamweb.engine.PlaybackSettings.resetToDefaults()
    }

    @AfterEach
    fun tidyUp() {
        cloudstreamweb.engine.PlaybackSettings.resetToDefaults()
    }

    private fun Application.bootSystem() {
        installApiContract(lanToken = null)
        routing { SystemRoutes.install(this) }
    }

    private fun obj(body: String) = json.parseToJsonElement(body).jsonObject

    @Test
    fun `settings factory defaults hold`() = testApplication {
        application { bootSystem() }
        val resp = client.get("/api/settings")
        assertEquals(HttpStatusCode.OK, resp.status)
        val s = obj(resp.bodyAsText())
        assertEquals(true, s["audioRemux"]!!.jsonPrimitive.boolean)
        assertEquals(true, s["probeSources"]!!.jsonPrimitive.boolean)
        assertEquals(false, s["liveTranscode"]!!.jsonPrimitive.boolean)
        assertEquals("auto", s["gpuAccel"]!!.jsonPrimitive.content)
        assertEquals(false, s["convertAfterDownload"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `patch round-trip persists between calls`() = testApplication {
        application { bootSystem() }
        val resp = client.post("/api/settings") {
            contentType(ContentType.Application.Json)
            setBody("""{"liveTranscode": true, "gpuAccel": "on", "audioRemux": false}""")
        }
        assertEquals(HttpStatusCode.OK, resp.status)
        // response echoes the FULL updated body
        val full = obj(resp.bodyAsText())
        assertEquals(true, full["liveTranscode"]!!.jsonPrimitive.boolean)
        assertEquals("on", full["gpuAccel"]!!.jsonPrimitive.content)
        assertEquals(false, full["audioRemux"]!!.jsonPrimitive.boolean)
        // untouched field stays at its default
        assertEquals(true, full["probeSources"]!!.jsonPrimitive.boolean)

        // and a fresh GET sees the persisted state
        val again = obj(client.get("/api/settings").bodyAsText())
        assertEquals(true, again["liveTranscode"]!!.jsonPrimitive.boolean)
        assertEquals("on", again["gpuAccel"]!!.jsonPrimitive.content)
    }

    @Test
    fun `gpuAccel garbage is a 400 recoil and does not persist`() = testApplication {
        application { bootSystem() }
        val resp = client.post("/api/settings") {
            contentType(ContentType.Application.Json)
            setBody("""{"gpuAccel": "always"}""")
        }
        assertEquals(HttpStatusCode.BadRequest, resp.status)
        assertEquals("REPO_INVALID", obj(resp.bodyAsText())["error"]!!.jsonPrimitive.content)
        // the invalid value must not have been written
        assertEquals("auto", obj(client.get("/api/settings").bodyAsText())["gpuAccel"]!!.jsonPrimitive.content)
    }

    @Test
    fun `capabilities shape is complete for the SPA`() = testApplication {
        application { bootSystem() }
        val resp = client.get("/api/capabilities")
        assertEquals(HttpStatusCode.OK, resp.status)
        val caps = obj(resp.bodyAsText())
        // booleans + nullable strings + list — keys must all exist regardless of host
        listOf("ffmpegAvailable", "ffprobeAvailable", "audioRemuxAvailable", "liveTranscodeAvailable", "decodeProbeAvailable")
            .forEach { assertTrue(caps.containsKey(it), "missing capability key: $it") }
        // each available flag agrees with its source flag
        assertEquals(
            caps["ffmpegAvailable"]!!.jsonPrimitive.boolean && caps["ffprobeAvailable"]!!.jsonPrimitive.boolean,
            caps["audioRemuxAvailable"]!!.jsonPrimitive.boolean,
        )
        assertTrue(caps.containsKey("gpuAccelerators"))
        assertTrue(caps["gpuAccelerators"]!!.toString().startsWith("["))
    }
}
