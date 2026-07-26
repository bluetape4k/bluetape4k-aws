package io.bluetape4k.aws.kotlin.s3.model

import aws.sdk.kotlin.services.s3.model.DeleteBucketRequest
import io.bluetape4k.support.requireNotBlank

/**
 * Creates a [DeleteBucketRequest] for [bucket].
 *
 * ```kotlin
 * val request = deleteBucketRequestOf("my-bucket")
 * s3Client.deleteBucket(request)
 * ```
 *
 * @param bucket name of the bucket to delete
 * @param expectedBucketOwner expected bucket owner account ID; omitted when null
 * @return the [DeleteBucketRequest]
 */
inline fun deleteBucketRequestOf(
    bucket: String,
    expectedBucketOwner: String? = null,
    crossinline builder: DeleteBucketRequest.Builder.() -> Unit = {},
): DeleteBucketRequest {
    bucket.requireNotBlank("bucket")

    return DeleteBucketRequest {
        this.bucket = bucket
        this.expectedBucketOwner = expectedBucketOwner
        builder()
    }
}
