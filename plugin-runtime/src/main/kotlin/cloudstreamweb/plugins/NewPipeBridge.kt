package cloudstreamweb.plugins

import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request as NpRequest
import org.schabi.newpipe.extractor.downloader.Response
import java.util.concurrent.TimeUnit

/**
 * NewPipeExtractor needs a global Downloader (upstream's Android app calls
 * NewPipe.init(...) during app startup — invisible magic providers rely on).
 * Our headless server must do the same: YoutubeProvider (and any NewPipe-based
 * provider) throws "downloader is null" until this runs once.
 */
object NewPipeBridge {

    @Volatile private var initialized = false

    fun initOnce() {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            try {
                NewPipe.init(OkHttpDownloader(OkHttpClient.Builder()
                    .connectTimeout(20, TimeUnit.SECONDS)
                    .readTimeout(30, TimeUnit.SECONDS)
                    .build()))
                initialized = true
                System.err.println("NewPipe initialized (okhttp downloader)")
            } catch (t: Throwable) {
                System.err.println("NewPipe.init failed: $t")
            }
        }
    }
}

private class OkHttpDownloader(private val client: OkHttpClient) : Downloader() {
    @Throws(java.io.IOException::class)
    override fun execute(request: NpRequest): Response {
        val method = request.httpMethod().uppercase()
        val body = request.dataToSend()
        val builder = okhttp3.Request.Builder()
        builder.url(request.url())
        for ((name, values) in request.headers()) {
            for (v in values) builder.header(name, v)
        }
        val contentType = request.headers()["Content-Type"]?.firstOrNull()?.toMediaTypeOrNull()
            ?: if (body != null) "application/octet-stream".toMediaTypeOrNull() else null

        when (method) {
            "GET" -> builder.get()
            "HEAD" -> builder.head()
            "POST" -> builder.post(body.toRequestBodyOrEmpty(contentType))
            "PUT" -> builder.put(body.toRequestBodyOrEmpty(contentType))
            "PATCH" -> builder.patch(body.toRequestBodyOrEmpty(contentType))
            "DELETE" -> builder.delete()
            else -> builder.method(method, body.toRequestBodyOrEmpty(contentType))
        }

        client.newCall(builder.build()).execute().use { resp ->
            val text = resp.body?.string() ?: ""
            return Response(
                resp.code,
                resp.message,
                resp.headers.toMultimap(),
                text,
                resp.request.url.toString(),
            )
        }
    }

    private fun ByteArray?.toRequestBodyOrEmpty(contentType: okhttp3.MediaType?) =
        (this ?: ByteArray(0)).toRequestBody(contentType)
}

