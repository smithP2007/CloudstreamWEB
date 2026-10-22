package cloudstreamweb.errors

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * v2 M0: the taxonomy is the contract — every code that may appear on the
 * wire is registered here with a stable HTTP status, unknown codes fail
 * closed, and the codes the current routes already emit keep their status
 * (regression-proof for the SPA).
 */
class ErrorTaxonomyTest {

    @Test
    fun `shipped codes keep the statuses the SPA already relies on`() {
        assertEquals(401, ErrorCodes.statusFor(ErrorCodes.UNAUTHORIZED))
        assertEquals(400, ErrorCodes.statusFor(ErrorCodes.REPO_INVALID))
        assertEquals(404, ErrorCodes.statusFor(ErrorCodes.PROVIDER_NOT_FOUND))
        assertEquals(404, ErrorCodes.statusFor(ErrorCodes.ENTRY_NOT_FOUND))
        assertEquals(502, ErrorCodes.statusFor(ErrorCodes.PROVIDER_ERROR))
        assertEquals(500, ErrorCodes.statusFor(ErrorCodes.INTERNAL))
    }

    @Test
    fun `v2 playback codes have their planned statuses`() {
        assertEquals(502, ErrorCodes.statusFor(ErrorCodes.UPSTREAM_403))
        assertEquals(410, ErrorCodes.statusFor(ErrorCodes.URL_EXPIRED))
        assertEquals(504, ErrorCodes.statusFor(ErrorCodes.PROBE_TIMEOUT))
        assertEquals(422, ErrorCodes.statusFor(ErrorCodes.CODEC_UNSUPPORTED))
        assertEquals(501, ErrorCodes.statusFor(ErrorCodes.FFMPEG_MISSING))
        assertEquals(501, ErrorCodes.statusFor(ErrorCodes.GPU_ACCEL_UNAVAILABLE))
    }

    @Test
    fun `unknown codes fail closed to 502`() {
        assertEquals(502, ErrorCodes.statusFor("SOMETHING_NEVER_SEEN"))
        assertEquals(502, ErrorCodes.statusFor(""))
    }

    @Test
    fun `registry is complete and code names are unique`() {
        assertEquals(ErrorCodes.all.size, ErrorCodes.all.size) // set semantics = unique
        assertTrue(ErrorCodes.INTERNAL in ErrorCodes.all)
        // every shipped + planned code is in the registry
        listOf(
            ErrorCodes.UNAUTHORIZED, ErrorCodes.REPO_INVALID, ErrorCodes.PROVIDER_NOT_FOUND,
            ErrorCodes.ENTRY_NOT_FOUND, ErrorCodes.PROVIDER_ERROR, ErrorCodes.INTERNAL,
            ErrorCodes.UPSTREAM_403, ErrorCodes.URL_EXPIRED, ErrorCodes.PROBE_TIMEOUT,
            ErrorCodes.CODEC_UNSUPPORTED, ErrorCodes.FFMPEG_MISSING, ErrorCodes.GPU_ACCEL_UNAVAILABLE,
        ).forEach { assertTrue(it in ErrorCodes.all, "unregistered code: $it") }
    }
}
