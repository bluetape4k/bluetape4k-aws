package io.bluetape4k.aws.kotlin.s3

import aws.sdk.kotlin.services.s3.S3Client
import aws.sdk.kotlin.services.s3.model.CopyObjectRequest
import aws.sdk.kotlin.services.s3.model.CopyObjectResponse
import io.bluetape4k.aws.kotlin.s3.model.copyObjectRequestOf

/**
 * Copies an object.
 *
 * ```
 * val response = s3Client.copy("src-bucket", "src-key", "dest-bucket", "dest-key")
 * ```
 *
 * @param srcBucket source bucket name
 * @param srcKey source object key
 * @param destBucket destination bucket name
 * @param destKey destination object key
 * @return the [CopyObjectResponse]
 */
suspend inline fun S3Client.copy(
    srcBucket: String,
    srcKey: String,
    destBucket: String,
    destKey: String,
    crossinline builder: CopyObjectRequest.Builder.() -> Unit = {},
): CopyObjectResponse {
    val request = copyObjectRequestOf(srcBucket, srcKey, destBucket, destKey, builder = builder)
    return copyObject(request)
}
