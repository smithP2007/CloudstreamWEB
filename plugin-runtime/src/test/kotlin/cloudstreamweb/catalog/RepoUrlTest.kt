package cloudstreamweb.catalog

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RepoUrlTest {

    @Test
    fun `full https urls pass through`() {
        assertEquals(
            "https://example.com/repo.json",
            RepoUrl.normalizeWithoutShortcode("https://example.com/repo.json")
        )
        assertEquals(
            "http://example.com/repo.json",
            RepoUrl.normalizeWithoutShortcode("  http://example.com/repo.json ")
        )
    }

    @Test
    fun `cloudstreamrepo scheme is stripped and https is prepended`() {
        assertEquals(
            "https://example.com/repo.json",
            RepoUrl.normalizeWithoutShortcode("cloudstreamrepo://example.com/repo.json")
        )
        // upstream parity: when the stripped part contains an inner https url,
        // the whole original string comes back unchanged
        assertEquals(
            "cloudstreamrepo://https://example.com/repo.json",
            RepoUrl.normalizeWithoutShortcode("cloudstreamrepo://https://example.com/repo.json")
        )
    }

    @Test
    fun `scheme-wrapped inputs resolve to a fetchable https url`() = runTest {
        assertEquals(
            "https://example.com/repo.json",
            RepoUrl.resolve("cloudstreamrepo://https://example.com/repo.json", StubHttp())
        )
    }

    @Test
    fun `cs-dot-repo urls pass through unchanged (redirector domain)`() {
        val input = "https://cs.repo/?https://example.com/repo.json"
        assertEquals(input, RepoUrl.normalizeWithoutShortcode(input))
        assertEquals(
            "https://cs.repo/https://example.com/repo.json",
            RepoUrl.normalizeWithoutShortcode("https://cs.repo/https://example.com/repo.json")
        )
    }

    @Test
    fun `non-urls are rejected by the pure normalizer`() {
        assertNull(RepoUrl.normalizeWithoutShortcode("myRepoCode"))
        assertNull(RepoUrl.normalizeWithoutShortcode("example.com/repo.json"))
        assertNull(RepoUrl.normalizeWithoutShortcode(""))
    }

    @Test
    fun `shortcode detection`() {
        assertTrue(RepoUrl.isShortcode("myRepoCode"))
        assertTrue(RepoUrl.isShortcode("abc_DEF-1"))
        assertTrue(RepoUrl.isShortcode("!bang"))
        assertFalse(RepoUrl.isShortcode("https://example.com/repo.json"))
        assertFalse(RepoUrl.isShortcode("a b c"))
        assertFalse(RepoUrl.isShortcode("with.dot"))
    }

    @Test
    fun `cuttly shortcode resolves from Location header`() = runTest {
        val http = StubHttp(
            "https://cutt.ly/myCode" to HttpResp(
                status = 302,
                headers = mapOf("location" to "https://example.com/repo.json"),
                body = ""
            )
        )
        assertEquals("https://example.com/repo.json", RepoUrl.resolveShortcode("myCode", http))
        assertEquals("https://example.com/repo.json", RepoUrl.resolve("myCode", http))
    }

    @Test
    fun `bang shortcodes use py-md`() = runTest {
        val http = StubHttp(
            "https://py.md/bangCode" to HttpResp(
                status = 301,
                headers = mapOf("Location" to "https://example.com/repo.json"),
                body = ""
            )
        )
        assertEquals("https://example.com/repo.json", RepoUrl.resolveShortcode("!bangCode", http))
    }

    @Test
    fun `error redirects count as unresolved`() = runTest {
        val http = StubHttp(
            "https://py.md/missing" to HttpResp(
                status = 302,
                headers = mapOf("Location" to "https://py.md/404?url=x"),
                body = ""
            )
        )
        assertNull(RepoUrl.resolveShortcode("!missing", http))
    }

    @Test
    fun `non-redirect answers count as unresolved`() = runTest {
        val http = StubHttp(
            "https://cutt.ly/bad" to HttpResp(status = 200, headers = mapOf(), body = "whatever")
        )
        assertNull(RepoUrl.resolveShortcode("bad", http))
    }

    @Test
    fun `jsdelivr fallback conversion`() {
        assertEquals(
            "https://cdn.jsdelivr.net/gh/user/repo@main/repo.json",
            RepoUrl.toJsdelivr("https://raw.githubusercontent.com/user/repo/main/repo.json")
        )
        assertNull(RepoUrl.toJsdelivr("https://example.com/foo.json"))
    }
}

/** Minimal canned-HTTP stub for tests. */
class StubHttp(private val responses: Map<String, HttpResp>) : Http {
    constructor(vararg responses: Pair<String, HttpResp>) : this(responses.toMap())

    override suspend fun get(url: String, redirect: Boolean): HttpResp =
        responses[url] ?: HttpResp(status = 404, headers = mapOf(), body = "")
}
