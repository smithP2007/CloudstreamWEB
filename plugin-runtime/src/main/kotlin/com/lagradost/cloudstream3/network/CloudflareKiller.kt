package com.lagradost.cloudstream3.network

import okhttp3.Interceptor
import okhttp3.Response

/**
 * Headless port of the app-module CloudflareKiller (upstream
 * app/src/main/java/com/lagradost/cloudstream3/network/CloudflareKiller.kt).
 *
 * Community extractors (HubCloud/VCloud/GDFlix family, CSX) attach
 * `interceptor = CloudflareKiller()` and call getCookieHeaders(url). Upstream
 * solves 403/503 cloudflare challenges with an Android WebView; headless
 * there is no WebView/JS, so we keep the interceptor API and semantics
 * (saved-cookies map +etag pass-through) and let challenges fall through —
 * sites then answer as they would to a plain client. Success or failure is
 * visible in the extractor errors.
 */
class CloudflareKiller : Interceptor {

    companion object {
        const val TAG = "CloudflareKiller"
        private val ERROR_CODES = listOf(403, 503)
        private val CLOUDFLARE_SERVERS = listOf("cloudflare-nginx", "cloudflare")

        fun parseCookieMap(cookie: String): Map<String, String> =
            cookie.split(";").mapNotNull {
                val split = it.split("=")
                val key = split.getOrNull(0)?.trim() ?: return@mapNotNull null
                val value = split.getOrNull(1)?.trim() ?: return@mapNotNull null
                if (key.isBlank() || value.isBlank()) return@mapNotNull null
                key to value
            }.toMap()
    }

    val savedCookies: MutableMap<String, Map<String, String>> = mutableMapOf()

    /** Headers (+ cookies) that upstream would use for a manual request. */
    fun getCookieHeaders(url: String): okhttp3.Headers {
        val cookies = savedCookies[java.net.URI(url).host] ?: emptyMap()
        val builder = okhttp3.Headers.Builder()
        for ((k, v) in cookies) builder.add(k, v)
        return builder.build()
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)
        val isChallenge = response.header("Server") in CLOUDFLARE_SERVERS &&
            response.code in ERROR_CODES
        if (!isChallenge) return response

        // Remember the cf_clearance cookie so repeated calls reuse it, but no
        // WebView challenge solving exists headless.
        response.header("Set-Cookie")?.let { setCookie ->
            parseCookieMap(setCookie).let { parsed ->
                if (parsed.isNotEmpty()) savedCookies[request.url.host] = parsed
            }
        }
        return response
    }
}
