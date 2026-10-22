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

    // ---- M4 full-probe parsing -------------------------------------------------

    private val cannedLinkProbe = """
        {
          "streams": [
            {"index": 0, "codec_type": "video", "codec_name": "h264", "profile": "High",
             "width": 1920, "height": 1080, "pix_fmt": "yuv420p10le",
             "color_transfer": "smpte2084", "avg_frame_rate": "24000/1001"},
            {"index": 1, "codec_type": "audio", "codec_name": "aac", "channels": 2,
             "disposition": {"default": 1}, "tags": {"language": "hin"}},
            {"index": 2, "codec_type": "audio", "codec_name": "eac3", "channels": 6,
             "disposition": {"default": 0}, "tags": {"language": "eng"}},
            {"index": 3, "codec_type": "subtitle", "codec_name": "subrip",
             "disposition": {"default": 0}, "tags": {"language": "eng"}},
            {"index": 4, "codec_type": "attachment"}
          ],
          "format": {"format_name": "matroska,webm", "duration": "7092.052000"}
        }
    """.trimIndent()

    @Test
    fun `link probe parses video face, audio, subtitles, container and duration`() {
        val p = Remux.parseLinkProbeJson(cannedLinkProbe)
        assertEquals("mkv", p.container, "matroska family must read as mkv")
        assertEquals("matroska,webm", p.formatName)
        assertEquals(7092.052, p.durationSec ?: 0.0, 0.001)
        assertFalse(p.streamed)
        val v = p.video!!
        assertEquals("h264", v.codec)
        assertEquals("High", v.profile)
        assertEquals(1920, v.width)
        assertEquals(1080, v.height)
        assertEquals(10, v.bitDepth, "yuv420p10le must read as 10-bit")
        assertTrue(v.hdr, "smpte2084 (PQ) transfer must read as HDR")
        assertEquals(23.976, v.fps ?: 0.0, 0.01)
        // audio enumerates in stream order with streamIndex truth preserved
        assertEquals(2, p.audio.size)
        assertEquals("hin", p.audio[0].lang)
        assertTrue(p.audio[0].isDefault)
        assertEquals(1, p.audio[0].streamIndex)
        assertEquals("eng", p.audio[1].lang)
        assertFalse(p.audio[1].isDefault)
        assertEquals(1, p.subtitlesCount, "one subtitle stream in the canned data")
    }

    @Test
    fun `link probe maps mp4-family container and 8-bit SDR as plain`() {
        val sdr = """
            {"streams": [{"index":0, "codec_type":"video", "codec_name":"h264",
              "profile":"High", "width":1280, "height":720, "pix_fmt":"yuv420p",
              "avg_frame_rate":"30/1"}],
             "format": {"format_name":"mov,mp4,m4a,3gp,3g2,mj2", "duration":"120.0"}}
        """.trimIndent()
        val p = Remux.parseLinkProbeJson(sdr)
        assertEquals("mp4", p.container)
        assertEquals(8, p.video!!.bitDepth)
        assertFalse(p.video!!.hdr, "plain yuv420p with no transfer tag is SDR")
        assertEquals(30.0, p.video!!.fps ?: 0.0, 0.01)
        assertEquals(120.0, p.durationSec ?: 0.0, 0.01)
        assertEquals(0, p.audio.size)
        assertEquals(null, p.subtitlesCount)
    }

    @Test
    fun `link probe of garbage or empty json degrades to streamed`() {
        assertTrue(Remux.parseLinkProbeJson("not json at all").streamed)
        assertTrue(Remux.parseLinkProbeJson("{}").streamed)
        assertFalse(Remux.parseLinkProbeJson(cannedLinkProbe).streamed)
    }

    @Test
    fun `m3u8 links short-circuit to streamed without any spawn`() {
        val p = Remux.probeLink("https://host.example/live/index.m3u8?token=1", emptyMap(), "pure-m3u8-id")
        assertTrue(p.streamed)
        assertEquals(null, p.video)
        assertEquals(0, p.audio.size)
    }
}
