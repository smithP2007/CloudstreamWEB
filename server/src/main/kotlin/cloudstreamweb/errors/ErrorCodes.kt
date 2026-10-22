package cloudstreamweb.errors

/**
 * Typed error taxonomy (v2 "Playback Platform", M0).
 *
 * Every machine-readable failure code the API can return lives here, with its
 * HTTP status. Codes flow to the SPA as `ErrorBody.error`, appear in the
 * `[api-error]` server-log line together with the request id, and each code's
 * "off"-story (feature disabled / capability missing) is covered by the
 * golden-toggle routing tests introduced with it. A code must exist here
 * BEFORE a route may return it — that is the contract this object enforces.
 */
object ErrorCodes {
    // ---- shipped in phase 1..5 ---------------------------------------------
    const val UNAUTHORIZED = "UNAUTHORIZED"
    const val REPO_INVALID = "REPO_INVALID"
    const val PROVIDER_NOT_FOUND = "PROVIDER_NOT_FOUND"
    const val ENTRY_NOT_FOUND = "ENTRY_NOT_FOUND"
    const val PROVIDER_ERROR = "PROVIDER_ERROR"
    const val INTERNAL = "INTERNAL"

    // ---- v2 playback platform (introduced M3..M5) ---------------------------
    const val UPSTREAM_403 = "UPSTREAM_403" // hoster refuses this request right now -> 502
    const val URL_EXPIRED = "URL_EXPIRED" // signed link past its TTL -> 410, SPA re-extracts
    const val PROBE_TIMEOUT = "PROBE_TIMEOUT" // candidate probe gave up -> 504
    const val CODEC_UNSUPPORTED = "CODEC_UNSUPPORTED" // stream the browser cannot decode -> 422
    const val FFMPEG_MISSING = "FFMPEG_MISSING" // remux/transcode asked for, ffmpeg absent -> 501
    const val GPU_ACCEL_UNAVAILABLE = "GPU_ACCEL_UNAVAILABLE" // hwaccel asked, no device -> 501

    /**
     * HTTP status for a code. Unknown codes fail closed to 502 so an
     * unclassified failure never masquerades as client error or 200.
     */
    fun statusFor(code: String): Int = when (code) {
        UNAUTHORIZED -> 401
        REPO_INVALID -> 400
        PROVIDER_NOT_FOUND, ENTRY_NOT_FOUND -> 404
        URL_EXPIRED -> 410
        CODEC_UNSUPPORTED -> 422
        FFMPEG_MISSING, GPU_ACCEL_UNAVAILABLE -> 501
        PROBE_TIMEOUT -> 504
        INTERNAL -> 500
        else -> 502 // PROVIDER_ERROR, UPSTREAM_403, and anything not yet classified
    }

    /** Registry of every code that may appear on the wire (test coverage target). */
    val all: Set<String> = setOf(
        UNAUTHORIZED, REPO_INVALID, PROVIDER_NOT_FOUND, ENTRY_NOT_FOUND,
        PROVIDER_ERROR, INTERNAL, UPSTREAM_403, URL_EXPIRED, PROBE_TIMEOUT,
        CODEC_UNSUPPORTED, FFMPEG_MISSING, GPU_ACCEL_UNAVAILABLE,
    )
}
