package io.bluetape4k.aws.s3

import io.bluetape4k.aws.s3.model.MoveObjectResult
import io.bluetape4k.aws.s3.model.getObjectRequest
import io.bluetape4k.aws.s3.model.putObjectRequest
import io.bluetape4k.aws.s3.model.toAsyncRequestBody
import io.bluetape4k.concurrent.completableFutureOf
import io.bluetape4k.logging.KotlinLogging
import io.bluetape4k.logging.error
import io.bluetape4k.logging.warn
import io.bluetape4k.support.requireNotBlank
import software.amazon.awssdk.core.async.AsyncRequestBody
import software.amazon.awssdk.core.async.AsyncResponseTransformer
import software.amazon.awssdk.services.s3.S3AsyncClient
import software.amazon.awssdk.services.s3.model.CopyObjectRequest
import software.amazon.awssdk.services.s3.model.CreateBucketConfiguration
import software.amazon.awssdk.services.s3.model.CreateBucketResponse
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.GetObjectResponse
import software.amazon.awssdk.services.s3.model.NoSuchBucketException
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import software.amazon.awssdk.services.s3.model.PutObjectResponse
import software.amazon.awssdk.services.s3.model.S3Exception
import software.amazon.awssdk.services.s3.model.S3Object
import java.io.File
import java.nio.charset.Charset
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.ExecutionException

private val log = KotlinLogging.logger { }

/**
 * See the API documentation for details.
 *
 * See the API documentation for details.
 *
 * @param bucketName Parameter.
 * @return Return value.
 *
 * Example:
 * ```kotlin
 * val result = s3AsyncClient.existsBucketAsync("demo-bucket").join()
 * // result == true
 * ```
 */
fun S3AsyncClient.existsBucketAsync(bucketName: String): CompletableFuture<Boolean> {
    bucketName.requireNotBlank("bucketName")

    return headBucket { it.bucket(bucketName) }
        .handle { _, error ->
            when {
                error == null                -> true
                error.isMissingBucketError() -> false
                else                         -> throw error
            }
        }
}

private fun Throwable.isMissingBucketError(): Boolean {
    val cause = unwrapKnownWrapper()
    return when (cause) {
        is NoSuchBucketException -> true
        is S3Exception           -> cause.statusCode() == 404 ||
                cause.awsErrorDetails()?.errorCode() in setOf("NoSuchBucket", "NotFound")
        else                     -> false
    }
}

private fun Throwable.unwrapKnownWrapper(): Throwable {
    var cause = this
    while (cause is CompletionException || cause is ExecutionException) {
        cause = cause.cause ?: return cause
    }
    return cause
}

/**
 * See the API documentation for details.
 *
 * @param bucketName Parameter.
 * @param builder Parameter.
 * @return Return value.
 *
 * Example:
 * ```kotlin
 * val result = s3AsyncClient.createBucketAsync("demo-bucket").join()
 * // result.location().contains("demo-bucket")
 * ```
 */
fun S3AsyncClient.createBucketAsync(
    bucketName: String,
    builder: CreateBucketConfiguration.Builder.() -> Unit = {},
): CompletableFuture<CreateBucketResponse> {
    bucketName.requireNotBlank("bucketName")

    return createBucket {
        it.bucket(bucketName).createBucketConfiguration(builder)
    }
}

//
// Get Object
//

/**
 * See the API documentation for details.
 *
 * @param bucket Bucket name
 * @param key Object key
 * @param builder Parameter.
 * @return Return value.
 *
 * Example:
 * ```kotlin
 * val result = s3AsyncClient.getAsByteArrayAsync("demo-bucket", "docs/readme.txt").join()
 * // result.isNotEmpty() == true
 * ```
 */
inline fun S3AsyncClient.getAsByteArrayAsync(
    bucket: String,
    key: String,
    builder: GetObjectRequest.Builder.() -> Unit = {},
): CompletableFuture<ByteArray> {
    val request = getObjectRequest(bucket, key, builder)

    return getObject(request, AsyncResponseTransformer.toBytes())
        .thenApply { it.asByteArray() }
}

/**
 * See the API documentation for details.
 *
 * @param bucket Bucket name
 * @param key Object key
 * @param builder Parameter.
 * @return Return value.
 *
 * Example:
 * ```kotlin
 * val result = s3AsyncClient.getAsStringAsync("demo-bucket", "docs/readme.txt").join()
 * // result.contains("readme", ignoreCase = true) == true
 * ```
 */
inline fun S3AsyncClient.getAsStringAsync(
    bucket: String,
    key: String,
    builder: GetObjectRequest.Builder.() -> Unit = {},
): CompletableFuture<String> {
    val request = getObjectRequest(bucket, key, builder)

    return getObject(request, AsyncResponseTransformer.toBytes())
        .thenApply { it.asString(Charsets.UTF_8) }
}

/**
 * See the API documentation for details.
 *
 * @param bucket Bucket name
 * @param key Object key
 * @param destinationPath Parameter.
 * @param builder Parameter.
 * @return Return value.
 *
 * Example:
 * ```kotlin
 * val target = java.nio.file.Path.of("build/tmp/readme.txt")
 * val result = s3AsyncClient.getAsFileAsync("demo-bucket", "docs/readme.txt", target).join()
 * // result.eTag().isNullOrBlank() == false
 * ```
 */
inline fun S3AsyncClient.getAsFileAsync(
    bucket: String,
    key: String,
    destinationPath: Path,
    builder: GetObjectRequest.Builder.() -> Unit = {},
): CompletableFuture<GetObjectResponse> {
    val request = getObjectRequest(bucket, key, builder)
    return getObject(request, destinationPath)
}


//
// Put Object
//

/**
 * See the API documentation for details.
 *
 * @param bucket Parameter.
 * @param key Parameter.
 * @param body Parameter.
 * @param builder Parameter.
 * @return Return value.
 *
 * Example:
 * ```kotlin
 * val result = s3AsyncClient.putAsync("demo-bucket", "notes/hello.txt", "hello".toAsyncRequestBody()).join()
 * // result.eTag().isNullOrBlank() == false
 * ```
 */
inline fun S3AsyncClient.putAsync(
    bucket: String,
    key: String,
    body: AsyncRequestBody,
    builder: PutObjectRequest.Builder.() -> Unit = {},
): CompletableFuture<PutObjectResponse> {
    bucket.requireNotBlank("bucket")
    key.requireNotBlank("key")

    val request = putObjectRequest(bucket, key, builder)
    return putObject(request, body)
}

/**
 * See the API documentation for details.
 *
 * @param bucket Parameter.
 * @param key Parameter.
 * @param bytes Parameter.
 * @param builder Parameter.
 * @return Return value.
 *
 * Example:
 * ```kotlin
 * val result = s3AsyncClient.putAsByteArrayAsync("demo-bucket", "notes/data.bin", byteArrayOf(1, 2, 3)).join()
 * // result.eTag().isNullOrBlank() == false
 * ```
 */
inline fun S3AsyncClient.putAsByteArrayAsync(
    bucket: String,
    key: String,
    bytes: ByteArray,
    builder: PutObjectRequest.Builder.() -> Unit = {},
): CompletableFuture<PutObjectResponse> =
    putAsync(bucket, key, bytes.toAsyncRequestBody(), builder)

/**
 * See the API documentation for details.
 *
 * @param bucket Parameter.
 * @param key Parameter.
 * @param contents Parameter.
 * @param builder Parameter.
 * @return Return value.
 *
 * Example:
 * ```kotlin
 * val result = s3AsyncClient.putAsStringAsync("demo-bucket", "notes/hello.txt", "hello").join()
 * // result.eTag().isNullOrBlank() == false
 * ```
 */
inline fun S3AsyncClient.putAsStringAsync(
    bucket: String,
    key: String,
    contents: String,
    charset: Charset = Charsets.UTF_8,
    builder: PutObjectRequest.Builder.() -> Unit = {},
): CompletableFuture<PutObjectResponse> =
    putAsync(bucket, key, contents.toAsyncRequestBody(charset), builder)

/**
 * See the API documentation for details.
 *
 * @param bucket Parameter.
 * @param key Parameter.
 * @param file Parameter.
 * @param builder Parameter.
 * @return Return value.
 *
 * Example:
 * ```kotlin
 * val source = java.io.File("settings.gradle.kts")
 * val result = s3AsyncClient.putAsFileAsync("demo-bucket", "repo/settings.gradle.kts", source).join()
 * // result.eTag().isNullOrBlank() == false
 * ```
 */
inline fun S3AsyncClient.putAsFileAsync(
    bucket: String,
    key: String,
    file: File,
    builder: PutObjectRequest.Builder.() -> Unit = {},
): CompletableFuture<PutObjectResponse> =
    putAsync(bucket, key, file.toAsyncRequestBody(), builder)

/**
 * See the API documentation for details.
 *
 * @param bucket Parameter.
 * @param key Parameter.
 * @param path Parameter.
 * @param builder Parameter.
 * @return Return value.
 *
 * Example:
 * ```kotlin
 * val source = java.nio.file.Path.of("settings.gradle.kts")
 * val result = s3AsyncClient.putAsFileAsync("demo-bucket", "repo/settings.gradle.kts", source).join()
 * // result.eTag().isNullOrBlank() == false
 * ```
 */
inline fun S3AsyncClient.putAsFileAsync(
    bucket: String,
    key: String,
    path: Path,
    builder: PutObjectRequest.Builder.() -> Unit = {},
): CompletableFuture<PutObjectResponse> =
    putAsync(bucket, key, path.toAsyncRequestBody(), builder)

//
// Move Object
//

/**
 * See the API documentation for details.
 *
 * Note: See the referenced documentation.
 * See the API documentation for details.
 *
 * @param srcBucketName Parameter.
 * @param srcKey Parameter.
 * @param destBucketName Parameter.
 * @param destKey Parameter.
 * @return Return value.
 *
 * Example:
 * ```kotlin
 * val result = s3AsyncClient.moveObjectAsync("demo-bucket", "docs/a.txt", "demo-bucket", "archive/a.txt").join()
 * // result.copyResult.eTag().isNullOrBlank() == false
 * ```
 */
fun S3AsyncClient.moveObjectAsync(
    srcBucketName: String,
    srcKey: String,
    destBucketName: String,
    destKey: String,
): CompletableFuture<MoveObjectResult> {
    srcBucketName.requireNotBlank("srcBucketName")
    srcKey.requireNotBlank("srcKey")
    destBucketName.requireNotBlank("destBucketName")
    destKey.requireNotBlank("destKey")

    return copyObject { builder ->
        builder
            .sourceBucket(srcBucketName)
            .sourceKey(srcKey)
            .destinationBucket(destBucketName)
            .destinationKey(destKey)
    }.thenCompose { copyResponse ->
        if (copyResponse.copyObjectResult().eTag()?.isNotEmpty() == true) {
            deleteObject { builder ->
                builder.bucket(srcBucketName).key(srcKey)
            }.handle { deleteResponse, error ->
                error?.let {
                    log.warn(it) {
                        "Failed to delete source object after copy. " +
                                "Source: $srcBucketName/$srcKey, Dest: $destBucketName/$destKey"
                    }
                }
                MoveObjectResult(copyResponse.copyObjectResult(), deleteResponse)
            }
        } else {
            completableFutureOf(MoveObjectResult(copyResponse.copyObjectResult()))
        }
    }
}

/**
 * See the API documentation for details.
 *
 * Note: See the referenced documentation.
 *
 * @param copyRequestBuilder Parameter.
 * @param deleteRequestBuilder Parameter.
 * @return Return value.
 *
 * Example:
 * ```kotlin
 * val result = s3AsyncClient.moveObjectAsync(
 *     copyRequestBuilder = {
 *         sourceBucket("demo-bucket")
 *         sourceKey("docs/a.txt")
 *         destinationBucket("demo-bucket")
 *         destinationKey("archive/a.txt")
 *     },
 *     deleteRequestBuilder = {
 *         bucket("demo-bucket")
 *         key("docs/a.txt")
 *     },
 * ).join()
 * // result.isPartialSuccess == false
 * ```
 */
fun S3AsyncClient.moveObjectAsync(
    copyRequestBuilder: CopyObjectRequest.Builder.() -> Unit,
    deleteRequestBuilder: DeleteObjectRequest.Builder.() -> Unit,
): CompletableFuture<MoveObjectResult> =
    copyObject(copyRequestBuilder)
        .thenCompose { copyResponse ->
            if (copyResponse.copyObjectResult().eTag()?.isNotBlank() == true) {
                deleteObject(deleteRequestBuilder).handle { deleteResponse, error ->
                    error?.let { log.warn(it) { "Failed to delete source object after copy" } }
                    MoveObjectResult(copyResponse.copyObjectResult(), deleteResponse)
                }
            } else {
                completableFutureOf(MoveObjectResult(copyResponse.copyObjectResult()))
            }
        }

/**
 * See the API documentation for details.
 *
 * See the API documentation for details.
 *
 * @param srcBucketName Parameter.
 * @param srcKey Parameter.
 * @param destBucketName Parameter.
 * @param destKey Parameter.
 * @return Return value.
 * @throws Throwable if the operation fails.
 *
 * Example:
 * ```kotlin
 * val result = s3AsyncClient.moveObjectAtomicAsync("demo-bucket", "docs/a.txt", "demo-bucket", "archive/a.txt").join()
 * // result.isSuccess == true
 * ```
 */
fun S3AsyncClient.moveObjectAtomicAsync(
    srcBucketName: String,
    srcKey: String,
    destBucketName: String,
    destKey: String,
): CompletableFuture<MoveObjectResult> =
    moveObjectAsync(srcBucketName, srcKey, destBucketName, destKey).thenCompose { result ->
        if (result.isPartialSuccess) {
            // See the API documentation for details.
            log.warn {
                "Move partially succeeded. Attempting rollback by deleting copied object. Dest: $destBucketName/$destKey"
            }
            deleteObject { it.bucket(destBucketName).key(destKey) }
                .handle { _, rollbackError ->
                    rollbackError?.let {
                        log.error(it) {
                            "Rollback failed! Copied object may remain at destination. Dest: $destBucketName/$destKey"
                        }
                        throw IllegalStateException(
                            "Move failed and rollback also failed. Copied object remains at $destBucketName/$destKey",
                            it,
                        )
                    }
                    error("Move failed: copy succeeded but delete failed. Rollback completed.")
                }
        } else {
            completableFutureOf(result)
        }
    }
