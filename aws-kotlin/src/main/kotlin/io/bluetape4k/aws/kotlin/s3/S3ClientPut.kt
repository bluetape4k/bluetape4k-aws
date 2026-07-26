package io.bluetape4k.aws.kotlin.s3

import aws.sdk.kotlin.services.s3.S3Client
import aws.sdk.kotlin.services.s3.model.ObjectCannedAcl
import aws.sdk.kotlin.services.s3.model.PutObjectRequest
import aws.sdk.kotlin.services.s3.model.PutObjectResponse
import aws.smithy.kotlin.runtime.content.ByteStream
import aws.smithy.kotlin.runtime.content.fromFile
import io.bluetape4k.aws.kotlin.s3.model.putObjectRequestOf
import io.bluetape4k.coroutines.flow.async
import io.bluetape4k.io.exists
import kotlinx.coroutines.flow.DEFAULT_CONCURRENCY
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.flow
import java.io.File
import java.nio.file.Path
import io.bluetape4k.coroutines.flow.collect as collectAsync

/**
 * Stores an object at [key] in [bucketName].
 *
 * ```kotlin
 * val response = s3Client.put("bucket-name", "key") {
 *    this.body = ByteStream.fromString("Hello, World!")
 *    this.contentType = "text/plain"
 *    this.metadata = mapOf("key" to "value")
 *    this.cacheControl = "max-age=3600"
 * }
 * ```
 * @param bucketName bucket name
 * @param key object key
 * @param builder configures the [PutObjectRequest] through [PutObjectRequest.Builder]
 * @return the [PutObjectResponse]
 */
suspend inline fun S3Client.put(
    bucketName: String,
    key: String,
    body: ByteStream? = null,
    metadata: Map<String, String>? = null,
    acl: ObjectCannedAcl? = null,
    contentType: String? = null,
    crossinline builder: PutObjectRequest.Builder.() -> Unit = {},
): PutObjectResponse {
    val request = putObjectRequestOf(bucketName, key, body, metadata, acl, contentType, builder)
    return putObject(request)
}

/**
 * Stores [bytes] at [key] in [bucketName].
 *
 * ```kotlin
 * val response = s3Client.putFromByteArray("bucket-name", "key", byteArrayOf(1, 2, 3, 4))
 * ```
 *
 * @param bucketName bucket name
 * @param key object key
 * @param bytes bytes to store
 * @param metadata metadata
 * @param builder configures the [PutObjectRequest] through [PutObjectRequest.Builder]
 * @return the [PutObjectResponse]
 */
suspend inline fun S3Client.putFromByteArray(
    bucketName: String,
    key: String,
    bytes: ByteArray,
    metadata: Map<String, String>? = null,
    acl: ObjectCannedAcl? = null,
    contentType: String? = null,
    crossinline builder: PutObjectRequest.Builder.() -> Unit = {},
): PutObjectResponse =
    put(bucketName, key, ByteStream.fromBytes(bytes), metadata, acl, contentType, builder)

/**
 * Stores [text] at [key] in [bucketName].
 *
 * ```kotlin
 * val response = s3Client.putFromString("bucket-name", "key", "Hello World!")
 * ```
 * @param bucketName bucket name
 * @param key object key
 * @param text text to store
 * @param metadata metadata
 * @param builder configures the [PutObjectRequest] through [PutObjectRequest.Builder]
 * @return the [PutObjectResponse]
 */
suspend inline fun S3Client.putFromString(
    bucketName: String,
    key: String,
    text: String,
    metadata: Map<String, String>? = null,
    acl: ObjectCannedAcl? = null,
    contentType: String? = null,
    crossinline builder: PutObjectRequest.Builder.() -> Unit = {},
): PutObjectResponse =
    put(bucketName, key, ByteStream.fromString(text), metadata, acl, contentType, builder)

/**
 * Stores the contents of [file] at [key] in [bucketName].
 *
 * ```kotlin
 * val response = s3Client.putFromFile("bucket-name", "key", File("test.txt"))
 * ```
 * @param bucketName bucket name
 * @param key object key
 * @param file file to store
 * @param metadata metadata
 * @param builder configures the [PutObjectRequest] through [PutObjectRequest.Builder]
 * @return the [PutObjectResponse]
 * @throws IllegalArgumentException when the file does not exist
 * @see putFromPath
 */
suspend inline fun S3Client.putFromFile(
    bucketName: String,
    key: String,
    file: File,
    metadata: Map<String, String>? = null,
    acl: ObjectCannedAcl? = null,
    contentType: String? = null,
    crossinline builder: PutObjectRequest.Builder.() -> Unit = {},
): PutObjectResponse {
    require(file.exists()) { "File not found: $file" }

    return put(bucketName, key, ByteStream.fromFile(file), metadata, acl, contentType, builder)
}

/**
 * Stores the file at [filePath] under [key] in [bucketName].
 *
 * ```kotlin
 * val response = s3Client.putFromPath("bucket-name", "key", Paths.get("test.txt"))
 * ```
 * @param bucketName bucket name
 * @param key object key
 * @param filePath path of the file to store
 * @param metadata metadata
 * @param builder configures the [PutObjectRequest] through [PutObjectRequest.Builder]
 * @return the [PutObjectResponse]
 * @throws IllegalArgumentException when the file does not exist
 * @see putFromFile
 */
suspend inline fun S3Client.putFromPath(
    bucketName: String,
    key: String,
    filePath: Path,
    metadata: Map<String, String>? = null,
    acl: ObjectCannedAcl? = null,
    contentType: String? = null,
    crossinline builder: PutObjectRequest.Builder.() -> Unit = {},
): PutObjectResponse {
    require(filePath.exists()) { "File not found: $filePath" }

    return put(bucketName, key, ByteStream.fromFile(filePath.toFile()), metadata, acl, contentType, builder)
}

/**
 * Stores multiple objects concurrently.
 *
 * ```kotlin
 * val response = s3Client.putAll(concurrency = 10, putRequest1, putRequest2, putRequest3).toList()
 * ```
 *
 * @param concurrency number of requests to execute concurrently
 * @param putRequests [PutObjectRequest] instances to execute
 * @return the [PutObjectResponse] instances
 * @see put
 */
fun S3Client.putAll(
    concurrency: Int = DEFAULT_CONCURRENCY,
    vararg putRequests: PutObjectRequest,
): Flow<PutObjectResponse> = flow {
    val asyncFlow = putRequests
        .asFlow()
        .async { request ->
            putObject(request)
        }

    asyncFlow.collectAsync(concurrency) { putResponse -> emit(putResponse) }
}
