package io.bluetape4k.aws.kotlin.s3.model

import aws.sdk.kotlin.services.s3.model.HeadBucketRequest
import io.bluetape4k.support.requireNotBlank

/**
 * Creates a [HeadBucketRequest] for checking whether a bucket exists.
 *
 * ```kotlin
 * val request = headBucketRequestOf("my-bucket")
 * val response = s3Client.headBucket(request)
 * ```
 *
 * @param bucket bucket name
 * @param expectedBucketOwner expected bucket owner account ID; omitted when null
 * @return the [HeadBucketRequest]
 */
inline fun headBucketRequestOf(
    bucket: String,
    expectedBucketOwner: String? = null,
    crossinline builder: HeadBucketRequest.Builder.() -> Unit = {},
): HeadBucketRequest {
    bucket.requireNotBlank("bucket")

    return HeadBucketRequest {
        this.bucket = bucket
        this.expectedBucketOwner = expectedBucketOwner

        builder()
    }
}
