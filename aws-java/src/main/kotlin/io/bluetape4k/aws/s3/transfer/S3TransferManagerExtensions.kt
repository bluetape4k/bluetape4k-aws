package io.bluetape4k.aws.s3.transfer

import io.bluetape4k.aws.s3.model.putObjectRequestOf
import io.bluetape4k.aws.s3.model.toAsyncRequestBody
import io.bluetape4k.io.exists
import io.bluetape4k.logging.KotlinLogging
import io.bluetape4k.support.requireNotBlank
import software.amazon.awssdk.core.ResponseBytes
import software.amazon.awssdk.core.async.AsyncRequestBody
import software.amazon.awssdk.core.async.AsyncResponseTransformer
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.GetObjectResponse
import software.amazon.awssdk.transfer.s3.S3TransferManager
import software.amazon.awssdk.transfer.s3.model.Download
import software.amazon.awssdk.transfer.s3.model.DownloadFileRequest
import software.amazon.awssdk.transfer.s3.model.DownloadRequest
import software.amazon.awssdk.transfer.s3.model.FileDownload
import software.amazon.awssdk.transfer.s3.model.FileUpload
import software.amazon.awssdk.transfer.s3.model.Upload
import software.amazon.awssdk.transfer.s3.model.UploadFileRequest
import software.amazon.awssdk.transfer.s3.model.UploadRequest
import java.nio.file.Path

private val log = KotlinLogging.logger { }

/**
 * See the API documentation for details.
 *
 * @param T Parameter.
 * @param responseTransformer Parameter.
 * @param builder Parameter.
 * @return Return value.
 *
 * Example:
 * ```kotlin
 * val download = transferManager.downloadAsync(AsyncResponseTransformer.toBytes())
 * // download.completionFuture().isDone == false
 * ```
 */
inline fun <T: Any> S3TransferManager.downloadAsync(
    responseTransformer: AsyncResponseTransformer<GetObjectResponse, T>,
    builder: DownloadRequest.UntypedBuilder.() -> Unit = {},
): Download<T> = download(downloadRequest(responseTransformer, builder))

/**
 * See the API documentation for details.
 *
 * @param T Parameter.
 * @param bucket bucket name
 * @param key key
 * @param responseTransformer Parameter.
 * @param builder Parameter.
 * @return Return value.
 *
 * Example:
 * ```kotlin
 * val download = transferManager.downloadAsync("demo-bucket", "docs/readme.txt", AsyncResponseTransformer.toBytes())
 * // download.completionFuture().isCompletedExceptionally == false
 * ```
 */
inline fun <T: Any> S3TransferManager.downloadAsync(
    bucket: String,
    key: String,
    responseTransformer: AsyncResponseTransformer<GetObjectResponse, T>,
    crossinline builder: DownloadRequest.UntypedBuilder.() -> Unit = {},
): Download<T> {
    bucket.requireNotBlank("bucket")
    key.requireNotBlank("key")

    val request = downloadRequestOf(bucket, key, responseTransformer, builder)
    return download(request)
}

/**
 * See the API documentation for details.
 *
 * @param bucket bucket name
 * @param key key
 * @param builder Parameter.
 * @return Return value.
 *
 * Example:
 * ```kotlin
 * val download = transferManager.downloadAsByteArrayAsync("demo-bucket", "docs/readme.txt")
 * // download.completionFuture().isDone == false
 * ```
 */
inline fun S3TransferManager.downloadAsByteArrayAsync(
    bucket: String,
    key: String,
    crossinline builder: DownloadRequest.UntypedBuilder.() -> Unit = {},
): Download<ResponseBytes<GetObjectResponse>> {
    bucket.requireNotBlank("bucket")
    key.requireNotBlank("key")

    val request =
        downloadRequestOf(bucket, key, AsyncResponseTransformer.toBytes(), builder)

    return download(request)
}

/**
 * See the API documentation for details.
 *
 * @param bucket bucket name
 * @param key key
 * @param destination Parameter.
 * @param builder Parameter.
 * See the API documentation for details.
 * @return Return value.
 *
 * Example:
 * ```kotlin
 * val target = java.nio.file.Path.of("build/tmp/readme.txt")
 * val download = transferManager.downloadFileAsync("demo-bucket", "docs/readme.txt", target)
 * // download.completionFuture().isDone == false
 * ```
 */
inline fun S3TransferManager.downloadFileAsync(
    bucket: String,
    key: String,
    destination: Path,
    builder: DownloadFileRequest.Builder.() -> Unit = {},
): FileDownload {
    bucket.requireNotBlank("bucket")
    key.requireNotBlank("key")

    val request = downloadFileRequestOf(bucket, key, destination, builder)
    return downloadFile(request)
}

/**
 * See the API documentation for details.
 *
 * @param bucket bucket name
 * @param key key
 * @param asyncRequestBody Parameter.
 * @param builder Parameter.
 * @return Return value.
 *
 * Example:
 * ```kotlin
 * val upload = transferManager.uploadAsync("demo-bucket", "notes/hello.txt", "hello".toAsyncRequestBody())
 * // upload.completionFuture().isDone == false
 * ```
 */
inline fun S3TransferManager.uploadAsync(
    bucket: String,
    key: String,
    asyncRequestBody: AsyncRequestBody,
    builder: UploadRequest.Builder.() -> Unit = {},
): Upload {
    bucket.requireNotBlank("bucket")
    key.requireNotBlank("key")

    val request =
        uploadRequest {
            putObjectRequest(putObjectRequestOf(bucket, key))
            requestBody(asyncRequestBody)
            builder()
        }
    return upload(request)
}

/**
 * See the API documentation for details.
 *
 * @param bucket bucket name
 * @param key key
 * @param content Parameter.
 * @param builder Parameter.
 * @return Return value.
 *
 * Example:
 * ```kotlin
 * val upload = transferManager.uploadByteArrayAsync("demo-bucket", "notes/data.bin", byteArrayOf(1, 2, 3))
 * // upload.completionFuture().isDone == false
 * ```
 */
inline fun S3TransferManager.uploadByteArrayAsync(
    bucket: String,
    key: String,
    content: ByteArray,
    builder: UploadRequest.Builder.() -> Unit = {},
): Upload {
    bucket.requireNotBlank("bucket")
    key.requireNotBlank("key")

    val request =
        uploadRequest {
            putObjectRequest(putObjectRequestOf(bucket, key))
            requestBody(content.toAsyncRequestBody())
            builder()
        }

    return upload(request)
}

/**
 * See the API documentation for details.
 *
 * @param bucket bucket name
 * @param key key
 * @param source Parameter.
 * @param builder Parameter.
 * @return Return value.
 * @throws Throwable if the operation fails.
 *
 * Example:
 * ```kotlin
 * val source = java.nio.file.Path.of("settings.gradle.kts")
 * val upload = transferManager.uploadFileAsync("demo-bucket", "repo/settings.gradle.kts", source)
 * // upload.completionFuture().isDone == false
 * ```
 */
inline fun S3TransferManager.uploadFileAsync(
    bucket: String,
    key: String,
    source: Path,
    builder: UploadFileRequest.Builder.() -> Unit = {},
): FileUpload {
    bucket.requireNotBlank("bucket")
    key.requireNotBlank("key")
    require(source.exists()) { "File not found. source=$source" }

    val request =
        uploadFileRequest {
            putObjectRequest(putObjectRequestOf(bucket, key))
            source(source)
            builder()
        }

    return uploadFile(request)
}
