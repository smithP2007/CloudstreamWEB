package cloudstreamweb.engine

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** M1: linkId is the cache-key namespace for probe/remux/ffprobe caches. */
class LinkIdTest {

    @Test
    fun `same url and headers always map to the same id`() {
        val a = PlaybackEngine.linkId("https://cdn.host/x.mkv", mapOf("Referer" to "https://site.example/"))
        val b = PlaybackEngine.linkId("https://cdn.host/x.mkv", mapOf("Referer" to "https://site.example/"))
        assertEquals(a, b)
    }

    @Test
    fun `header order does not matter`() {
        val a = PlaybackEngine.linkId("https://cdn.host/x.mkv", linkedMapOf("Referer" to "r", "User-Agent" to "u"))
        val b = PlaybackEngine.linkId("https://cdn.host/x.mkv", linkedMapOf("User-Agent" to "u", "Referer" to "r"))
        assertEquals(a, b)
    }

    @Test
    fun `different urls or headers map to different ids`() {
        val base = PlaybackEngine.linkId("https://cdn.host/x.mkv")
        assertNotEquals(base, PlaybackEngine.linkId("https://cdn.host/y.mkv"))
        assertNotEquals(base, PlaybackEngine.linkId("https://cdn.host/x.mkv", mapOf("Referer" to "https://site.example/")))
        // one different header pair = another id
        assertNotEquals(
            PlaybackEngine.linkId("https://cdn.host/x.mkv", mapOf("Referer" to "https://site.example/")),
            PlaybackEngine.linkId("https://cdn.host/x.mkv", mapOf("Referer" to "https://other.example/")),
        )
    }

    @Test
    fun `ids are short fixed-length hex`() {
        val id = PlaybackEngine.linkId("https://cdn.host/a?b=c")
        assertEquals(16, id.length)
        assertTrue(id.all { it in "0123456789abcdef" })
    }
}
