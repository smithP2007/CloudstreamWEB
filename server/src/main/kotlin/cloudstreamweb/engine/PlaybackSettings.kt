package cloudstreamweb.engine

import cloudstreamweb.errors.ProviderException
import cloudstreamweb.persistence.Db
import kotlinx.serialization.Serializable

/**
 * M2 playback settings: the user-facing toggles of the playback platform.
 * Persisted in the existing `prefs` table under a `settings.*` key namespace
 * (plain scalar values — "true"/"false"/"auto" — no new schema, and the keys
 * never collide with plugin SharedPreferences because plugins never read them).
 *
 * Every new playback feature MUST be switchable here; features read these at
 * request time so a toggle hits without a restart.
 */
object PlaybackSettings {

    private const val PREFIX = "settings."

    @Serializable
    data class SettingsDto(
        val audioRemux: Boolean = true,          // M3: serve muxed files language-switched (mkv->mp4)
        val probeSources: Boolean = true,        // M4: ffprobe + MediaCapabilities truth pass
        val bufferWatchdog: Boolean = true,      // M4: mid-play stall monitor (one reload, then next source)
        val liveTranscode: Boolean = false,      // M5: CPU/GPU transcode for undecodable streams
        val gpuAccel: String = "auto",           // M5: auto|on|off
        val convertAfterDownload: Boolean = false, // M5: transcode-once cache after a download
    )

    /** Partial update; only provided fields change. */
    @Serializable
    data class SettingsPatch(
        val audioRemux: Boolean? = null,
        val probeSources: Boolean? = null,
        val bufferWatchdog: Boolean? = null,
        val liveTranscode: Boolean? = null,
        val gpuAccel: String? = null,
        val convertAfterDownload: Boolean? = null,
    )

    val GPU_MODES = setOf("auto", "on", "off")

    /** Live view; missing/garbage keys fall back to defaults per-field. */
    fun current(): SettingsDto {
        val prefs = Db.loadPrefs().filterKeys { it.startsWith(PREFIX) }
        fun flag(key: String, default: Boolean): Boolean = when (prefs[PREFIX + key]) {
            "true" -> true
            "false" -> false
            else -> default
        }
        val gpu = prefs[PREFIX + "gpu_accel"]?.takeIf { it in GPU_MODES } ?: "auto"
        return SettingsDto(
            audioRemux = flag("audio_remux", true),
            probeSources = flag("probe_sources", true),
            bufferWatchdog = flag("buffer_watchdog", true),
            liveTranscode = flag("live_transcode", false),
            gpuAccel = gpu,
            convertAfterDownload = flag("convert_after_download", false),
        )
    }

    fun update(patch: SettingsPatch): SettingsDto {
        if (patch.gpuAccel != null && patch.gpuAccel !in GPU_MODES) {
            throw ProviderException("REPO_INVALID", "gpuAccel must be one of $GPU_MODES")
        }
        if (patch.audioRemux != null) Db.savePref(PREFIX + "audio_remux", patch.audioRemux.toString())
        if (patch.probeSources != null) Db.savePref(PREFIX + "probe_sources", patch.probeSources.toString())
        if (patch.bufferWatchdog != null) Db.savePref(PREFIX + "buffer_watchdog", patch.bufferWatchdog.toString())
        if (patch.liveTranscode != null) Db.savePref(PREFIX + "live_transcode", patch.liveTranscode.toString())
        if (patch.gpuAccel != null) Db.savePref(PREFIX + "gpu_accel", patch.gpuAccel)
        if (patch.convertAfterDownload != null) {
            Db.savePref(PREFIX + "convert_after_download", patch.convertAfterDownload.toString())
        }
        return current()
    }

    /** Test/dev helper: restore factory defaults (also what a fresh install reads). */
    fun resetToDefaults(): SettingsDto = update(
        SettingsPatch(
            audioRemux = true,
            probeSources = true,
            bufferWatchdog = true,
            liveTranscode = false,
            gpuAccel = "auto",
            convertAfterDownload = false,
        ),
    )
}
