package cloudstreamweb.errors

import io.ktor.util.AttributeKey

/**
 * Per-request correlation id (8 hex chars). Generated in the app contract's
 * first pipeline phase, echoed as the `X-Req-Id` response header, and printed
 * on every `[api-error]` log line so a browser-side failure maps 1:1 onto the
 * server log without timezone archaeology.
 */
object ReqId {
    val Key = AttributeKey<String>("csweb.reqId")

    fun next(): String = java.lang.Long.toHexString(java.util.concurrent.ThreadLocalRandom.current().nextLong())
        .take(8)
}
