package cloudstreamweb.routes

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Random
import cloudstreamweb.engine.PlaybackEngine

/**
 * v2 M0: the m3u8 playlist rewriter is pure text logic — property-test it
 * against hostile inputs instead of trusting hand-picked samples. Fixtures +
 * invariants:
 *
 *  1. line count is preserved,
 *  2. blank lines pass through byte-identical,
 *  3. a plain segment line either stays as-is (unresolvable: data:,javascript:,
 *     URI garbage) or becomes exactly `/api/stream?url=<ENC>` whose decoded
 *     form is the resolved absolute URL the proxy will replay,
 *  4. an `URI="..."` attribute inside a comment line is swapped in-place;
 *     everything OUTSIDE the attribute echoes byte-identically,
 *  5. the url parameter is a stable round-trip of Kotlin's URLEncoder
 *     (`re-encode(decode(param)) == param`) so re-proxying never drifts.
 *
 * Random seed is fixed so a discovered failure is reproducible forever.
 */
class PlaylistRewriteFuzzTest {

    private val uriAttr = Regex("URI=\"[^\"]*\"")

    /** Same algorithm as StreamProxy.resolveUpstream — the oracle. */
    private fun replicaResolve(base: String, ref: String): String? {
        if (ref.startsWith("data:") || ref.startsWith("javascript:")) return null
        return try {
            URI(base).resolve(ref).toString()
        } catch (t: Throwable) {
            null
        }
    }

    private val bases = listOf(
        "http://cdn.example.com/dir/sub/playlist.m3u8",
        "http://cdn.example.com/p.m3u8?q=1",
        "https://up.example:8443/a/b.m3u8",
        "http://127.0.0.1:9981/pl.m3u8",
    )

    private val refs = listOf(
        "720/prog_index.m3u8?age=30",
        "http://mirror.example/x/seg.m3u8?tok=%2Ba%20b",
        "//schemeless/dir/pl.m3u8",
        "iframes/low-if.m3u8",
        "key.bin?ci=%2F..%2F..%2F",
        "a/./b/../c.m3u8?x=1+2",
        "data:text/plain,inline",
        "javascript:alert(1)",
        "no-scheme-bad?(space)",
        "",
        "#%-junk",
    )

    private val comments = listOf(
        "#EXTM3U", "#EXT-X-ENDLIST",
        "#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"aud\",NAME=\"en\",URI=\"__R__\"",
        "#EXT-X-I-FRAME-STREAM-INF:BANDWIDTH=128000,URI=\"__R__\"",
        "#EXT-X-SESSION-KEY:METHOD=AES-128,URI=\"__R__\"",
        "#EXT-X-STREAM-INF:BANDWIDTH=<n>,CODECS=\"avc1.64001f\"",
        "#URI-quote-missing=\"unclosed",
        "#EXT-X-KEY:URI=\"__R__\"",
    )

    private inner class Gen(val rnd: Random) {
        fun <T> pick(arr: List<T>): T = arr[rnd.nextInt(arr.size)]
        fun line(): String = when (rnd.nextInt(4)) {
            0 -> pick(comments).replace("__R__", pick(refs))
            1, 2 -> pick(refs)
            else -> ""
        }
    }

    @Test
    fun `fuzz - rewrite invariants hold on hostile playlists`() {
        val rnd = Random(0xC1FEE5)
        repeat(400) { iter ->
            val base = pick(bases, rnd)
            val gen = Gen(rnd)
            val text = (1..rnd.nextInt(12) + 1).joinToString("\n") { gen.line() }
            val out = PlaybackEngine.rewritePlaylist(text, base, "")
            val ins = text.lines()
            val outs = out.lines()
            assertEquals(ins.size, outs.size, "[$iter] line count must not change\ntext=$text\nout=$out")
            ins.zip(outs).forEachIndexed { idx, pair ->
                val src = pair.first
                val dst = pair.second
                val ctx = "[$iter@$idx] src=$src dst=$dst base=$base"
                if (src.isBlank()) {
                    assertEquals(src, dst, ctx)
                    return@forEachIndexed
                }
                if (src.startsWith("#")) {
                    assertTrue(dst.startsWith("#"), ctx)
                    assertEquals(stripUri(src), stripUri(dst), "comment text outside URI attrs must echo: $ctx")
                    // every attribute that survives is either the original or a proxy ref
                    uriAttr.findAll(dst).forEach { m ->
                        val inner = m.groupValues[0].removePrefix("URI=\"").removeSuffix("\"")
                        assertTrue(
                            inner.startsWith("/api/stream?url=") || src.contains("URI=\"$inner\""),
                            ctx,
                        )
                        if (inner.startsWith("/api/stream?url=")) {
                            assertRoundTrip(inner, ctx)
                        }
                    }
                    return@forEachIndexed
                }
                // plain segment/manifest line
                val resolved = replicaResolve(base, src)
                if (resolved == null) {
                    assertEquals(src, dst, "unresolvable line must pass through untouched: $ctx")
                } else {
                    val m = Regex("^/api/stream\\?url=([^&]+)$").matchEntire(dst)
                    assertNotNull(m, "resolvable line must become a proxied ref: $ctx")
                    assertEquals(
                        resolved,
                        URLDecoder.decode(m!!.groupValues[1], StandardCharsets.UTF_8),
                        "decoded param must equal the resolved url: $ctx",
                    )
                    assertRoundTrip(dst, ctx)
                }
            }
        }
    }

    private fun <T> pick(arr: List<T>, rnd: Random): T = arr[rnd.nextInt(arr.size)]

    private fun stripUri(line: String) = uriAttr.replace(line, "URI=\"·\"")

    /** 5. param is a closed round-trip of URLEncoder so no drift on re-proxying. */
    private fun assertRoundTrip(proxiedRef: String, ctx: String) {
        val param = Regex("[?&]url=([^&]+)").find(proxiedRef)!!.groupValues[1]
        assertEquals(
            param,
            URLEncoder.encode(URLDecoder.decode(param, StandardCharsets.UTF_8), StandardCharsets.UTF_8.name()),
            "encode(decode(param)) != param: $ctx",
        )
    }

    @Test
    fun `fixture - real-world shaped master playlist rewrites every reference`() {
        val text = javaClass.getResourceAsStream("/fixtures/master.m3u8")!!
            .readBytes().toString(StandardCharsets.UTF_8)
        val base = "http://cdn.example.com/master.m3u8"
        val out = PlaybackEngine.rewritePlaylist(text, base, "")

        // audio rendition (relative), absolute audio URI, i-frame, key + 2 variants
        assertEquals(6, out.split("\n").count { it.contains("/api/stream?url=") })
        assertTrue(out.contains("#EXT-X-VERSION:6"), "comment lines must survive")
        assertTrue(out.contains("RESOLUTION=1920x1080,AUDIO=\"aud\",CODECS=\"avc1.640028,mp4a.40.2\""))
        // absolute reference with token query decodes to itself + base
        val decoded = out.lines().first { it.startsWith("#EXT-X-MEDIA") && "NAME=\"English\"" in it }
            .let { Regex("URI=\"([^\"]+)\"").find(it)!!.groupValues[1] }
        assertEquals(
            "http://cdn.example.com/aud/en.m3u8?token=abc",
            URLDecoder.decode(Regex("[?&]url=([^&]+)").find(decoded)!!.groupValues[1], StandardCharsets.UTF_8),
        )
        // relative reference gains the manifest's directory
        val hindiDecoded = out.lines().first { it.startsWith("#EXT-X-MEDIA") && "NAME=\"Hindi\"" in it }
            .let { Regex("URI=\"([^\"]+)\"").find(it)!!.groupValues[1] }
        assertEquals(
            "http://cdn.example.com/audio/hi.m3u8",
            URLDecoder.decode(Regex("[?&]url=([^&]+)").find(hindiDecoded)!!.groupValues[1], StandardCharsets.UTF_8),
        )
    }
}
