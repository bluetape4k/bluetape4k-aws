package io.bluetape4k.aws.ktor.s3

import io.ktor.http.Headers
import io.ktor.http.HttpStatusCode

/**
 * Thrown when the S3 REST API returns a non-2xx response.
 *
 * ## Behavior/Contract
 *
 * Preserves the HTTP status, S3 error code/message, request id, host id, and response headers.
 * If the XML error body cannot be parsed, part of the response body may be propagated as the message.
 *
 * ```kotlin
 * suspend fun readOrNull(s3: S3KtorClient): ByteArray? {
 *     return try {
 *         s3.getObjectBytes("demo-bucket", "missing.txt")
 *     } catch (e: S3KtorException) {
 *         check(e.status.value == 404)
 *         check(e.code == "NoSuchKey")
 *         null
 *     }
 * }
 * ```
 */
class S3KtorException(
    val status: HttpStatusCode,
    val code: String?,
    override val message: String,
    val requestId: String?,
    val hostId: String?,
    val headers: Headers,
): RuntimeException(message)
