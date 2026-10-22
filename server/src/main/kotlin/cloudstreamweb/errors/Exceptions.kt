package cloudstreamweb.errors

import kotlinx.serialization.Serializable

/**
 * Typed API failure: `kind` is a machine-readable code from [ErrorCodes]
 * (routes may only return registered codes), `message` is the human detail
 * that travels to the SPA inside [ErrorBody].

 * Statuses are assigned centrally by [ErrorCodes.statusFor].
 */
class ProviderException(val kind: String, message: String, cause: Throwable? = null) :
    RuntimeException(message, cause)

/** The wire shape of every failure path (matches the `X-Req-Id` contract). */
@Serializable
data class ErrorBody(val error: String, val detail: String? = null)
