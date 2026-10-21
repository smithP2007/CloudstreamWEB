package cloudstreamweb.routes

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** SRT → WebVTT conversion the /api/subtitle bridge performs. */
class VttConversionTest {

    @Test
    fun `srt gets webvtt header and dot timestamps`() {
        val srt = """
            1
            00:00:01,000 --> 00:00:03,500
            Hello <b>world</b>

            2
            00:01:42,123 --> 00:01:45,000
            Second line
        """.trimIndent()
        val vtt = StreamProxy.toVtt(srt)
        assertTrue(vtt.startsWith("WEBVTT"), "missing WEBVTT header: $vtt")
        assertTrue("00:00:01.000 --> 00:00:03.500" in vtt)
        assertTrue("00:01:42.123 --> 00:01:45.000" in vtt)
        assertFalse(vtt.lines().any { it.contains("-->") && it.contains(",") }, "stray SRT commas left")
    }

    @Test
    fun `webvtt input passes through`() {
        val vtt = "WEBVTT\n\nintro\n00:00.500 --> 00:01.000\nhi"
        val out = StreamProxy.toVtt(vtt)
        assertTrue(out.startsWith("WEBVTT"))
        assertTrue("00:00.500 --> 00:01.000" in out, "existing vtt timestamps must not be altered")
    }

    @Test
    fun `bom is stripped`() {
        val srt = "\uFEFF1\n00:00:02,000 --> 00:00:04,000\nx"
        val vtt = StreamProxy.toVtt(srt)
        assertTrue(vtt.startsWith("WEBVTT"))
        assertTrue(vtt.contains("00:00:02.000"))
    }
}
