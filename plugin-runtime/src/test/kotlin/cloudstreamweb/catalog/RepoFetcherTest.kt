package cloudstreamweb.catalog

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RepoFetcherTest {

    private val manifestJson = """
        {
          "iconUrl": "https://example.com/icon.png",
          "name": "Test Repo",
          "description": "A repo used by tests",
          "manifestVersion": 1,
          "pluginLists": ["https://cdn.example.com/plugins.json"]
        }
    """.trimIndent()

    private val pluginsJson = """
        [
          {
            "url": "https://cdn.example.com/TestProvider.cs3",
            "status": 1,
            "version": 3,
            "apiVersion": 1,
            "name": "Test Provider",
            "internalName": "TestProvider",
            "authors": ["someone"],
            "description": "does test things",
            "tvTypes": ["TvSeries", "Movie"],
            "language": "en",
            "fileSize": 12345,
            "fileHash": "sha256-deadbeef"
          },
          {
            "url": "https://cdn.example.com/Second.cs3",
            "name": "Second",
            "internalName": "Second"
          }
        ]
    """.trimIndent()

    @Test
    fun `manifest and plugin lists parse into models`() = runTest {
        val http = StubHttp(
            mapOf(
                "https://example.com/repo.json" to HttpResp(200, mapOf(), manifestJson),
                "https://cdn.example.com/plugins.json" to HttpResp(200, mapOf(), pluginsJson),
            )
        )
        val fetcher = RepoFetcher(http = http)

        val manifest = fetcher.fetchManifest("https://example.com/repo.json")
        assertEquals("Test Repo", manifest.name)
        assertEquals(1, manifest.manifestVersion)
        assertEquals(listOf("https://cdn.example.com/plugins.json"), manifest.pluginLists)

        val plugins = fetcher.repoPlugins("https://example.com/repo.json")
        assertEquals(2, plugins.size)
        assertEquals("TestProvider", plugins[0].internalName)
        assertEquals(sha256("deadbeef"), plugins[0].fileHash)
        assertEquals(listOf("TvSeries", "Movie"), plugins[0].tvTypes)
        assertEquals(1, plugins[1].status) // default filled in when absent
    }

    @Test
    fun `duplicate plugin urls collapse`() = runTest {
        val dup = """
            [
              {"url": "https://cdn.example.com/A.cs3", "name": "A", "internalName": "A"},
              {"url": "https://cdn.example.com/A.cs3", "name": "A2", "internalName": "A2"}
            ]
        """.trimIndent()
        val http = StubHttp(
            mapOf(
                "https://example.com/repo.json" to HttpResp(200, mapOf(), manifestJson),
                "https://cdn.example.com/plugins.json" to HttpResp(200, mapOf(), dup),
            )
        )
        assertEquals(1, RepoFetcher(http = http).repoPlugins("https://example.com/repo.json").size)
    }

    @Test
    fun `fetches are cached for the ttl window`() = runTest {
        var hits = 0
        val http = object : Http {
            override suspend fun get(url: String, redirect: Boolean): HttpResp {
                hits += 1
                return HttpResp(200, mapOf(), manifestJson)
            }
        }
        val fetcher = RepoFetcher(http = http)
        repeat(3) { fetcher.fetchManifest("https://example.com/repo.json") }
        assertEquals(1, hits, "second and third call must come from the cache")
    }

    @Test
    fun `non-200 responses raise RepoFetchException`() = runTest {
        val http = StubHttp(mapOf("https://example.com/repo.json" to HttpResp(404, mapOf(), "")))
        assertThrows(RepoFetchException::class.java) {
            kotlinx.coroutines.runBlocking { RepoFetcher(http = http).fetchManifest("https://example.com/repo.json") }
        }
    }

    @Test
    fun `catalog parses plain strings and verified objects`() = runTest {
        val dbJson = """
            [
              "https://raw.githubusercontent.com/one/repo/master/repo.json",
              {"url": "https://example.com/official.json", "verified": true},
              {"url": "https://example.com/beta.json"}
            ]
        """.trimIndent()
        val http = StubHttp(
            mapOf(
                "https://raw.githubusercontent.com/recloudstream/cs-repos/master/repos-db.json"
                    to HttpResp(200, mapOf(), dbJson)
            )
        )
        val entries = RepoFetcher(http = http).catalog()
        assertEquals(3, entries.size)
        assertTrue(entries.any { it.verified && it.url == "https://example.com/official.json" })
        assertFalse(entries[0].verified)
    }

    private fun sha256(hex: String) = "sha256-$hex"
}
