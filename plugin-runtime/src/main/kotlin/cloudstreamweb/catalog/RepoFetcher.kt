package cloudstreamweb.catalog

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/**
 * Fetches and parses the repo chain:
 *   repos-db.json (catalog) → repo.json (manifest) → plugins.json (list of SitePlugin)
 * with a 5-minute in-memory cache, mirroring upstream.
 */
class RepoFetcher(
    private val http: Http = OkHttp(),
    private val cache: HttpCache = HttpCache(),
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** The upstream-maintained public catalog of CloudStream repositories. */
    suspend fun catalog(): List<CatalogEntry> {
        val url = "https://raw.githubusercontent.com/recloudstream/cs-repos/master/repos-db.json"
        val resp = cache.wrap(url) { http.get(url) }
        if (resp.status != 200) throw RepoFetchException(url, resp.status)
        return resp.body.parseCatalogEntries()
    }

    suspend fun fetchManifest(manifestUrl: String): RepoManifest {
        val resp = cache.wrap(manifestUrl) { http.get(manifestUrl) }
        if (resp.status != 200) throw RepoFetchException(manifestUrl, resp.status)
        return resp.body.parseManifest()
    }

    suspend fun fetchPluginList(listUrl: String): List<SitePlugin> {
        val resp = cache.wrap(listUrl) { http.get(listUrl) }
        if (resp.status != 200) throw RepoFetchException(listUrl, resp.status)
        return json.decodeFromString(resp.body)
    }

    /** All plugins of a repo (parallel over pluginLists, upstream `amap`). */
    suspend fun repoPlugins(manifestUrl: String): List<SitePlugin> {
        val manifest = fetchManifest(manifestUrl)
        return manifest.pluginLists.flatMap { fetchPluginList(it) }.distinctBy { it.url }
    }

    private fun String.parseManifest(): RepoManifest = json.decodeFromString(this)

    /** entries are either URL strings or objects with url/verified — upstream mixing */
    private fun String.parseCatalogEntries(): List<CatalogEntry> {
        val arr = Json.parseToJsonElement(this).jsonArray.map { e ->
            when (e) {
                is JsonPrimitive -> CatalogEntry(url = e.jsonPrimitive.content)
                is JsonObject -> CatalogEntry(
                    url = e["url"]?.jsonPrimitive?.content ?: "",
                    verified = e["verified"]?.jsonPrimitive?.content == "true",
                )
                else -> null
            }
        }.mapNotNull { entry -> entry?.takeIf { it.url.isNotBlank() } }
        return arr
    }
}

class RepoFetchException(val url: String, val status: Int) : RuntimeException("fetch failed $status for $url")
