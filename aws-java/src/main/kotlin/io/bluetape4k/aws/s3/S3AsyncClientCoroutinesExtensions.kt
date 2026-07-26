package io.bluetape4k.aws.s3

import io.bluetape4k.aws.s3.model.MoveObjectResult
import io.bluetape4k.support.requireNotBlank
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.future.await
import software.amazon.awssdk.core.async.AsyncRequestBody
import software.amazon.awssdk.services.s3.S3AsyncClient
import software.amazon.awssdk.services.s3.model.CopyObjectRequest
import software.amazon.awssdk.services.s3.model.CreateBucketConfiguration
import software.amazon.awssdk.services.s3.model.CreateBucketResponse
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.GetObjectResponse
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import software.amazon.awssdk.services.s3.model.PutObjectResponse
import software.amazon.awssdk.services.s3.model.S3Object
import java.io.File
import java.nio.charset.Charset
import java.nio.file.Path

/**
 * See the API documentation for details.
 *
 * @param bucketName Parameter.
 * @return Return value.
 *
 * Example:
 * ```kotlin
 * val result = s3AsyncClient.existsBucket("demo-bucket")
 * // result == true
 * ```
 */
suspend inline fun S3AsyncClient.existsBucket(bucketName: String): Boolean =
    existsBucketAsync(bucketName).await()

/**
 * See the API documentation for details.
 *
 * @param bucketName Parameter.
 * @param builder Parameter.
 * @return Return value.
 *
 * Example:
 * ```kotlin
 * val result = s3AsyncClient.createBucket("demo-bucket")
 * // result.location().contains("demo-bucket")
 * ```
 */
suspend fun S3AsyncClient.createBucket(
    bucketName: String,
    builder: CreateBucketConfiguration.Builder.() -> Unit = {},
): CreateBucketResponse =
    createBucketAsync(bucketName, builder).await()

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
 * val result = s3AsyncClient.getAsByteArray("demo-bucket", "docs/readme.txt")
 * // result.isNotEmpty() == true
 * ```
 */
suspend inline fun S3AsyncClient.getAsByteArray(
    bucket: String,
    key: String,
    builder: GetObjectRequest.Builder.() -> Unit = {},
): ByteArray =
    getAsByteArrayAsync(bucket, key, builder).await()

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
 * val result = s3AsyncClient.getAsString("demo-bucket", "docs/readme.txt")
 * // result.contains("readme", ignoreCase = true) == true
 * ```
 */
suspend inline fun S3AsyncClient.getAsString(
    bucket: String,
    key: String,
    builder: GetObjectRequest.Builder.() -> Unit = {},
): String =
    getAsStringAsync(bucket, key, builder).await()

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
 * val result = s3AsyncClient.getAsFile("demo-bucket", "docs/readme.txt", target)
 * // result.eTag().isNullOrBlank() == false
 * ```
 */
suspend inline fun S3AsyncClient.getAsFile(
    bucket: String,
    key: String,
    destinationPath: Path,
    builder: GetObjectRequest.Builder.() -> Unit = {},
): GetObjectResponse =
    getAsFileAsync(bucket, key, destinationPath, builder).await()

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
 * val result = s3AsyncClient.put("demo-bucket", "notes/hello.txt", "hello".toAsyncRequestBody())
 * // result.eTag().isNullOrBlank() == false
 * ```
 */
suspend inline fun S3AsyncClient.put(
    bucket: String,
    key: String,
    body: AsyncRequestBody,
    builder: PutObjectRequest.Builder.() -> Unit = {},
): PutObjectResponse =
    putAsync(bucket, key, body, builder).await()

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
 * val result = s3AsyncClient.putAsByteArray("demo-bucket", "notes/data.bin", byteArrayOf(1, 2, 3))
 * // result.eTag().isNullOrBlank() == false
 * ```
 */
suspend inline fun S3AsyncClient.putAsByteArray(
    bucket: String,
    key: String,
    bytes: ByteArray,
    builder: PutObjectRequest.Builder.() -> Unit = {},
): PutObjectResponse =
    putAsByteArrayAsync(bucket, key, bytes, builder).await()

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
 * val result = s3AsyncClient.putAsString("demo-bucket", "notes/hello.txt", "hello")
 * // result.eTag().isNullOrBlank() == false
 * ```
 */
suspend inline fun S3AsyncClient.putAsString(
    bucket: String,
    key: String,
    contents: String,
    charset: Charset = Charsets.UTF_8,
    builder: PutObjectRequest.Builder.() -> Unit = {},
): PutObjectResponse =
    putAsStringAsync(bucket, key, contents, charset, builder).await()

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
 * val result = s3AsyncClient.putAsFile("demo-bucket", "repo/settings.gradle.kts", source)
 * // result.eTag().isNullOrBlank() == false
 * ```
 */
suspend inline fun S3AsyncClient.putAsFile(
    bucket: String,
    key: String,
    file: File,
    builder: PutObjectRequest.Builder.() -> Unit = {},
): PutObjectResponse =
    putAsFileAsync(bucket, key, file, builder).await()

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
 * val result = s3AsyncClient.putAsFile("demo-bucket", "repo/settings.gradle.kts", source)
 * // result.eTag().isNullOrBlank() == false
 * ```
 */
suspend inline fun S3AsyncClient.putAsFile(
    bucket: String,
    key: String,
    path: Path,
    builder: PutObjectRequest.Builder.() -> Unit = {},
): PutObjectResponse =
    putAsFileAsync(bucket, key, path, builder).await()

/**
 * Returns a cold [Flow] that emits every [S3Object] in [bucket].
 *
 * The flow calls `ListObjectsV2` lazily when collected and follows
 * `nextContinuationToken` until S3 reports that the result is no longer
 * truncated. Use [prefix] to restrict the emitted objects to a key prefix.
 *
 * @param bucket bucket name to list
 * @param prefix optional key prefix filter
 * @return cold [Flow] of all listed [S3Object] values
 *
 * Example:
 * ```kotlin
 * s3AsyncClient.listAllObjects("demo-bucket", prefix = "logs/")
 *     .collect { println(it.key()) }
 * ```
 */
fun S3AsyncClient.listAllObjects(
    bucket: String,
    prefix: String? = null,
): Flow<S3Object> {
    bucket.requireNotBlank("bucket")

    return flow {
        var continuationToken: String? = null

        do {
            val response = listObjectsV2 { builder ->
                builder.bucket(bucket)
                prefix?.let(builder::prefix)
                continuationToken?.let(builder::continuationToken)
            }.await()

            response.contents().orEmpty().forEach { emit(it) }

            val isTruncated = response.isTruncated == true
            continuationToken = response.nextContinuationToken()
            check(!isTruncated || !continuationToken.isNullOrBlank()) {
                "S3 ListObjectsV2 response for bucket=$bucket was truncated without nextContinuationToken"
            }
        } while (isTruncated)
    }
}

/**
 * See the API documentation for details.
 *
 * Note: See the referenced documentation.
 *
 * @param srcBucketName Parameter.
 * @param srcKey Parameter.
 * @param destBucketName Parameter.
 * @param destKey Parameter.
 * @return Return value.
 *
 * Example:
 * ```kotlin
 * val result = s3AsyncClient.moveObject("demo-bucket", "docs/a.txt", "demo-bucket", "archive/a.txt")
 * // result.copyResult.eTag().isNullOrBlank() == false
 * ```
 */
suspend fun S3AsyncClient.moveObject(
    srcBucketName: String,
    srcKey: String,
    destBucketName: String,
    destKey: String,
): MoveObjectResult =
    moveObjectAsync(srcBucketName, srcKey, destBucketName, destKey).await()

/**
 * See the API documentation for details.
 *
 * Note: See the referenced documentation.
 *
 * @param copyObjectRequest Parameter.
 * @param deleteObjectRequest Parameter.
 * @return Return value.
 *
 * Example:
 * ```kotlin
 * val result = s3AsyncClient.moveObject(
 *     copyObjectRequest = {
 *         sourceBucket("demo-bucket")
 *         sourceKey("docs/a.txt")
 *         destinationBucket("demo-bucket")
 *         destinationKey("archive/a.txt")
 *     },
 *     deleteObjectRequest = {
 *         bucket("demo-bucket")
 *         key("docs/a.txt")
 *     }
 * )
 * // result.isPartialSuccess == false
 * ```
 */
suspend fun S3AsyncClient.moveObject(
    copyObjectRequest: CopyObjectRequest.Builder.() -> Unit,
    deleteObjectRequest: DeleteObjectRequest.Builder.() -> Unit,
): MoveObjectResult =
    moveObjectAsync(copyObjectRequest, deleteObjectRequest).await()

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
 * val result = s3AsyncClient.moveObjectAtomic("demo-bucket", "docs/a.txt", "demo-bucket", "archive/a.txt")
 * // result.isSuccess == true
 * ```
 */
suspend fun S3AsyncClient.moveObjectAtomic(
    srcBucketName: String,
    srcKey: String,
    destBucketName: String,
    destKey: String,
): MoveObjectResult =
    moveObjectAtomicAsync(srcBucketName, srcKey, destBucketName, destKey).await()
