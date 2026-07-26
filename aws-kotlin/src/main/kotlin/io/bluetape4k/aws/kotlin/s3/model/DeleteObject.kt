package io.bluetape4k.aws.kotlin.s3.model

import aws.sdk.kotlin.services.s3.model.Delete
import aws.sdk.kotlin.services.s3.model.DeleteObjectRequest
import aws.sdk.kotlin.services.s3.model.DeleteObjectsRequest
import aws.sdk.kotlin.services.s3.model.ObjectIdentifier
import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.support.requireNotEmpty

/**
 * Creates a [DeleteObjectRequest] for the S3 object at [key] in [bucket].
 *
 * ```kotlin
 * val request = deleteObjectRequestOf(
 *     bucket = "my-bucket",
 *     key = "path/to/object.txt"
 * )
 * s3Client.deleteObject(request)
 * ```
 *
 * @param bucket bucket name
 * @param key key of the object to delete
 * @param versionId specific version ID; when null, uses the latest version
 * @return the [DeleteObjectRequest]
 */
inline fun deleteObjectRequestOf(
    bucket: String,
    key: String,
    versionId: String? = null,
    crossinline builder: DeleteObjectRequest.Builder.() -> Unit = {},
): DeleteObjectRequest {
    bucket.requireNotBlank("bucket")
    key.requireNotBlank("key")

    return DeleteObjectRequest {
        this.bucket = bucket
        this.key = key
        this.versionId = versionId

        builder()
    }
}

/**
 * Creates a [DeleteObjectsRequest] for deleting the S3 objects identified by [identifiers] from [bucket].
 *
 * ```kotlin
 * val identifiers = listOf("key1", "key2").map { it.toObjectIdentifier() }
 * val request = deleteObjectsRequestOf("my-bucket", identifiers)
 * s3Client.deleteObjects(request)
 * ```
 *
 * @param bucket bucket name
 * @param identifiers identifiers of the objects to delete
 * @return the [DeleteObjectsRequest]
 */
inline fun deleteObjectsRequestOf(
    bucket: String,
    identifiers: List<ObjectIdentifier>,
    crossinline builder: DeleteObjectsRequest.Builder.() -> Unit = {},
): DeleteObjectsRequest {
    bucket.requireNotBlank("bucket")
    identifiers.requireNotEmpty("identifiers")

    return deleteObjectsRequestOf(bucket, deleteOf(identifiers), builder)
}

/**
 * Creates a [DeleteObjectsRequest] from a [Delete] object.
 *
 * ```kotlin
 * val delete = deleteOf("key1", "key2")
 * val request = deleteObjectsRequestOf("my-bucket", delete)
 * s3Client.deleteObjects(request)
 * ```
 *
 * @param bucket bucket name
 * @param delete [Delete] object containing the objects to delete
 * @return the [DeleteObjectsRequest]
 */
inline fun deleteObjectsRequestOf(
    bucket: String,
    delete: Delete,
    crossinline builder: DeleteObjectsRequest.Builder.() -> Unit = {},
): DeleteObjectsRequest {
    bucket.requireNotBlank("bucket")

    return DeleteObjectsRequest {
        this.bucket = bucket
        this.delete = delete

        builder()
    }
}
