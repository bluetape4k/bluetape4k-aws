@file:Suppress("NOTHING_TO_INLINE")

package io.bluetape4k.aws.s3.model

import io.bluetape4k.support.requireZeroOrPositiveNumber
import software.amazon.awssdk.core.sync.RequestBody
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.file.Path

/**
 * See the API documentation for details.
 */
const val DEFAULT_MIME_TYPE: String = "application/octet-stream"

/**
 * See the API documentation for details.
 *
 * Example:
 * ```kotlin
 * val result = "hello".toRequestBody()
 * // result.optionalContentLength().orElse(-1L) == 5L
 * ```
 */
inline fun String.toRequestBody(charset: Charset = Charsets.UTF_8): RequestBody =
    RequestBody.fromString(this, charset)

/**
 * See the API documentation for details.
 *
 * Example:
 * ```kotlin
 * val result = byteArrayOf(1, 2, 3).toRequestBody()
 * // result.optionalContentLength().orElse(-1L) == 3L
 * ```
 */
inline fun ByteArray.toRequestBody(): RequestBody = RequestBody.fromBytes(this)

/**
 * See the API documentation for details.
 */
inline fun ByteBuffer.toRequestBody(): RequestBody = RequestBody.fromByteBuffer(this)

/**
 * See the API documentation for details.
 */
inline fun File.toRequestBody(): RequestBody = RequestBody.fromFile(this)

/**
 * See the API documentation for details.
 */
inline fun Path.toRequestBody(): RequestBody = RequestBody.fromFile(this)

/**
 * See the API documentation for details.
 *
 * See the API documentation for details.
 */
inline fun InputStream.toRequestBody(contentLength: Long): RequestBody {
    contentLength.requireZeroOrPositiveNumber("contentLength")
    return RequestBody.fromInputStream(this, contentLength)
}

/**
 * See the API documentation for details.
 */
inline fun requestBodyOf(text: String, charset: Charset = Charsets.UTF_8): RequestBody =
    RequestBody.fromString(text, charset)

/**
 * See the API documentation for details.
 */
inline fun requestBodyOf(bytes: ByteArray): RequestBody = RequestBody.fromBytes(bytes)

/**
 * See the API documentation for details.
 */
inline fun requestBodyOf(byteBuffer: ByteBuffer): RequestBody = RequestBody.fromByteBuffer(byteBuffer)

/**
 * See the API documentation for details.
 */
inline fun requestBodyOf(file: File): RequestBody = RequestBody.fromFile(file)

/**
 * See the API documentation for details.
 */
inline fun requestBodyOf(path: Path): RequestBody = RequestBody.fromFile(path)

/**
 * See the API documentation for details.
 *
 * See the API documentation for details.
 *
 * Example:
 * ```kotlin
 * val stream = java.io.ByteArrayInputStream(byteArrayOf(10, 20, 30))
 * val result = requestBodyOf(stream, 3)
 * // result.optionalContentLength().orElse(-1L) == 3L
 * ```
 */
inline fun requestBodyOf(
    inputStream: InputStream,
    contentLength: Long,
): RequestBody {
    contentLength.requireZeroOrPositiveNumber("contentLength")
    return RequestBody.fromInputStream(inputStream, contentLength)
}

/**
 * See the API documentation for details.
 *
 * Example:
 * ```kotlin
 * val result = requestBodyOf("text/plain") {
 *     java.io.ByteArrayInputStream("hello".toByteArray())
 * }
 * // result.contentType() == "text/plain"
 * ```
 */
fun requestBodyOf(
    mimeType: String = DEFAULT_MIME_TYPE,
    contentProvider: () -> InputStream,
): RequestBody =
    RequestBody.fromContentProvider(contentProvider, mimeType)
