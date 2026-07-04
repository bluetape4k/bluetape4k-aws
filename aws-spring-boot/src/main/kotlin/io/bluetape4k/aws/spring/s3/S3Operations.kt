package io.bluetape4k.aws.spring.s3

import kotlinx.coroutines.flow.Flow
import software.amazon.awssdk.services.s3.model.DeleteObjectResponse
import software.amazon.awssdk.services.s3.model.PutObjectResponse
import software.amazon.awssdk.services.s3.model.S3Object
import java.net.URL
import java.nio.charset.Charset
import java.time.Duration

/**
 * Coroutine-based S3 operations contract for Spring applications.
 *
 * ```kotlin
 * import java.net.URL
 *
 * class DocumentStorage(private val s3: S3Operations) {
 *
 *     suspend fun save(bucket: String, key: String, contents: String) {
 *         s3.upload(bucket, key, contents, contentType = "text/plain")
 *     }
 *
 *     suspend fun read(bucket: String, key: String): String =
 *         s3.downloadText(bucket, key)
 *
 *     fun presignedUpload(bucket: String, key: String): URL =
 *         s3.presignPut(bucket, key, contentType = "application/json")
 * }
 * ```
 */
interface S3Operations {

    /**
     * Returns `true` when [bucket] exists, or `false` when it does not.
     */
    suspend fun existsBucket(bucket: String): Boolean

    /**
     * Uploads [bytes] to the [bucket]/[key] object.
     *
     * Sets `PutObjectRequest.contentType` when [contentType] is not null.
     */
    suspend fun upload(
        bucket: String,
        key: String,
        bytes: ByteArray,
        contentType: String? = null,
    ): PutObjectResponse

    /**
     * Encodes the [contents] string with [charset] and uploads it to the [bucket]/[key] object.
     *
     * The default [contentType] includes `text/plain` and [charset].
     */
    suspend fun upload(
        bucket: String,
        key: String,
        contents: String,
        charset: Charset = Charsets.UTF_8,
        contentType: String? = "text/plain; charset=${charset.name()}",
    ): PutObjectResponse

    /**
     * Downloads the [bucket]/[key] object content as a [ByteArray].
     */
    suspend fun downloadBytes(bucket: String, key: String): ByteArray

    /**
     * Decodes the [bucket]/[key] object content with [charset] and returns it as a string.
     */
    suspend fun downloadText(
        bucket: String,
        key: String,
        charset: Charset = Charsets.UTF_8,
    ): String

    /**
     * Deletes the [bucket]/[key] object.
     */
    suspend fun delete(bucket: String, key: String): DeleteObjectResponse

    /**
     * Fetches one page of objects in [bucket].
     *
     * [maxKeys] must be within the AWS S3 `ListObjectsV2` range of 1..1000.
     */
    suspend fun listPage(
        bucket: String,
        prefix: String? = null,
        maxKeys: Int = 1_000,
        continuationToken: String? = null,
    ): S3ListPage

    /**
     * Provides objects in [bucket] as a cold [Flow].
     *
     * Page retrieval starts when the flow is collected, and subsequent pages are requested in [pageSize] units.
     */
    fun listFlow(
        bucket: String,
        prefix: String? = null,
        pageSize: Int = 1_000,
    ): Flow<S3Object>

    /**
     * Exposes the [bucket]/[key] object as a Spring `Resource`.
     */
    fun resource(bucket: String, key: String): S3Resource

    /**
     * Creates a presigned GET URL for downloading the [bucket]/[key] object.
     *
     * Uses `bluetape4k.aws.s3.presign.duration` when [duration] is null.
     */
    fun presignGet(
        bucket: String,
        key: String,
        duration: Duration? = null,
    ): URL

    /**
     * Creates a presigned PUT URL for uploading the [bucket]/[key] object.
     *
     * When [contentType] is specified, the signed `PutObjectRequest` includes the same value.
     */
    fun presignPut(
        bucket: String,
        key: String,
        duration: Duration? = null,
        contentType: String? = null,
    ): URL
}
