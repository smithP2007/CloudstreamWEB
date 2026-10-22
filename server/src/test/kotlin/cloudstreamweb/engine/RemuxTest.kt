package cloudstreamweb.engine

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test

/**
 * M3 pure parts of the remux engine: command shape, ffprobe JSON parsing,
 * default-track choice, self-proxy input shape. Live spawn tests live in
 * RemuxIntegrationTest.
 */
class RemuxTest {

    private val cannedProbe = """
        {
          "streams": [
            {"index": 1, "codec_name": "aac", "channels": 2,
             "disposition": {"default": 1, "und": 0},
             "tags": {"language": "hin", "title": "Hindi 2.0"}},
            {"index": 2, "codec_name": "eac3", "channels": 6,
             "disposition": {"default": 0},
             "tags": {"language": "eng", "title": "English 5.1"}},
            {"index": 3, "codec_name": "dts", "channels": 6,
             "disposition": {"default": 0},
             "tags": {"language": "kor"}}
          ]
        }
    """.trimIndent()

    @Test
    fun `probe json parses languages titles and ordinals in stream order`() {
        val tracks = Remux.parseProbeJson(cannedProbe)
        assertEquals(3, tracks.size)
        // ordinals follow the enumeration order (file's audio-stream order)
        assertEquals(0, tracks[0].ordinal); assertEquals(1, tracks[0].streamIndex)
        assertEquals("hin", tracks[0].lang)
        assertEquals("Hindi 2.0", tracks[0].title)
        assertEquals("aac", tracks[0].codec)
        assertEquals(2, tracks[0].channels)
        assertTrue(tracks[0].isDefault)
        assertEquals("eng", tracks[1].lang)
        assertEquals(6, tracks[1].channels)
        assertFalse(tracks[1].isDefault)
        // missing title stays null, ordinal 2 for the korean track
        assertEquals("kor", tracks[2].lang)
        assertNullValue(tracks[2].title)
        assertEquals(2, tracks[2].ordinal)
    }

    private fun assertNullValue(x: String?) = org.junit.jupiter.api.Assertions.assertNull(x)

    @Test
    fun `default audio is the mux default, falling back to the first track`() {
        val tracks = Remux.parseProbeJson(cannedProbe)
        assertEquals(0, Remux.defaultAudioOrdinal(tracks))
        assertEquals(0, Remux.defaultAudioOrdinal(emptyList()))
        val noDefault = tracks.map { it.copy(isDefault = false) }
        assertEquals(0, Remux.defaultAudioOrdinal(noDefault))
    }

    @Test
    fun `self proxy input embeds raw=1 so ffmpeg gets original bytes`() {
        val input = Remux.selfProxyInput("https://host.example/v/x.mkv?tok=1", emptyMap())
        assertTrue(input.startsWith("http://"))
        assertTrue("/api/stream?url=" in input)
        assertTrue("&raw=1" in input, "self input must request raw passthrough: $input")
        // url param decodes back to the original
        val urlParam = Regex("[?&]url=([^&]+)").find(input)!!.groupValues[1]
        assertEquals(
            "https://host.example/v/x.mkv?tok=1",
            java.net.URLDecoder.decode(urlParam, Charsets.UTF_8),
        )
    }

    companion object {
        private lateinit var tools: Capabilities.FfmpegTools

        @BeforeAll
        @JvmStatic
        fun probe() {
            tools = Capabilities.resolveTools()
        }
    }

    @Test
    fun `remux command shape - map, copy, fragmented mp4, stdout`() {
        Assumptions.assumeTrue(tools.ffmpeg != null, "local ffmpeg required")
        val cmd = Remux.buildRemuxCommand("http://self/api/stream?url=x&raw=1", audioOrdinal = 2, startSeconds = 12.5)
        val s = cmd.map { it }
        assertTrue(s[0].endsWith("ffmpeg"), "first argv must be the resolved ffmpeg: ${s[0]}")
        assertTrue("-nostdin" in s, "ffmpeg must never block on stdin")
        val inputIdx = s.indexOf("-i")
        assertTrue(inputIdx > 0)
        // fast seek lands BEFORE the input (input seeking with -c copy)
        assertEquals("12.50", s[s.indexOf("-ss") + 1], "seek must be formatted without locale drift")
        assertTrue(s.indexOf("-ss") < inputIdx)
        // audio ordinal 2 maps as 0:a:2, video always 0:v:0
        assertEquals("0:v:0", s[s.indexOf("-map") + 1])
        assertEquals("0:a:2", s[s.indexOf("-map") + 3])
        assertTrue("-c" in s && "copy" in s)
        assertEquals("mp4", s[s.indexOf("-f") + 1])
        assertTrue(s.indexOf("empty_moov+frag_keyframe+default_base_moof") > 0)
        assertEquals("pipe:1", s.last())
    }

    @Test
    fun `remux command without start omits -ss`() {
        Assumptions.assumeTrue(tools.ffmpeg != null, "local ffmpeg required")
        val cmd = Remux.buildRemuxCommand("http://self/x", 0, null)
        assertTrue("-ss" !in cmd)
    }
}
