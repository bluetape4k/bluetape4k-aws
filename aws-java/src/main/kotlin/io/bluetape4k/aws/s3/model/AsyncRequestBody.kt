@file:Suppress("NOTHING_TO_INLINE")

package io.bluetape4k.aws.s3.model

import org.reactivestreams.Publisher
import software.amazon.awssdk.core.async.AsyncRequestBody
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.file.Path
import java.util.concurrent.ExecutorService
import java.util.concurrent.ForkJoinPool

/**
 * See the API documentation for details.
 *
 * Example:
 * ```kotlin
 * val result = "hello".toAsyncRequestBody()
 * // result.contentLength().orElse(-1L) == 5L
 * ```
 */
inline fun String.toAsyncRequestBody(charset: Charset = Charsets.UTF_8): AsyncRequestBody =
    AsyncRequestBody.fromString(this, charset)

/**
 * See the API documentation for details.
 */
inline fun ByteArray.toAsyncRequestBody(): AsyncRequestBody = AsyncRequestBody.fromBytes(this)

/**
 * See the API documentation for details.
 */
inline fun ByteBuffer.toAsyncRequestBody(): AsyncRequestBody = AsyncRequestBody.fromByteBuffer(this)

/**
 * See the API documentation for details.
 */
inline fun File.toAsyncRequestBody(): AsyncRequestBody = AsyncRequestBody.fromFile(this)

/**
 * See the API documentation for details.
 */
inline fun Path.toAsyncRequestBody(): AsyncRequestBody = AsyncRequestBody.fromFile(this)

/**
 * See the API documentation for details.
 *
 * See the API documentation for details.
 */
inline fun InputStream.toAsyncRequestBody(
    contentLength: Long,
    executor: ExecutorService = ForkJoinPool.commonPool(),
): AsyncRequestBody {
    require(contentLength >= 0L) { "contentLength must be >= 0, but was $contentLength" }
    return AsyncRequestBody.fromInputStream(this, contentLength, executor)
}

/**
 * See the API documentation for details.
 */
inline fun asyncRequestBodyOf(text: String, cs: Charset = Charsets.UTF_8): AsyncRequestBody =
    AsyncRequestBody.fromString(text, cs)

/**
 * See the API documentation for details.
 */
inline fun asyncRequestBodyOf(bytes: ByteArray): AsyncRequestBody = AsyncRequestBody.fromBytes(bytes)

/**
 * See the API documentation for details.
 */
inline fun asyncRequestBodyOf(byteBuffer: ByteBuffer): AsyncRequestBody = AsyncRequestBody.fromByteBuffer(byteBuffer)

/**
 * See the API documentation for details.
 */
inline fun asyncRequestBodyOf(file: File): AsyncRequestBody = AsyncRequestBody.fromFile(file)

/**
 * See the API documentation for details.
 */
inline fun asyncRequestBodyOf(path: Path): AsyncRequestBody = AsyncRequestBody.fromFile(path)

/**
 * See the API documentation for details.
 *
 * See the API documentation for details.
 *
 * Example:
 * ```kotlin
 * val stream = java.io.ByteArrayInputStream(byteArrayOf(1, 2, 3, 4))
 * val result = asyncRequestBodyOf(stream, 4)
 * // result.contentLength().orElse(-1L) == 4L
 * ```
 */
inline fun asyncRequestBodyOf(
    inputStream: InputStream,
    contentLength: Long,
    executor: ExecutorService = ForkJoinPool.commonPool(),
): AsyncRequestBody {
    require(contentLength >= 0L) { "contentLength must be >= 0, but was $contentLength" }
    return AsyncRequestBody.fromInputStream(inputStream, contentLength, executor)
}

/**
 * See the API documentation for details.
 *
 * Example:
 * ```kotlin
 * val publisher = org.reactivestreams.FlowAdapters.toPublisher(
 *     java.util.concurrent.SubmissionPublisher<java.nio.ByteBuffer>().apply {
 *         submit(java.nio.ByteBuffer.wrap(byteArrayOf(1, 2, 3)))
 *         close()
 *     }
 * )
 * val result = asyncRequestBodyOf(publisher)
 * // result.contentLength().isPresent == false
 * ```
 */
inline fun asyncRequestBodyOf(
    contentPublisher: Publisher<ByteBuffer>,
): AsyncRequestBody =
    AsyncRequestBody.fromPublisher(contentPublisher)
