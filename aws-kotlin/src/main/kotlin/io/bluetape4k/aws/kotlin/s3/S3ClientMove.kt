package io.bluetape4k.aws.kotlin.s3

import aws.sdk.kotlin.services.s3.S3Client
import aws.sdk.kotlin.services.s3.copyObject
import aws.sdk.kotlin.services.s3.deleteObject
import aws.sdk.kotlin.services.s3.model.CopyObjectRequest
import aws.sdk.kotlin.services.s3.model.CopyObjectResponse
import aws.sdk.kotlin.services.s3.model.DeleteObjectRequest

/**
 * Moves an S3 object.
 *
 * ```kotlin
 * val response = s3Client.move("src-bucket", "src-key", "dest-bucket", "dest-key")
 * ```
 *
 * @param srcBucket bucket containing the source object
 * @param srcKey source object key
 * @param destBucket destination bucket
 * @param destKey destination object key
 * @return the [CopyObjectResponse]
 */
suspend inline fun S3Client.move(
    srcBucket: String,
    srcKey: String,
    destBucket: String,
    destKey: String,
    crossinline builder: CopyObjectRequest.Builder.() -> Unit = {},
): CopyObjectResponse {
    val response = copy(srcBucket, srcKey, destBucket, destKey, builder)

    if (response.copyObjectResult?.eTag?.isNotBlank() == true) {
        deleteObject {
            bucket = srcBucket
            key = srcKey
        }
    }
    return response
}

/**
 * Moves an S3 object.
 *
 * ```kotlin
 * val response = s3Client.move(
 *    copyRequestBuilder = {
 *        bucket = "dest-bucket"
 *        key = "dest-key"
 *        copySource = "src-bucket/src-key"
 *    },
 *    deleteRequestBuilder = {
 *          bucket = "src-bucket"
 *          key = "src-key"
 *    }
 * )
 * ```
 * @param copyRequestBuilder configures the [CopyObjectRequest] through [CopyObjectRequest.Builder]
 * @param deleteRequestBuilder configures the [DeleteObjectRequest] through [DeleteObjectRequest.Builder]
 * @return the [CopyObjectResponse]
 */
suspend inline fun S3Client.move(
    crossinline copyRequestBuilder: CopyObjectRequest.Builder.() -> Unit,
    crossinline deleteRequestBuilder: DeleteObjectRequest.Builder.() -> Unit,
): CopyObjectResponse {
    val response = copyObject(copyRequestBuilder)

    if (response.copyObjectResult?.eTag?.isNotBlank() == true) {
        deleteObject(deleteRequestBuilder)
    }
    return response
}
