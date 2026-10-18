package io.bluetape4k.aws.s3.model

import io.bluetape4k.support.requireNotBlank
import software.amazon.awssdk.services.s3.model.DeleteBucketRequest

inline fun deleteBucketRequest(
    bucket: String,
    builder: DeleteBucketRequest.Builder.() -> Unit,
): DeleteBucketRequest {
    bucket.requireNotBlank("bucket")

    return DeleteBucketRequest.builder()
        .bucket(bucket)
        .apply(builder)
        .build()
}

inline fun deleteBucketRequestOf(
    bucket: String,
    expectedOwner: String? = null,
    builder: DeleteBucketRequest.Builder.() -> Unit = {},
): DeleteBucketRequest =
    deleteBucketRequest(bucket) {
        expectedOwner?.let { expectedBucketOwner(it) }
        builder()
    }
