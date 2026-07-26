package io.bluetape4k.aws.kotlin.s3

import aws.sdk.kotlin.services.s3.S3Client
import aws.sdk.kotlin.services.s3.getBucketPolicy
import aws.sdk.kotlin.services.s3.model.GetBucketPolicyRequest
import aws.sdk.kotlin.services.s3.model.GetObjectAclRequest
import aws.sdk.kotlin.services.s3.model.GetObjectAclResponse
import aws.sdk.kotlin.services.s3.model.GetObjectRequest
import aws.sdk.kotlin.services.s3.model.GetObjectResponse
import aws.sdk.kotlin.services.s3.model.GetObjectRetentionRequest
import aws.sdk.kotlin.services.s3.model.GetObjectRetentionResponse
import aws.sdk.kotlin.services.s3.model.HeadObjectRequest
import aws.sdk.kotlin.services.s3.presigners.presignGetObject
import aws.smithy.kotlin.runtime.ServiceException
import kotlinx.coroutines.CancellationException
import aws.smithy.kotlin.runtime.content.ByteStream
import aws.smithy.kotlin.runtime.content.decodeToString
import aws.smithy.kotlin.runtime.content.toByteArray
import aws.smithy.kotlin.runtime.content.writeToFile
import aws.smithy.kotlin.runtime.content.writeToOutputStream
import aws.smithy.kotlin.runtime.http.request.HttpRequest
import aws.smithy.kotlin.runtime.http.response.statusCode
import io.bluetape4k.aws.kotlin.s3.model.getObjectAclRequestOf
import io.bluetape4k.aws.kotlin.s3.model.getObjectRequestOf
import io.bluetape4k.aws.kotlin.s3.model.getObjectRetentionRequestOf
import io.bluetape4k.aws.kotlin.s3.model.headObjectRequestOf
import io.bluetape4k.coroutines.flow.async
import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.support.requireNotEmpty
import kotlinx.coroutines.flow.DEFAULT_CONCURRENCY
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.channelFlow
import java.io.OutputStream
import java.nio.file.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import io.bluetape4k.coroutines.flow.collect as collectAsync

/**
 * Checks whether the object at [key] exists in [bucket].
 *
 * Only a missing object (`NoSuchKey`/`NotFound`/HTTP `404`) is normalized to `false`.
 * Other failures, including authentication and network errors, are propagated.
 *
 * ```
 * val exists = s3Client.existsObject("bucket-name", "key")
 * ```
 */
suspend inline fun S3Client.existsObject(
    bucket: String,
    key: String,
    crossinline builder: HeadObjectRequest.Builder.() -> Unit = {},
): Boolean {
    val request = headObjectRequestOf(bucket, key, builder = builder)
    return try {
        headObject(request)
        true
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        if (e.isMissingObjectError()) false else throw e
    }
}

/**
 * Retrieves the object at [key] from [bucketName].
 *
 * ```
 * val response = s3Client.get("bucket-name", "key")
 * ```
 * @param bucketName bucket name
 * @param key object key
 * @param builder configures the [GetObjectRequest] through [GetObjectRequest.Builder]
 * @return the [GetObjectResponse]
 */
suspend inline fun S3Client.get(
    bucketName: String,
    key: String,
    crossinline builder: GetObjectRequest.Builder.() -> Unit = {},
): GetObjectResponse {
    val request = getObjectRequestOf(bucketName, key, builder = builder)
    return getObject(request) { it }
}


/**
 * Retrieves the object at [key] from [bucketName] and transforms it with [responseTransform].
 *
 * ```
 * val objectText = s3Client.getAs("bucket-name", "key") { it.readText() }
 * ```
 * @param bucketName bucket name
 * @param key object key
 * @param requestBuilder configures the [GetObjectRequest] through [GetObjectRequest.Builder]
 * @param responseTransform transforms the [GetObjectResponse] into the desired type
 * @return the value produced by [responseTransform]
 */
suspend inline fun <T> S3Client.getAs(
    bucketName: String,
    key: String,
    crossinline requestBuilder: GetObjectRequest.Builder.() -> Unit = {},
    noinline responseTransform: suspend (GetObjectResponse) -> T,
): T {
    val request = getObjectRequestOf(bucketName, key, builder = requestBuilder)
    return getObject(request, responseTransform)
}

/**
 * Retrieves the object at [key] from [bucketName] as a byte array.
 *
 * ```
 * val bytes = s3Client.getAsByteArray("bucket-name", "key")
 * ```
 *
 * @param bucketName bucket name
 * @param key object key
 * @param requestBuilder configures the [GetObjectRequest] through [GetObjectRequest.Builder]
 * @return the object bytes
 */
suspend inline fun S3Client.getAsByteArray(
    bucketName: String,
    key: String,
    crossinline requestBuilder: GetObjectRequest.Builder.() -> Unit = {},
): ByteArray? {
    return getAs(bucketName, key, requestBuilder) {
        it.body?.toByteArray()
    }
}

/**
 * Retrieves the object at [key] from [bucketName] as a string.
 *
 * ```
 * val text = s3Client.getAsString("bucket-name", "key")
 * ```
 *
 * @param bucketName bucket name
 * @param key object key
 * @param requestBuilder configures the [GetObjectRequest] through [GetObjectRequest.Builder]
 * @return the object content as a string
 */
suspend inline fun S3Client.getAsString(
    bucketName: String,
    key: String,
    crossinline requestBuilder: GetObjectRequest.Builder.() -> Unit = {},
): String? {
    return getAs(bucketName, key, requestBuilder) {
        it.body?.decodeToString()
    }
}

/**
 * Retrieves the object at [key] from [bucketName] and stores it in [file].
 *
 * ```
 * val file = File("test.txt")
 * s3Client.getAsFile("bucket-name", "key", file)
 * ```
 *
 * @param bucketName bucket name
 * @param key object key
 * @param file destination file
 * @param requestBuilder configures the [GetObjectRequest] through [GetObjectRequest.Builder]
 * @return the number of bytes written
 */
suspend inline fun S3Client.getAsFile(
    bucketName: String,
    key: String,
    file: java.io.File,
    crossinline requestBuilder: GetObjectRequest.Builder.() -> Unit = {},
): Long {
    return getAs(bucketName, key, requestBuilder) {
        it.body?.writeToFile(file) ?: -1L
    }
}

/**
 * Retrieves the object at [key] from [bucketName] and stores it at [filePath].
 *
 * ```
 * val filePath = Paths.get("test.txt")
 * s3Client.getAsFile("bucket-name", "key", filePath)
 * ```
 *
 * @param bucketName bucket name
 * @param key object key
 * @param filePath destination file path
 * @param requestBuilder configures the [GetObjectRequest] through [GetObjectRequest.Builder]
 * @return the number of bytes written
 */
suspend inline fun S3Client.getAsFile(
    bucketName: String,
    key: String,
    filePath: Path,
    crossinline requestBuilder: GetObjectRequest.Builder.() -> Unit = {},
): Long {
    return getAs(bucketName, key, requestBuilder) {
        it.body?.writeToFile(filePath) ?: -1L
    }
}

/**
 * Retrieves the object at [key] from [bucketName] and writes it to [outputStream].
 * Use [requestBuilder] to configure the [GetObjectRequest].
 *
 * ```
 * val outputStream = ByteArrayOutputStream()
 * s3Client.getAsOutputStream("bucket-name", "key", outputStream)
 * ```
 *
 * @param bucketName bucket name
 * @param key object key
 * @param outputStream destination stream
 * @param requestBuilder configures the [GetObjectRequest] through [GetObjectRequest.Builder]
 * @return the number of bytes written to [outputStream]
 * @see [writeToOutputStream]
 */
suspend inline fun S3Client.getAsOutputStream(
    bucketName: String,
    key: String,
    outputStream: OutputStream,
    crossinline requestBuilder: GetObjectRequest.Builder.() -> Unit = {},
) {
    getAs(bucketName, key, requestBuilder) {
        it.body?.writeToOutputStream(outputStream)
    }
}

/**
 * Downloads multiple S3 objects concurrently.
 *
 * ```
 * val responses = s3Client.getAll(request1, request2, request3).toList()
 * ```
 *
 * @param concurrency number of requests to process concurrently
 * @param getObjectRequests object requests to download
 * @return a [Flow] of [GetObjectResponse] values
 */
fun S3Client.getAll(
    concurrency: Int = DEFAULT_CONCURRENCY,
    vararg getObjectRequests: GetObjectRequest,
): Flow<GetObjectResponse> = channelFlow {
    getObjectRequests
        .asFlow()
        .async { request ->
            getObject(request) { response ->
                // Read the body in the block because getObject closes its stream when the block completes.
                val bodyBytes = response.body?.toByteArray()
                response.copy { body = bodyBytes?.let { ByteStream.fromBytes(it) } }
            }
        }
        .collectAsync(concurrency) { response ->
            send(response)
        }
}

/**
 * Creates a presigned URL for an S3 object.
 *
 * ```
 * val url = s3Client.presignGetObject("bucket-name", "key")
 * ```
 *
 * @param bucketName bucket name
 * @param key object key
 * @param duration validity period of the presigned URL
 * @param requestBuilder configures the [GetObjectRequest] through [GetObjectRequest.Builder]
 */
suspend inline fun S3Client.presignGetObject(
    bucketName: String,
    key: String,
    duration: Duration = 5.seconds,
    crossinline requestBuilder: GetObjectRequest.Builder.() -> Unit = {},
): HttpRequest {
    val request = getObjectRequestOf(bucketName, key, builder = requestBuilder)
    return presignGetObject(request, duration)
}

/**
 * Retrieves the object ACL for [key] in [bucketName].
 *
 * ```
 * val response = s3Client.getObjectAcl("bucket-name", "key")
 * ```
 * @param bucketName bucket name
 * @param key object key
 * @return the [GetObjectAclResponse]
 */
suspend inline fun S3Client.getObjectAcl(
    bucketName: String,
    key: String,
    versionId: String? = null,
    crossinline requestBuilder: GetObjectAclRequest.Builder.() -> Unit = {},
): GetObjectAclResponse {
    bucketName.requireNotBlank("bucketName")
    key.requireNotBlank("key")

    return getObjectAcl(getObjectAclRequestOf(bucketName, key, versionId, requestBuilder))
}

/**
 * Retrieves object ACLs for multiple keys in [bucketName] concurrently.
 *
 * ```
 * val responses = s3Client.getObjectsAcl("bucket-name", "key1", "key2", "key3").toList()
 * ```
 *
 * @param bucketName bucket name
 * @param keys object keys
 * @return a [Flow] of [GetObjectAclResponse] values
 */
fun S3Client.getObjectsAcl(bucketName: String, vararg keys: String): Flow<GetObjectAclResponse> {
    bucketName.requireNotBlank("bucketName")
    keys.requireNotEmpty("keys")

    return keys.asFlow()
        .async { key ->
            getObjectAcl(bucketName, key)
        }
}

/**
 * Retrieves object retention settings for [key] in [bucketName].
 *
 * ```
 * val response = s3Client.getObjectRetention("bucket-name", "key")
 * ```
 * @param bucketName bucket name
 * @param key object key
 * @param builder configures the [GetObjectRetentionRequest] through [GetObjectRetentionRequest.Builder]
 * @return the [GetObjectRetentionResponse]
 */
suspend inline fun S3Client.getObjectRetention(
    bucketName: String,
    key: String,
    versionId: String? = null,
    crossinline builder: GetObjectRetentionRequest.Builder.() -> Unit = {},
): GetObjectRetentionResponse {
    val request = getObjectRetentionRequestOf(bucketName, key, versionId, builder)
    return getObjectRetention(request)
}

/**
 * Returns the policy document for [bucketName], or `null` when the policy does
 * not exist.
 *
 * ```
 * val policy = s3Client.tryGetBucketPolicy("bucket-name")
 * ```
 *
 * Only documented missing-policy errors are normalized to `null`. Access
 * failures, throttling, retryable service failures, and unknown SDK errors are
 * propagated to the caller.
 *
 * @param bucketName bucket name.
 * @param expectedBucketOwner expected bucket owner.
 * @return bucket policy document, or `null` when the policy does not exist.
 */
suspend inline fun S3Client.tryGetBucketPolicy(
    bucketName: String,
    expectedBucketOwner: String? = null,
    crossinline builder: GetBucketPolicyRequest.Builder.() -> Unit = {},
): String? {
    return try {
        getBucketPolicy {
            this.bucket = bucketName
            this.expectedBucketOwner = expectedBucketOwner
            builder()
        }.policy
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        if (e.isMissingBucketPolicyError()) null else throw e
    }
}

@PublishedApi
internal fun Throwable.isMissingBucketPolicyError(): Boolean {
    val serviceError = this as? ServiceException ?: return false
    val errorCode = serviceError.sdkErrorMetadata.errorCode
    val statusCode = serviceError.sdkErrorMetadata.protocolResponse.statusCode()?.value
    return errorCode in setOf("NoSuchBucketPolicy", "NoSuchBucket", "NotFound") || statusCode == 404
}

@PublishedApi
internal fun Throwable.isMissingObjectError(): Boolean {
    val serviceError = this as? ServiceException ?: return false
    val errorCode = serviceError.sdkErrorMetadata.errorCode
    val statusCode = serviceError.sdkErrorMetadata.protocolResponse.statusCode()?.value
    return errorCode in setOf("NoSuchKey", "NotFound") || statusCode == 404
}
