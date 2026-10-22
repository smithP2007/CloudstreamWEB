package cloudstreamweb.engine

import cloudstreamweb.RuntimeConfig
import kotlinx.serialization.Serializable
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * M2 capability detection: what the HOST can contribute to playback.
 *
 * - ffmpeg/ffprobe resolution precedence: `CS_WEB_FFMPEG` env (a binary or a
 *   directory) -> `tools/ffmpeg/bin/` next to the repo (see scripts/bootstrap.sh,
 *   gitignored) -> the same dir under the data dir -> PATH. Absent binaries
 *   simply degrade the capabilities DTO (and the SPA grays out the dependent
 *   toggles); nothing here throws for a missing capability.
 * - GPU accelerators: VAAPI via /dev/dri render nodes, NVDEC/NVENC via
 *   nvidia-smi. Detection is best-effort — M5 uses the list to pick hwaccel
 *   arguments and fails over to CPU when honor starts lying.
 *
 * Re-probing on every call is deliberate: the endpoint is cold-path (SPA
 * settings page), spawning `-version` costs a few ms, and this can never go
 * stale after a bootstrap run without a server restart.
 */
object Capabilities {

    /** ffmpeg + ffprobe as resolved on disk; both optional independently. */
    data class FfmpegTools(val ffmpeg: Path?, val ffprobe: Path?)

    @Serializable
    data class CapabilitiesDto(
        val ffmpegAvailable: Boolean = false,
        val ffmpegVersion: String? = null,
        val ffmpegPath: String? = null,
        val ffprobeAvailable: Boolean = false,
        val ffprobeVersion: String? = null,
        val ffprobePath: String? = null,
        val gpuAccelerators: List<String> = emptyList(),
    ) {
        // conveniences for the SPA (constructor props so they serialize)
        val audioRemuxAvailable: Boolean = ffmpegAvailable && ffprobeAvailable
        val liveTranscodeAvailable: Boolean = ffmpegAvailable
        val decodeProbeAvailable: Boolean = ffprobeAvailable
    }

    fun probe(): CapabilitiesDto {
        val tools = resolveTools()
        val gpu = gpuAccelerators()
        return CapabilitiesDto(
            ffmpegAvailable = tools.ffmpeg != null,
            ffmpegVersion = tools.ffmpeg?.let { versionOf(it) },
            ffmpegPath = tools.ffmpeg?.toString(),
            ffprobeAvailable = tools.ffprobe != null,
            ffprobeVersion = tools.ffprobe?.let { versionOf(it) },
            ffprobePath = tools.ffprobe?.toString(),
            gpuAccelerators = gpu,
        )
    }

    // ---- binary resolution ---------------------------------------------------

    fun resolveTools(): FfmpegTools {
        val dirs = buildList {
            System.getenv("CS_WEB_FFMPEG")?.takeIf { it.isNotBlank() }?.let { env ->
                val p = Path.of(env)
                add(if (Files.isRegularFile(p)) p.parent else p)
            }
            add(Path.of("tools/ffmpeg/bin"))
            add(Path.of("../tools/ffmpeg/bin"))
            add(RuntimeConfig.dataDir.resolve("tools/ffmpeg/bin"))
        }
        for (dir in dirs) {
            if (isExecutable(dir.resolve("ffmpeg"))) {
                val probe = dir.resolve("ffprobe")
                return FfmpegTools(dir.resolve("ffmpeg"), if (isExecutable(probe)) probe else whichOnPath("ffprobe"))
            }
        }
        return FfmpegTools(whichOnPath("ffmpeg"), whichOnPath("ffprobe"))
    }

    private fun isExecutable(p: Path): Boolean =
        Files.isRegularFile(p) && Files.isExecutable(p)

    private fun whichOnPath(bin: String): Path? = runCatching {
        val p = ProcessBuilder("sh", "-c", "command -v $bin").start()
        val ok = p.waitFor(5, TimeUnit.SECONDS) && p.exitValue() == 0
        if (!ok) return@runCatching null
        p.inputStream.bufferedReader().readText().trim()
            .lineSequence().firstOrNull()?.takeIf { it.isNotBlank() }
            ?.let { Path.of(it) }
    }.getOrNull()

    /** Short `-version` marker, e.g. "7.0.2-static" (first line parsed). */
    fun versionOf(bin: Path): String? = runCatching {
        val p = ProcessBuilder(bin.toString(), "-version").start()
        val ok = p.waitFor(6, TimeUnit.SECONDS) && p.exitValue() == 0
        if (!ok) return@runCatching null
        val first = p.inputStream.bufferedReader().readText()
            .lineSequence().firstOrNull() ?: return@runCatching null
        Regex("""version\s+(\S+)""").find(first)?.groupValues?.get(1) ?: first.take(60)
    }.getOrNull()

    // ---- GPU accel detection ---------------------------------------------------

    fun gpuAccelerators(): List<String> = buildList {
        runCatching {
            val dri = Path.of("/dev/dri")
            if (Files.isDirectory(dri) &&
                Files.list(dri).use { s -> s.anyMatch { it.fileName.toString().startsWith("renderD") } }
            ) add("vaapi")
        }
        runCatching {
            val p = ProcessBuilder("nvidia-smi", "-L").start()
            val ok = p.waitFor(5, TimeUnit.SECONDS) && p.exitValue() == 0
            val out = p.inputStream.bufferedReader().readText()
            if (ok && out.contains("GPU")) {
                add("nvdec")
                add("nvenc")
            }
        }
    }
}
