package io.bluetape4k.aws.s3.model

import io.bluetape4k.support.requireNotBlank
import software.amazon.awssdk.services.s3.model.HeadObjectRequest

inline fun headObjectRequest(
    bucket: String,
    key: String,
    builder: HeadObjectRequest.Builder.() -> Unit = {},
): HeadObjectRequest {
    bucket.requireNotBlank("bucket")
    key.requireNotBlank("key")

    return HeadObjectRequest.builder()
        .bucket(bucket)
        .key(key)
        .apply(builder)
        .build()
}

fun headObjectRequestOf(bucket: String, key: String): HeadObjectRequest {
    bucket.requireNotBlank("bucket")
    key.requireNotBlank("key")

    return HeadObjectRequest.builder()
        .bucket(bucket)
        .key(key)
        .build()
}
