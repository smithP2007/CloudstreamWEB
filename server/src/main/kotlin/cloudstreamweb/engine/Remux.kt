package cloudstreamweb.engine

import cloudstreamweb.RuntimeConfig
import cloudstreamweb.errors.ErrorCodes
import cloudstreamweb.errors.ProviderException
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondOutputStream
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.TimeUnit

/**
 * M3 audio remux + source probing.
 *
 * The truth requirement: probing and remuxing MUST see exactly what a browser
 * would see. Both therefore go THROUGH the engine's own /api/stream proxy
 * (self-connection, same header replay / Range semantics), never straight at
 * the hoster with hand-rolled headers.
 *
 * - `probeAudio` runs ffprobe over an mkv/webm file link and reports the audio
 *   streams (language/title/channels) — cached per `linkId` in
 *   `cache/probe/<id>.json` (TTL 6h; links are usually signed and ephemeral,
 *   so a new extraction naturally produces a new key).
 * - `buildRemuxCommand` Produces the ffmpeg argv for a streamable remux:
 *   video+one audio stream, `-c copy` (no transcode — fast, lossless), out as
 *   fragmented MP4 on stdout so Chromium's <video> plays it natively. The
 *   10-bit/codec-level cases a copy cannot fix belong to M5 live transcode.
 */
object Remux {

    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    data class AudioTrackDto(
        val ordinal: Int,          // 0-based audio-stream ordinal (also the ?audio= param)
        val streamIndex: Int,      // absolute file stream index (informational)
        val lang: String? = null,  // ISO-639 code as muxed (hin/eng/kor/und...)
        val title: String? = null, // track title metadata, when present
        val codec: String? = null, // aac/ac3/eac3/dts/...
        val channels: Int? = null,
        val isDefault: Boolean = false,
    )

    // ---- probing -------------------------------------------------------------

    private val probeDir: Path
        get() = RuntimeConfig.dataDir.resolve("cache").resolve("probe")

    private const val PROBE_TTL_MS = 6L * 60 * 60 * 1000

    /** Per-link decode truth for badges/ranking (M4): video codec-face + audio layout. */
    @Serializable
    data class VideoProbeDto(
        val codec: String? = null,     // h264/hevc/vp9/av1/mpeg4/...
        val profile: String? = null,   // High / Main / Baseline / ...
        val width: Int? = null,
        val height: Int? = null,
        val bitDepth: Int? = null,     // 8/10/12 (parsed from pix_fmt)
        val hdr: Boolean = false,      // PQ (HDR10) / HLG / bt2020 primaries
        val fps: Double? = null,
    )

    @Serializable
    data class LinkProbeDto(
        val container: String? = null,   // coarse: mkv/webm/mp4/mpegts/...
        val formatName: String? = null,  // raw ffprobe format_name
        val durationSec: Double? = null,
        val bitRate: Long? = null,       // container bitrate (MediaCapabilities smooth estimate)
        val video: VideoProbeDto? = null,
        val audio: List<AudioTrackDto> = emptyList(),
        val subtitlesCount: Int? = null,
        val streamed: Boolean = false,   // HLS or otherwise no direct file probed
    )

    /**
     * Audio streams of a FILE link, probed through the engine proxy and cached
     * by linkId. Never throws for "bad content" — returns emptyList and lets
     * the caller decide; missing TOOLING is the caller's responsibility to
     * check first (FFMPEG_MISSING).
     */
    fun probeAudio(rawUrl: String, headers: Map<String, String>, linkId: String): List<AudioTrackDto> {
        val tools = Capabilities.resolveTools()
        val ffprobe = tools.ffprobe ?: throw ProviderException(
            cloudstreamweb.errors.ErrorCodes.FFMPEG_MISSING,
            "ffprobe is not installed (run scripts/bootstrap.sh or set CS_WEB_FFMPEG)",
        )

        val cache = probeDir.resolve("$linkId.json")
        if (Files.isRegularFile(cache) &&
            System.currentTimeMillis() - Files.getLastModifiedTime(cache).toMillis() < PROBE_TTL_MS
        ) {
            return runCatching { parseProbeJson(Files.readString(cache)) }.getOrElse { emptyList() }
        }

        Files.createDirectories(probeDir)
        val stdout = runProbe(
            ffprobe,
            listOf(
                "-v", "error",
                "-print_format", "json",
                "-show_streams",
                "-select_streams", "a",
                selfProxyInput(rawUrl, headers),
            ),
        ) ?: throw ProviderException(
            cloudstreamweb.errors.ErrorCodes.PROBE_TIMEOUT,
            "ffprobe failed for ${rawUrl.take(120)}",
        )
        // only cache probes we could parse into tracks; an empty parse (no
        // audio streams) is a legitimate file shape, cache it too
        return parseProbeJson(stdout).also {
            runCatching { Files.writeString(cache, stdout) }
        }
    }

    /** Cache-first read for the route; falls back to a live probe when stale/missing. */
    fun cachedTracks(rawUrl: String, headers: Map<String, String>, linkId: String): List<AudioTrackDto> =
        probeAudio(rawUrl, headers, linkId)

    private fun audioTrackOf(s: kotlinx.serialization.json.JsonObject, ordinal: Int): AudioTrackDto? {
        val streamIndex = s["index"]?.jsonPrimitive?.content?.toIntOrNull() ?: return null
        val tags = s["tags"]?.jsonObject
        val disp = s["disposition"]?.jsonObject
        return AudioTrackDto(
            ordinal = ordinal,
            streamIndex = streamIndex,
            lang = tags?.get("language")?.jsonPrimitive?.content,
            title = tags?.get("title")?.jsonPrimitive?.content,
            codec = s["codec_name"]?.jsonPrimitive?.content,
            channels = s["channels"]?.jsonPrimitive?.content?.toIntOrNull(),
            isDefault = disp?.get("default")?.jsonPrimitive?.content == "1",
        )
    }

    fun parseProbeJson(stdout: String): List<AudioTrackDto> {
        val streams = json.parseToJsonElement(stdout).jsonObject["streams"]?.jsonArray ?: return emptyList()
        return streams.mapIndexedNotNull { i, el -> audioTrackOf(el.jsonObject, i) }
    }

    // ---- M4 full-link probe ------------------------------------------------------

    /**
     * Everything a decode decision needs about a FILE link: container, the
     * first video stream's codec-face, audio layout, subtitle count, duration.
     * Same through-proxy truth as [probeAudio] (`raw=1` self-connection), cached
     * per linkId in `<linkId>.probe.json` (TTL 6h). HLS links short-circuit to
     * `streamed=true` — segment probing costs more latency than its badge is
     * worth (m3u8 candidates are rarely the file-remux path anyway).
     */
    fun probeLink(rawUrl: String, headers: Map<String, String>, linkId: String): LinkProbeDto {
        if (rawUrl.substringBefore('?').lowercase().endsWith(".m3u8")) {
            return LinkProbeDto(streamed = true)
        }

        val tools = Capabilities.resolveTools()
        val ffprobe = tools.ffprobe ?: throw ProviderException(
            cloudstreamweb.errors.ErrorCodes.FFMPEG_MISSING,
            "ffprobe is not installed (run scripts/bootstrap.sh or set CS_WEB_FFMPEG)",
        )

        val cache = probeDir.resolve("$linkId.probe.json")
        if (Files.isRegularFile(cache) &&
            System.currentTimeMillis() - Files.getLastModifiedTime(cache).toMillis() < PROBE_TTL_MS
        ) {
            runCatching { return parseLinkProbeJson(Files.readString(cache)) }
            // unreadable cache: fall through, probe live, overwrite below
        }

        Files.createDirectories(probeDir)
        val stdout = runProbe(
            ffprobe,
            listOf(
                "-v", "error",
                "-print_format", "json",
                "-show_streams",
                "-show_format",
                selfProxyInput(rawUrl, headers),
            ),
            // badges must not wait minutes for a stuck hoster (audio probes —
            // which gate the default remux — keep the full 300 s budget)
            timeoutSeconds = 45,
        ) ?: throw ProviderException(
            cloudstreamweb.errors.ErrorCodes.PROBE_TIMEOUT,
            "ffprobe failed for ${rawUrl.take(120)}",
        )
        return parseLinkProbeJson(stdout).also {
            runCatching { Files.writeString(cache, stdout) }
        }
    }

    /** Full-probe parse: video face + audio + subtitles + format. Tolerant by design. */
    fun parseLinkProbeJson(stdout: String): LinkProbeDto {
        val root = runCatching { json.parseToJsonElement(stdout).jsonObject }.getOrNull()
            ?: return LinkProbeDto(streamed = true)
        val format = root["format"]?.jsonObject
        val streams = root["streams"]?.jsonArray ?: return LinkProbeDto(streamed = true)

        var video: VideoProbeDto? = null
        val audio = mutableListOf<AudioTrackDto>()
        var subtitles = 0
        for (el in streams) {
            val s = runCatching { el.jsonObject }.getOrNull() ?: continue
            when (s["codec_type"]?.jsonPrimitive?.content) {
                "video" -> if (video == null) video = videoTrackOf(s)
                "audio" -> audioTrackOf(s, audio.size)?.let { audio.add(it) }
                "subtitle" -> subtitles++
            }
        }
        val rawFormatName = format?.get("format_name")?.jsonPrimitive?.content
        return LinkProbeDto(
            container = containerOf(rawFormatName),
            formatName = rawFormatName,
            durationSec = format?.get("duration")?.jsonPrimitive?.content?.toDoubleOrNull(),
            bitRate = format?.get("bit_rate")?.jsonPrimitive?.content?.toLongOrNull(),
            video = video,
            audio = audio,
            subtitlesCount = subtitles.takeIf { it > 0 },
        )
    }

    private fun videoTrackOf(s: kotlinx.serialization.json.JsonObject): VideoProbeDto? {
        val codec = s["codec_name"]?.jsonPrimitive?.content ?: return null
        val pixFmt = s["pix_fmt"]?.jsonPrimitive?.content
        val transfer = s["color_transfer"]?.jsonPrimitive?.content
        val primaries = s["color_primaries"]?.jsonPrimitive?.content
        return VideoProbeDto(
            codec = codec,
            profile = s["profile"]?.jsonPrimitive?.content,
            width = s["width"]?.jsonPrimitive?.content?.toIntOrNull(),
            height = s["height"]?.jsonPrimitive?.content?.toIntOrNull(),
            bitDepth = when {
                pixFmt == null -> null
                pixFmt.contains("12") -> 12
                pixFmt.contains("10") -> 10
                pixFmt.contains("16") -> 16
                else -> 8
            },
            hdr = transfer == "smpte2084" ||
                transfer == "arib-std-b67" ||
                primaries?.startsWith("bt2020") == true,
            fps = s["avg_frame_rate"]?.jsonPrimitive?.content?.let(::rational),
        )
    }

    /** "24000/1001" -> 23.976…, "30/1" -> 30.0, plain "30" -> 30.0. */
    private fun rational(raw: String): Double? {
        val parts = raw.split('/')
        if (parts.size == 2) {
            val num = parts[0].toDoubleOrNull() ?: return null
            val den = parts[1].toDoubleOrNull() ?: return null
            return if (den != 0.0) num / den else null
        }
        return raw.toDoubleOrNull()
    }

    /** Coarse container for badges: ffprobe's comma list -> one family name. */
    private fun containerOf(formatName: String?): String? {
        val first = formatName?.split(',')?.firstOrNull()?.trim()
            .takeUnless { it.isNullOrEmpty() } ?: return null
        return when (first) {
            "matroska" -> "mkv"
            "mov" -> "mp4" // mov,mp4,... family — browsers treat them alike
            else -> first
        }
    }

    // ---- ffmpeg command building ----------------------------------------------

    /**
     * Remux argv: input through the engine proxy (header replay + Range are
     * browser-identical), fast-seek then -c copy into fragmented MP4 on stdout.
     */
    fun buildRemuxCommand(
        proxyInputUrl: String,
        audioOrdinal: Int,
        startSeconds: Double? = null,
    ): List<String> {
        val tools = Capabilities.resolveTools()
        val ffmpeg = tools.ffmpeg ?: throw ProviderException(
            cloudstreamweb.errors.ErrorCodes.FFMPEG_MISSING,
            "ffmpeg is not installed (run scripts/bootstrap.sh or set CS_WEB_FFMPEG)",
        )
        return buildList {
            add(ffmpeg.toString())
            add("-nostdin"); add("-hide_banner"); add("-loglevel"); add("error")
            if (startSeconds != null && startSeconds > 0.5) {
                add("-ss"); add(formatSeconds(startSeconds))
            }
            add("-i"); add(proxyInputUrl)
            add("-map"); add("0:v:0")
            add("-map"); add("0:a:$audioOrdinal")
            add("-c"); add("copy")
            add("-f"); add("mp4")
            add("-movflags"); add("empty_moov+frag_keyframe+default_base_moof")
            add("-max_muxing_queue_size"); add("2048")
            add("pipe:1")
        }
    }

    private fun formatSeconds(s: Double): String = String.format(java.util.Locale.ROOT, "%.2f", s)

    /**
     * Default audio for `?audio=`-less remux: the mux's own default track,
     * falling back to the first audio stream.
     */
    fun defaultAudioOrdinal(tracks: List<AudioTrackDto>): Int =
        tracks.firstOrNull { it.isDefault }?.ordinal
            ?: tracks.firstOrNull()?.ordinal
            ?: 0

    // ---- plumbing --------------------------------------------------------------

    /**
     * Input URL for ffmpeg/ffprobe: our own /api/stream bridge on loopback, so
     * every request ffmpeg makes gets the same header replay/Range treatment a
     * browser gets (probe truth == playback truth).
     */
    fun selfProxyInput(rawUrl: String, headers: Map<String, String>): String {
        val host = if (RuntimeConfig.host == "0.0.0.0" || RuntimeConfig.host.isBlank()) "127.0.0.1" else RuntimeConfig.host
        val base = selfBaseUrlOverride ?: "http://$host:${RuntimeConfig.port}"
        val b64 = PlaybackEngine.headersEncoded(headers)
        val auth = RuntimeConfig.lanToken?.let { "&token=" + java.net.URLEncoder.encode(it, "UTF-8") } ?: ""
        // raw=1: the self-connection must receive the ORIGINAL container bytes —
        // otherwise the stream route would remux our remux (nested ffmpeg, and
        // ffprobe would read a remux that needs another remux).
        return "$base${PlaybackEngine.proxied(rawUrl, b64)}&raw=1$auth"
    }

    /** Tests boot real servers on ephemeral ports; production never sets this. */
    @Volatile
    var selfBaseUrlOverride: String? = null

    private fun runProbe(bin: Path, args: List<String>, timeoutSeconds: Long = 300): String? = runCatching {
        val proc = ProcessBuilder(listOf(bin.toString()) + args)
            .redirectErrorStream(false)
            .start()
        // stdout/stderr are drained on daemon workers — a process that never
        // closes its pipes must not pin a dispatcher thread (this happened:
        // ffprobe reading a wedged hoster kept readText() alive forever and
        // starved request handling); the timeout below kills it regardless.
        val outRef = java.util.concurrent.atomic.AtomicReference<ByteArray>()
        val errRef = java.util.concurrent.atomic.AtomicReference<ByteArray>()
        val outThread = Thread {
            runCatching { outRef.set(proc.inputStream.use { it.readBytes() }) }
        }.apply { isDaemon = true; start() }
        val errThread = Thread {
            runCatching { errRef.set(proc.errorStream.use { it.readBytes() }) }
        }.apply { isDaemon = true; start() }

        val ok = proc.waitFor(timeoutSeconds, TimeUnit.SECONDS) && proc.exitValue() == 0
        if (!ok) {
            proc.destroyForcibly()
            System.err.println("[remux-probe] no exit within ${timeoutSeconds}s — killed")
            outThread.join(2_000)
            errThread.join(2_000)
            return@runCatching null
        }
        // normal exit: the pipes hit EOF and the readers finish quickly
        outThread.join(60_000)
        errThread.join(1_000)
        val err = errRef.get()?.decodeToString().orEmpty()
        if (err.isNotBlank()) System.err.println("[remux-probe] exit=0 warn=${err.take(300)}")
        (outRef.get() ?: ByteArray(0)).decodeToString().ifBlank { null }
    }.getOrNull()

    /** Human dump for offline re-probing (also used by local scripts). */
    fun dumpProbeCache(): List<File> =
        probeDir.toFile().listFiles()?.sortedBy { it.name } ?: emptyList()
}

/**
 * Spawn ffmpeg per [command] (built by [Remux.buildRemuxCommand]) and stream
 * its stdout into this call as fragmented MP4. The process is destroyed on
 * client abort and its stderr is mirrored to the server log under [logTag].
 * (Top-level extension for the same reason as engine.passthrough.)
 */
suspend fun ApplicationCall.respondRemux(
    command: List<String>,
    logTag: String,
    logFile: Path? = null,
) {
    val builder = ProcessBuilder(command).redirectErrorStream(false)
    val proc = builder.start()
    System.err.println("[$logTag] spawn ok pid=${proc.pid()} argv=${command.take(8)}")
    val errThread = Thread {
        runCatching {
            proc.errorStream.bufferedReader().forEachLine { line ->
                System.err.println("[$logTag] $line")
                logFile?.let { runCatching { Files.writeString(it, "$line\n", java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND) } }
            }
        }
    }.apply { isDaemon = true; start() }
    try {
        respondOutputStream(
            ContentType.parse("video/mp4"),
            HttpStatusCode.OK,
            null, // fragmented mp4 has no meaningful total length
        ) {
            val out = this
            try {
                proc.inputStream.use { input ->
                    var first = true
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (first && n >= 0) {
                            System.err.println("[$logTag] first byte from ffmpeg: n=$n t=${System.currentTimeMillis() / 1000}")
                        }
                        first = false
                        if (n < 0) break
                        out.write(buf, 0, n)
                    }
                    out.flush()
                }
                System.err.println("[$logTag] pump done exit=${runCatching { proc.waitFor(5, TimeUnit.SECONDS); if (proc.isAlive) -1 else proc.exitValue() }}")
            } catch (t: Throwable) {
                System.err.println("[$logTag] pump aborted t=${t.javaClass.simpleName} msg=${t.message}")
                throw t
            } finally {
                runCatching { proc.destroy() }
            }
        }
    } catch (t: Throwable) {
        runCatching { proc.destroyForcibly() }
        if (PlaybackEngine.isClientAbort(t)) {
            System.err.println("[$logTag] client disconnected — remux aborted")
        } else {
            throw t
        }
    } finally {
        // let the stderr mirror drain the tail (usually -loglevel error lines)
        runCatching { errThread.join(1000) }
    }
}
