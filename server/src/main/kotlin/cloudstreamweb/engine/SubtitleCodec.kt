package cloudstreamweb.engine

/**
 * WebVTT <-> SRT codec for the /api/subtitle bridge (M1: extracted from what
 * used to be StreamProxy so playback mechanics and subtitle conversion have
 * separate homes).
 */
object SubtitleCodec {

    /** WEBVTT header + comma→dot timestamps; VTT input passes through untouched. */
    private val srtStampRegex = Regex("""(\d{2}):(\d{2}):(\d{2}),(\d{3})""")

    fun toVtt(raw: String): String {
        val text = raw.replace("\uFEFF", "")
        if (text.trimStart().startsWith("WEBVTT")) return text
        val converted = srtStampRegex.replace(text) { m ->
            "${m.groupValues[1]}:${m.groupValues[2]}:${m.groupValues[3]}.${m.groupValues[4]}"
        }
        return "WEBVTT\n\n" + converted.trimStart('\n', '\r')
    }
}
