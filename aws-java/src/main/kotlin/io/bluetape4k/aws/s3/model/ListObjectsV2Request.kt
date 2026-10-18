package io.bluetape4k.aws.s3.model

import io.bluetape4k.support.requireNotBlank
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request

inline fun listObjectsV2Request(
    bucket: String,
    builder: ListObjectsV2Request.Builder.() -> Unit = {},
): ListObjectsV2Request {
    bucket.requireNotBlank("bucket")

    return ListObjectsV2Request.builder()
        .bucket(bucket)
        .apply(builder)
        .build()
}

inline fun listObjectsV2RequestOf(
    bucket: String,
    builder: ListObjectsV2Request.Builder.() -> Unit = {},
): ListObjectsV2Request =
    listObjectsV2Request(bucket, builder)
