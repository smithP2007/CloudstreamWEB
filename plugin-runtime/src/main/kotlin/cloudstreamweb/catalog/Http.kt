package cloudstreamweb.catalog

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Minimal HTTP surface so URL resolution / manifest fetching can be unit
 * tested with a stub. Mirrors what upstream RepositoryManager does
 * (no-redirect shortcodes, in-memory manifest caching with TTL).
 */
data class HttpResp(val status: Int, val headers: Map<String, String>, val body: String) {
    fun header(name: String): String? = headers[name.lowercase()] ?: headers[name]
}

interface Http {
    suspend fun get(url: String, redirect: Boolean = true): HttpResp
}

class OkHttp : Http {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private val noRedirectClient = client.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    override suspend fun get(url: String, redirect: Boolean): HttpResp = withContext(Dispatchers.IO) {
        val cli = if (redirect) client else noRedirectClient
        val req = Request.Builder().url(url).get().build()
        cli.newCall(req).execute().use { resp ->
            val headers = resp.headers.toMultimap().mapValues { it.value.firstOrNull() ?: "" }.mapKeys { it.key.lowercase() }
            HttpResp(resp.code, headers, resp.body?.string() ?: "")
        }
    }
}

/**
 * In-memory TTL cache used for manifest/plugin-list fetches.
 * Upstream uses a 5-minute cache for repo manifests and plugin lists.
 */
class HttpCache(private val ttlMs: Long = 5 * 60 * 1000) {
    private val mutex = Mutex()
    private val entries = HashMap<String, Pair<Long, HttpResp>>()

    suspend fun wrap(url: String, fetch: suspend () -> HttpResp): HttpResp {
        val now = System.currentTimeMillis()
        mutex.withLock {
            entries[url]?.let { (at, resp) ->
                if (now - at < ttlMs) return resp
            }
        }
        val resp = fetch()
        mutex.withLock { entries[url] = now to resp }
        return resp
    }

    fun reset() {
        mutex.tryLock().let { locked ->
            if (locked) try { entries.clear() } finally { mutex.unlock() }
        }
    }
}
