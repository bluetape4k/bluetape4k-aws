package io.bluetape4k.aws.s3.model

import software.amazon.awssdk.services.s3.model.PutObjectRequest
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest
import java.time.Duration

inline fun putObjectPresignRequest(
    putObjectRequest: PutObjectRequest,
    builder: PutObjectPresignRequest.Builder.() -> Unit,
): PutObjectPresignRequest =
    PutObjectPresignRequest.builder()
        .putObjectRequest(putObjectRequest)
        .apply(builder)
        .build()

inline fun putObjectPresignRequestOf(
    putObjectRequest: PutObjectRequest,
    signatureDuration: Duration? = null,
    builder: PutObjectPresignRequest.Builder.() -> Unit = {},
): PutObjectPresignRequest =
    putObjectPresignRequest(putObjectRequest) {
        signatureDuration?.let { signatureDuration(it) }
        builder()
    }

inline fun putObjectPresignRequestOf(
    bucket: String,
    key: String,
    signatureDuration: Duration? = null,
    builder: PutObjectPresignRequest.Builder.() -> Unit = {},
): PutObjectPresignRequest =
    putObjectPresignRequestOf(putObjectRequestOf(bucket, key), signatureDuration, builder)
