package cloudstreamweb.routes

import cloudstreamweb.installApiContract
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicReference
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer

/**
 * v2 M0 golden routing baseline for the playback bridge (offline: the
 * "upstream" is a JDK-internal HTTP server on loopback):
 *
 *  - m3u8 content is manifest-rewritten, content type preserved,
 *  - binary passthrough replays the client-side Range upstream and copies
 *    Content-Length / Content-Range / Accept-Ranges back,
 *  - upstream failures surface as typed ProviderError bodies,
 *  - every response carries an X-Req-Id (request-id plumbing),
 *  - /api/subtitle converts SRT to WebVTT.
 */
class StreamProxyRoutingTest {

    private lateinit var upstream: HttpServer
    private val lastRange = AtomicReference<String?>(null)

    private val filler = ByteArray(1536) { ((it % 94) + 33).toByte() }

    private val masterPlaylist: String by lazy {
        javaClass.getResourceAsStream("/fixtures/master.m3u8")!!
            .readBytes().toString(StandardCharsets.UTF_8)
    }

    private val srt = """
        1
        00:00:01,000 --> 00:00:03,500
        Hello proxy world
    """.trimIndent()

    private fun up(path: String): String = "http://127.0.0.1:${upstream.address.port}$path"

    private fun proxied(path: String, withHeaders: Boolean = false): String {
        val enc = URLEncoder.encode(up(path), StandardCharsets.UTF_8)
        return "/api/stream?url=$enc" + if (withHeaders) "&h=e309" else ""
    }

    @BeforeEach
    fun startUpstream() {
        upstream = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        with(upstream) {
            createContext("/master.m3u8") { ex: HttpExchange ->
                val b = masterPlaylist.toByteArray(StandardCharsets.UTF_8)
                ex.responseHeaders.set("Content-Type", "application/vnd.apple.mpegurl")
                ex.sendResponseHeaders(200, b.size.toLong())
                ex.responseBody.use { it.write(b) }
            }
            createContext("/file.bin") { ex ->
                lastRange.set(ex.requestHeaders.getFirst("Range"))
                if (ex.requestHeaders.getFirst("Range") != null) {
                    ex.responseHeaders.set("Content-Range", "bytes 5-9/${filler.size}")
                }
                ex.responseHeaders.set("Accept-Ranges", "bytes")
                ex.sendResponseHeaders(200, filler.size.toLong())
                ex.responseBody.use { it.write(filler) }
            }
            createContext("/denied") { ex -> ex.sendResponseHeaders(403, -1) }
            createContext("/sub.srt") { ex ->
                val b = srt.toByteArray(StandardCharsets.UTF_8)
                ex.sendResponseHeaders(200, b.size.toLong())
                ex.responseBody.use { it.write(b) }
            }
            start()
        }
    }

    @AfterEach
    fun stopUpstream() {
        upstream.stop(0)
    }

    private fun err(body: String) =
        Json.parseToJsonElement(body).jsonObject["error"]!!.jsonPrimitive.content

    private fun io.ktor.server.application.Application.bootProxy() {
        installApiContract(lanToken = null)
        routing { StreamProxy.install(this) }
    }

    @Test
    fun `m3u8 through the bridge is rewritten and typed`() = testApplication {
        application { bootProxy() }
        val resp = client.get(proxied("/master.m3u8"))
        assertEquals(HttpStatusCode.OK, resp.status)
        assertTrue(resp.contentType()?.toString()?.contains("mpegurl") == true, "content type preserved, got ${resp.contentType()}")

        val text = resp.bodyAsText()
        assertTrue("#EXT-X-VERSION:6" in text, "comment lines survive")
        // every resolvable reference became a proxy ref; unrewritable leftovers must be comments only
        val plain = text.lines().filter { it.isNotBlank() && !it.startsWith("#") }
        assertTrue(plain.isNotEmpty())
        assertTrue(plain.all { it.startsWith("/api/stream?url=") }, "plain refs proxied, got: $plain")

        // request-id plumbing on every response
        val reqId = resp.headers["X-Req-Id"]
        assertNotNull(reqId, "no X-Req-Id header")
        assertTrue(Regex("^[0-9a-f]{8}$").matches(reqId!!), "reqId format: $reqId")
    }

    @Test
    fun `binary passthrough replays Range and copies metadata headers`() = testApplication {
        application { bootProxy() }
        val resp = client.get(proxied("/file.bin")) { header(HttpHeaders.Range, "bytes=5-9") }
        assertEquals(HttpStatusCode.OK, resp.status)
        assertEquals("bytes=5-9", lastRange.get(), "Range must be forwarded upstream")
        assertEquals(String(filler, StandardCharsets.UTF_8), resp.bodyAsText(), "body streamed whole")
        assertEquals("bytes 5-9/${filler.size}", resp.headers["Content-Range"], "Content-Range copied back")
        assertEquals("1536", resp.headers[HttpHeaders.ContentLength], "Content-Length copied")
        assertEquals("bytes", resp.headers[HttpHeaders.AcceptRanges], "Accept-Ranges advertised")
        assertTrue(resp.contentType()?.toString()?.contains("application/octet-stream") == true)
    }

    @Test
    fun `upstream failure is a typed body with request id`() = testApplication {
        application { bootProxy() }
        val resp = client.get(proxied("/denied"))
        assertEquals(HttpStatusCode.BadGateway, resp.status)
        assertEquals("PROVIDER_ERROR", err(resp.bodyAsText()))
        assertNotNull(resp.headers["X-Req-Id"])
    }

    @Test
    fun `subtitle endpoint converts srt to webvtt`() = testApplication {
        application { bootProxy() }
        val resp = client.get("/api/subtitle?url=" + URLEncoder.encode(up("/sub.srt"), StandardCharsets.UTF_8))
        assertEquals(HttpStatusCode.OK, resp.status)
        assertTrue(resp.contentType()?.toString()?.startsWith("text/vtt") == true)
        val body = resp.bodyAsText()
        assertTrue(body.startsWith("WEBVTT"))
        assertTrue("00:00:01.000 --> 00:00:03.500" in body)
    }

    @Test
    fun `missing url parameter is a 400 recoil`() = testApplication {
        application { bootProxy() }
        val resp = client.get("/api/stream")
        assertEquals(HttpStatusCode.BadRequest, resp.status)
        assertEquals("REPO_INVALID", err(resp.bodyAsText()))
    }
}
