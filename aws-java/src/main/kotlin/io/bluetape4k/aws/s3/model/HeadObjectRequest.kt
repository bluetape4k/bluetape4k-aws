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

inline fun headObjectRequestOf(
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

/**
 * 이전 버전의 JVM 호출 호환성을 유지하면서 기본 요청을 생성합니다.
 *
 * @see headObjectRequestOf
 */
fun headObjectRequestOf(bucket: String, key: String): HeadObjectRequest = headObjectRequestOf(bucket, key) {}
