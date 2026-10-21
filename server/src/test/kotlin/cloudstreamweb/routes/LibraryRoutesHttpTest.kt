package cloudstreamweb.routes

import cloudstreamweb.installApiContract
import io.ktor.client.request.delete
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
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * HTTP-level checks for the library endpoints (Phase 4), with the same typed
 * error contract the live server installs, including the auto-finish margin
 * (position >= duration - 45s marks the entry finished).
 */
class LibraryRoutesHttpTest {

    private fun unique() = java.util.UUID.randomUUID().toString().substring(0, 8)

    private fun Application.installLibrary(extra: (io.ktor.server.routing.Routing) -> Unit = {}) {
        installApiContract()
        routing {
            LibraryRoutes.install(this)
            extra(this)
        }
    }

    private fun obj(json: String) = Json.parseToJsonElement(json).jsonObject

    @Test
    fun `history post auto-finishes near the end`() = testApplication {
        val provider = "testProv"
        val url = "https://src/${unique()}"
        val ep = "ep-$url"

        application {
            installLibrary()
        }

        // far from the end -> not finished
        client.post("/api/library/history") {
            contentType(ContentType.Application.Json)
            setBody(
                """{"provider":"$provider","url":"$url","episodeData":"$ep","name":"S","episodeLabel":"S1E1","position":100,"duration":1000}""",
            )
        }
        var entry = obj(
            client.get("/api/library/history/entry?provider=$provider&url=$url&data=$ep").bodyAsText(),
        )
        assertEquals("false", entry["finished"]!!.jsonPrimitive.content)

        // within the 45s margin -> finished
        client.post("/api/library/history") {
            contentType(ContentType.Application.Json)
            setBody(
                """{"provider":"$provider","url":"$url","episodeData":"$ep","name":"S","episodeLabel":"S1E1","position":970,"duration":1000}""",
            )
        }
        entry = obj(
            client.get("/api/library/history/entry?provider=$provider&url=$url&data=$ep").bodyAsText(),
        )
        assertEquals("true", entry["finished"]!!.jsonPrimitive.content)
        assertEquals("970.0", entry["position"]!!.jsonPrimitive.content)

        // continue-watching must NOT offer finished entries
        val cont = client.get("/api/library/continue").bodyAsText()
        assertFalse(cont.contains(url), "finished entry leaked into continue: $cont")

        client.delete("/api/library/history?provider=$provider&url=$url&data=$ep")
    }

    @Test
    fun `bookmark flag endpoint reflects create and delete`() = testApplication {
        val provider = "testProv"
        val url = "https://src/${unique()}"
        application {
            installLibrary()
        }

        suspend fun bookmarked() = obj(
            client.get("/api/library/bookmark?provider=$provider&url=$url").bodyAsText(),
        )["bookmarked"]!!.jsonPrimitive.content

        assertEquals("false", bookmarked())
        val created = client.post("/api/library/bookmarks") {
            contentType(ContentType.Application.Json)
            setBody("""{"provider":"$provider","url":"$url","name":"Movie","poster":"p.jpg","type":"MOVIE"}""")
        }
        assertEquals(HttpStatusCode.OK, created.status)
        assertEquals("true", bookmarked())
        client.delete("/api/library/bookmarks?provider=$provider&url=$url")
        assertEquals("false", bookmarked())
    }

    @Test
    fun `typed error contract on bad input`() = testApplication {
        application {
            installLibrary()
        }
        val bad = client.post("/api/library/history") {
            contentType(ContentType.Application.Json)
            setBody("""{"provider":"x","url":"y","episodeData":"","name":"n"}""")
        }
        assertEquals(HttpStatusCode.BadRequest, bad.status)
        assertEquals("REPO_INVALID", obj(bad.bodyAsText())["error"]!!.jsonPrimitive.content)
    }

    @Test
    fun `download endpoints queue report and delete`() = testApplication {
        application {
            installApiContract()
            routing {
                LibraryRoutes.install(this)
                DownloadsRoutes.install(this)
            }
        }
        val name = unique()
        val create = client.post("/api/downloads") {
            contentType(ContentType.Application.Json)
            setBody("""{"provider":"testProv","name":"$name","link":{"url":"http://127.0.0.1:1/x.mp4"}}""")
        }
        // unreachable target -> the job fails asynchronously; the row exists either way
        assertEquals(HttpStatusCode.Created, create.status)
        val rowId = obj(create.bodyAsText())["id"]!!.jsonPrimitive.content
        assertTrue(rowId.isNotBlank())

        val listed = client.get("/api/downloads").bodyAsText()
        assertTrue(listed.contains(name), "row missing from list: $listed")

        // cancel on a missing id -> 404 via the typed contract
        val missing = client.post("/api/downloads/zzzz/cancel")
        assertEquals(HttpStatusCode.NotFound, missing.status)
        assertEquals("ENTRY_NOT_FOUND", obj(missing.bodyAsText())["error"]!!.jsonPrimitive.content)

        // resume of a missing id -> same
        assertEquals(HttpStatusCode.NotFound, client.post("/api/downloads/zzzz/resume").status)

        client.delete("/api/downloads/$rowId")
        assertFalse(
            client.get("/api/downloads").bodyAsText().contains(rowId),
            "row should be gone after delete",
        )
    }
}
