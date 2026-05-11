package io.bluetape4k.aws.ktor.s3

import io.ktor.http.Headers
import io.ktor.http.HttpStatusCode

/**
 * S3 REST API가 non-2xx 응답을 반환할 때 발생합니다.
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
