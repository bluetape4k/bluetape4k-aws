package io.bluetape4k.aws.kotlin.s3

import aws.sdk.kotlin.services.s3.S3Client
import aws.sdk.kotlin.services.s3.deleteObjects
import aws.sdk.kotlin.services.s3.model.Delete
import aws.sdk.kotlin.services.s3.model.DeleteObjectsRequest
import aws.sdk.kotlin.services.s3.model.DeleteObjectsResponse
import io.bluetape4k.aws.kotlin.s3.model.deleteOf
import io.bluetape4k.logging.debug
import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.support.requireNotEmpty

/**
 * Deletes multiple objects from an S3 bucket.
 *
 * ```
 * val response = s3Client.deleteAll("bucket", "key-1", "key-2")
 * ```
 *
 * @param bucket name of the bucket containing the objects
 * @param keys keys of the objects to delete
 * @return the [DeleteObjectsResponse]
 */
suspend inline fun S3Client.deleteAll(
    bucket: String,
    vararg keys: String,
    crossinline builder: Delete.Builder.() -> Unit = {},
): DeleteObjectsResponse {
    bucket.requireNotBlank("bucketName")

    return deleteObjects {
        this.bucket = bucket
        this.delete = deleteOf(*keys, builder = builder)
    }
}

/**
 * Deletes the objects identified by [keys] from S3 [bucket].
 *
 * ```
 * val response = s3Client.deleteAll("bucket", listOf("key-1", "key-2"))
 * ```
 *
 * @param bucket name of the bucket containing the objects
 * @param keys keys of the objects to delete
 * @return the [DeleteObjectsResponse]
 */
suspend inline fun S3Client.deleteAll(
    bucket: String,
    keys: Collection<String>,
    crossinline bulider: Delete.Builder.() -> Unit = {},
): DeleteObjectsResponse {
    bucket.requireNotBlank("bucketName")
    keys.requireNotEmpty("keys")
    log.debug { "Delete all objects in bucket=$bucket" }

    return deleteObjects {
        this.bucket = bucket
        this.delete = deleteOf(keys, builder = bulider)
    }
}

/**
 * Deletes multiple objects from an S3 bucket.
 *
 * ```
 * val keys = listOf("key-1", "key-2")
 * val response = s3Client.deleteAll("bucket-1") {
 *      delete {
 *             quiet = true
 *             this.objects = keys.map { it.toObjectIdentifier() }
 *      }
 * }
 * ```
 *
 * @param bucket name of the bucket containing the objects
 * @param builder configures the [DeleteObjectsRequest] through [DeleteObjectsRequest.Builder]
 * @return the [DeleteObjectsResponse]
 */
suspend inline fun S3Client.deleteAll(
    bucket: String,
    crossinline builder: DeleteObjectsRequest.Builder.() -> Unit,
): DeleteObjectsResponse {
    bucket.requireNotBlank("bucketName")

    return deleteObjects {
        this.bucket = bucket
        builder()
    }
}
