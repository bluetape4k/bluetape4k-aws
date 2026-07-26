package io.bluetape4k.aws.kotlin.s3.model

import aws.sdk.kotlin.services.s3.model.GetBucketPolicyRequest
import io.bluetape4k.support.requireNotBlank

/**
 * Creates a [GetBucketPolicyRequest] for retrieving the policy of [bucket].
 *
 * ```kotlin
 * val request = getBucketPolicyRequestOf("my-bucket")
 * val response = s3Client.getBucketPolicy(request)
 * val policy = response.policy
 * ```
 *
 * @param bucket bucket name
 * @param expectedBucketOwner expected bucket owner account ID; omitted when null
 * @return the [GetBucketPolicyRequest]
 */
inline fun getBucketPolicyRequestOf(
    bucket: String,
    expectedBucketOwner: String? = null,
    crossinline builder: GetBucketPolicyRequest.Builder.() -> Unit = {},
): GetBucketPolicyRequest {
    bucket.requireNotBlank("bucket")

    return GetBucketPolicyRequest {
        this.bucket = bucket
        this.expectedBucketOwner = expectedBucketOwner

        builder()
    }
}
