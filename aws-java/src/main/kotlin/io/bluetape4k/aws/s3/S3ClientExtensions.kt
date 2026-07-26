package io.bluetape4k.aws.s3

import io.bluetape4k.aws.s3.model.MoveObjectResult
import io.bluetape4k.aws.s3.model.getObjectRequest
import io.bluetape4k.aws.s3.model.putObjectRequest
import io.bluetape4k.aws.s3.model.toRequestBody
import io.bluetape4k.io.exists
import io.bluetape4k.logging.KotlinLogging
import io.bluetape4k.logging.error
import io.bluetape4k.logging.warn
import io.bluetape4k.support.requireNotBlank
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.core.sync.ResponseTransformer
import software.amazon.awssdk.services.s3.S3Client
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
import java.util.concurrent.CompletionException
import java.util.concurrent.ExecutionException

private val log = KotlinLogging.logger {}

/**
 * See the API documentation for details.
 *
 * See the API documentation for details.
 * - `NoSuchBucketException`
 * See the API documentation for details.
 *
 * @param bucketName Parameter.
 * @return Return value.
 *
 * Example:
 * ```kotlin
 * val result = s3Client.existsBucket("demo-bucket")
 * // result.getOrThrow() == true
 * ```
 */
fun S3Client.existsBucket(bucketName: String): Result<Boolean> {
    bucketName.requireNotBlank("bucketName")

    return runCatching {
        headBucket { it.bucket(bucketName) }
        true
    }.recover { error ->
        when {
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
 * val result = s3Client.createBucket("demo-bucket")
 * // result.location().contains("demo-bucket")
 * ```
 */
fun S3Client.createBucket(
    bucketName: String,
    builder: CreateBucketConfiguration.Builder.() -> Unit = {},
): CreateBucketResponse {
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
 * @param responseTransformer Parameter.
 * @return Return value.
 *
 * Example:
 * ```kotlin
 * val result = s3Client.getObjectAs(
 *     bucket = "demo-bucket",
 *     key = "docs/readme.txt",
 *     responseTransformer = ResponseTransformer.toBytes(),
 * )
 * // result.asByteArray().isNotEmpty() == true
 * ```
 */
inline fun <T> S3Client.getObjectAs(
    bucket: String,
    key: String,
    builder: GetObjectRequest.Builder.() -> Unit = {},
    responseTransformer: ResponseTransformer<GetObjectResponse, T>,
): T {
    val request = getObjectRequest(bucket, key, builder)
    return getObject(request, responseTransformer)
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
 * val result = s3Client.getAsByteArray("demo-bucket", "docs/readme.txt")
 * // result.isNotEmpty() == true
 * ```
 */
inline fun S3Client.getAsByteArray(
    bucket: String,
    key: String,
    builder: GetObjectRequest.Builder.() -> Unit = {},
): ByteArray {
    val request = getObjectRequest(bucket, key, builder)
    return getObject(request, ResponseTransformer.toBytes()).asByteArray()
}

/**
 * See the API documentation for details.
 *
 * @param bucket Bucket name
 * @param key Object key
 * @param charset Parameter.
 * @param builder Parameter.
 * @return Return value.
 *
 * Example:
 * ```kotlin
 * val result = s3Client.getAsString("demo-bucket", "docs/readme.txt")
 * // result.contains("readme", ignoreCase = true) == true
 * ```
 */
inline fun S3Client.getAsString(
    bucket: String,
    key: String,
    charset: Charset = Charsets.UTF_8,
    builder: GetObjectRequest.Builder.() -> Unit = {},
): String =
    getAsByteArray(bucket, key, builder).toString(charset)

/**
 * See the API documentation for details.
 *
 * @param bucket Bucket name
 * @param key Object key
 * @param file Parameter.
 * @param builder Parameter.
 * @return Return value.
 *
 * Example:
 * ```kotlin
 * val target = java.io.File("build/tmp/readme.txt")
 * val result = s3Client.getAsFile("demo-bucket", "docs/readme.txt", target)
 * // result.eTag().isNullOrBlank() == false
 * ```
 */
inline fun S3Client.getAsFile(
    bucket: String,
    key: String,
    file: File,
    builder: GetObjectRequest.Builder.() -> Unit = {},
): GetObjectResponse {
    val request = getObjectRequest(bucket, key, builder)
    return getObject(request, ResponseTransformer.toFile(file))
}

/**
 * See the API documentation for details.
 *
 * @param bucket Bucket name
 * @param key Object key
 * @param path Parameter.
 * @param builder Parameter.
 * @return Return value.
 *
 * Example:
 * ```kotlin
 * val target = java.nio.file.Path.of("build/tmp/readme.txt")
 * val result = s3Client.getAsFile("demo-bucket", "docs/readme.txt", target)
 * // result.lastModified() != null
 * ```
 */
inline fun S3Client.getAsFile(
    bucket: String,
    key: String,
    path: Path,
    builder: GetObjectRequest.Builder.() -> Unit = {},
): GetObjectResponse {
    val request = getObjectRequest(bucket, key, builder)
    return getObject(request, ResponseTransformer.toFile(path))
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
 * val result = s3Client.put("demo-bucket", "notes/hello.txt", "hello".toRequestBody())
 * // result.eTag().isNullOrBlank() == false
 * ```
 */
inline fun S3Client.put(
    bucket: String,
    key: String,
    body: RequestBody,
    builder: PutObjectRequest.Builder.() -> Unit = {},
): PutObjectResponse {
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
 * val result = s3Client.putAsByteArray("demo-bucket", "notes/data.bin", byteArrayOf(1, 2, 3))
 * // result.eTag().isNullOrBlank() == false
 * ```
 */
inline fun S3Client.putAsByteArray(
    bucket: String,
    key: String,
    bytes: ByteArray,
    builder: PutObjectRequest.Builder.() -> Unit = {},
): PutObjectResponse =
    put(bucket, key, bytes.toRequestBody(), builder)

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
 * val result = s3Client.putAsString("demo-bucket", "notes/hello.txt", "hello")
 * // result.eTag().isNullOrBlank() == false
 * ```
 */
inline fun S3Client.putAsString(
    bucket: String,
    key: String,
    contents: String,
    charset: Charset = Charsets.UTF_8,
    builder: PutObjectRequest.Builder.() -> Unit = {},
): PutObjectResponse =
    put(bucket, key, contents.toRequestBody(charset), builder)

/**
 * See the API documentation for details.
 *
 * @param bucket Parameter.
 * @param key Parameter.
 * @param file Parameter.
 * @param builder Parameter.
 * @return Return value.
 * @throws Throwable if the operation fails.
 *
 * Example:
 * ```kotlin
 * val source = java.io.File("settings.gradle.kts")
 * val result = s3Client.putAsFile("demo-bucket", "repo/settings.gradle.kts", source)
 * // result.eTag().isNullOrBlank() == false
 * ```
 */
inline fun S3Client.putAsFile(
    bucket: String,
    key: String,
    file: File,
    builder: PutObjectRequest.Builder.() -> Unit = {},
): PutObjectResponse {
    require(file.exists()) { "File does not exist. file=$file" }

    return put(bucket, key, file.toRequestBody(), builder)
}

/**
 * See the API documentation for details.
 *
 * @param bucket Parameter.
 * @param key Parameter.
 * @param path Parameter.
 * @param builder Parameter.
 * @return Return value.
 * @throws Throwable if the operation fails.
 *
 * Example:
 * ```kotlin
 * val source = java.nio.file.Path.of("settings.gradle.kts")
 * val result = s3Client.putAsFile("demo-bucket", "repo/settings.gradle.kts", source)
 * // result.eTag().isNullOrBlank() == false
 * ```
 */
inline fun S3Client.putAsFile(
    bucket: String,
    key: String,
    path: Path,
    builder: PutObjectRequest.Builder.() -> Unit = {},
): PutObjectResponse {
    require(path.exists()) { "file does not exist. path=$path" }

    return put(bucket, key, path.toRequestBody(), builder)
}

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
 * val result = s3Client.moveObject("demo-bucket", "docs/a.txt", "demo-bucket", "archive/a.txt")
 * // result.copyResult.eTag().isNullOrBlank() == false
 * ```
 */
fun S3Client.moveObject(
    srcBucketName: String,
    srcKey: String,
    destBucketName: String,
    destKey: String,
): MoveObjectResult {
    srcBucketName.requireNotBlank("srcBucketName")
    srcKey.requireNotBlank("srcKey")
    destBucketName.requireNotBlank("destBucketName")
    destKey.requireNotBlank("destKey")

    val copyResponse =
        copyObject { builder ->
            builder
                .sourceBucket(srcBucketName)
                .sourceKey(srcKey)
                .destinationBucket(destBucketName)
                .destinationKey(destKey)
        }

    val deleteResponse =
        if (copyResponse.copyObjectResult().eTag()?.isNotBlank() == true) {
            runCatching {
                deleteObject { it.bucket(srcBucketName).key(srcKey) }
            }.onFailure { error ->
                log.warn(
                    "Failed to delete source object after copy. Source: {}/{}, Dest: {}/{}",
                    srcBucketName,
                    srcKey,
                    destBucketName,
                    destKey,
                    error,
                )
            }.getOrNull()
        } else {
            null
        }

    return MoveObjectResult(copyResponse.copyObjectResult(), deleteResponse)
}

/**
 * See the API documentation for details.
 *
 * Note: See the referenced documentation.
 *
 * @param copyRequest Parameter.
 * @param deleteRequest Parameter.
 * @return Return value.
 *
 * Example:
 * ```kotlin
 * val result = s3Client.moveObject(
 *     copyRequest = {
 *         sourceBucket("demo-bucket")
 *         sourceKey("docs/a.txt")
 *         destinationBucket("demo-bucket")
 *         destinationKey("archive/a.txt")
 *     },
 *     deleteRequest = {
 *         bucket("demo-bucket")
 *         key("docs/a.txt")
 *     },
 * )
 * // result.isPartialSuccess == false
 * ```
 */
fun S3Client.moveObject(
    copyRequest: CopyObjectRequest.Builder.() -> Unit,
    deleteRequest: DeleteObjectRequest.Builder.() -> Unit,
): MoveObjectResult {
    val copyResponse = copyObject(copyRequest)

    val deleteResponse =
        if (copyResponse.copyObjectResult().eTag()?.isNotBlank() == true) {
            runCatching {
                deleteObject(deleteRequest)
            }.onFailure { error ->
                log.warn(error) { "Failed to delete source object after copy." }
            }.getOrNull()
        } else {
            null
        }

    return MoveObjectResult(copyResponse.copyObjectResult(), deleteResponse)
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
 * val result = s3Client.moveObjectAtomic("demo-bucket", "docs/a.txt", "demo-bucket", "archive/a.txt")
 * // result.isSuccess == true
 * ```
 */
fun S3Client.moveObjectAtomic(
    srcBucketName: String,
    srcKey: String,
    destBucketName: String,
    destKey: String,
): MoveObjectResult {
    val result = moveObject(srcBucketName, srcKey, destBucketName, destKey)

    if (result.isPartialSuccess) {
        // See the API documentation for details.
        log.warn { "Move partially succeeded. Attempting rollback by deleting copied object. Dest: $destBucketName/$destKey" }

        runCatching {
            deleteObject { it.bucket(destBucketName).key(destKey) }
        }.onFailure { error ->
            log.error(error) { "Rollback failed! Copied object may remain at destination. Dest: $destBucketName/$destKey" }
            throw IllegalStateException(
                "Move failed and rollback also failed. Copied object remains at $destBucketName/$destKey",
                error
            )
        }

        error("Move failed: copy succeeded but delete failed. Rollback completed.")
    }

    return result
}
