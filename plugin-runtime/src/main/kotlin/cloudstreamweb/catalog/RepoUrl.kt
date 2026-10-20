package cloudstreamweb.catalog

/**
 * Port of upstream RepositoryManager.parseRepoUrl semantics.
 * Accepts:
 *  - full http(s) URLs (unchanged)
 *  - `cloudstreamrepo://host/...` and `https://cs.repo/...` (scheme handling per upstream, see below)
 *  - shortcodes [a-zA-Z0-9!_-]+ resolved via cutt.ly / py.md redirects (no-redirect GET → Location header)
 */
object RepoUrl {

    // Upstream regexes (kept verbatim in behavior). NOTE: upstream uses
    // contains() with ^-anchored patterns (startsWith semantics).
    private val httpPrefix = Regex("^https?://")
    private val csRepoPattern = Regex("^(cloudstreamrepo://)|(https://cs\\.repo/\\??)")
    private val shortcodePattern = Regex("^[a-zA-Z0-9!_-]+$")
    private val jsdelivrPattern = Regex("^https://raw.githubusercontent.com/([A-Za-z0-9-]+)/([A-Za-z0-9_.-]+)/(.*)$")

    /**
     * Pure normalization for URL prefixes (upstream behavior: cs.repo URLs are
     * returned as-is since cs.repo is a redirector; an inner URL after
     * cloudstreamrepo:// keeps the whole original string). Returns null when
     * the input is not a URL at all.
     */
    fun normalizeWithoutShortcode(inputRaw: String): String? {
        val input = inputRaw.trim()
        return when {
            input.contains(httpPrefix) -> input
            input.contains(csRepoPattern) ->
                input.replace(csRepoPattern, "").let { stripped ->
                    if (!stripped.contains(httpPrefix)) "https://$stripped" else input
                }
            else -> null
        }
    }

    fun isShortcode(inputRaw: String): Boolean {
        val input = inputRaw.trim()
        return input.matches(shortcodePattern)
    }

    /**
     * Resolve shortcode to a URL via cutt.ly (leading "!" selects py.md).
     * `http` injected for testability. Returns null on failure.
     */
    suspend fun resolveShortcode(code: String, http: Http): String? {
        return try {
            val resp = if (code.startsWith("!")) {
                http.get("https://py.md/${code.removePrefix("!")}", redirect = false)
            } else {
                http.get("https://cutt.ly/$code", redirect = false)
            }
            if (resp.status !in 300..399) return null
            val location = resp.header("Location") ?: resp.header("location") ?: return null
            if (location.startsWith("https://py.md/404") || location.startsWith("https://cutt.ly/404")) return null
            if (location.removeSuffix("/") == "https://py.md" || location.removeSuffix("/") == "https://cutt.ly") return null
            location
        } catch (t: Throwable) {
            null
        }
    }

    /** jsdelivr fallback conversion (mirrors upstream convertRawGitUrl). */
    fun toJsdelivr(url: String): String? {
        val match = jsdelivrPattern.find(url) ?: return null
        val (user, repo, rest) = match.destructured
        return "https://cdn.jsdelivr.net/gh/$user/$repo@$rest"
    }

    /** Full entry point: normalize a user-typed string to a manifest URL, or null. */
    suspend fun resolve(inputRaw: String, http: Http): String? {
        // Documented deviation from upstream parseRepoUrl: if a scheme-wrapped
        // input (cloudstreamrepo://+https://...) keeps the cloudstreamrepo://
        // prefix, strip it here so the result is always a fetchable https URL.
        normalizeWithoutShortcode(inputRaw)?.let { return it.removePrefix("cloudstreamrepo://") }
        if (isShortcode(inputRaw)) return resolveShortcode(inputRaw.trim(), http)
        return null
    }
}
