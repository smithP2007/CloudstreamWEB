package android.net

/**
 * Minimal android.net.Uri stub: community providers call
 * Uri.parse(...)?.getHost() while matching cookie/redirect domains during
 * stream extraction (verified in CSX Settings bytecode). Backed by
 * java.net.URI for size-correct host extraction.
 */
class Uri private constructor(private val value: String) {

    /** Host without port/fragment; empty string mirrors Android's fallback. */
    fun getHost(): String = try {
        java.net.URI(value).host ?: ""
    } catch (_: Throwable) {
        ""
    }

    override fun toString(): String = value

    companion object {
        @JvmStatic
        fun parse(uri: String?): Uri = Uri(uri ?: "")
    }
}
