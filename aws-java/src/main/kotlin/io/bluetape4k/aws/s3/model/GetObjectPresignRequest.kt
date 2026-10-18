package io.bluetape4k.aws.s3.model

import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest
import java.time.Duration

inline fun getObjectPresignRequest(
    getObjectRequest: GetObjectRequest,
    builder: GetObjectPresignRequest.Builder.() -> Unit,
): GetObjectPresignRequest =
    GetObjectPresignRequest.builder()
        .getObjectRequest(getObjectRequest)
        .apply(builder)
        .build()

inline fun getObjectPresignRequestOf(
    getObjectRequest: GetObjectRequest,
    signatureDuration: Duration? = null,
    builder: GetObjectPresignRequest.Builder.() -> Unit = {},
): GetObjectPresignRequest =
    getObjectPresignRequest(getObjectRequest) {
        signatureDuration?.let { signatureDuration(it) }
        builder()
    }

inline fun getObjectPresignRequestOf(
    bucket: String,
    key: String,
    signatureDuration: Duration? = null,
    builder: GetObjectPresignRequest.Builder.() -> Unit = {},
): GetObjectPresignRequest =
    getObjectPresignRequestOf(getObjectRequestOf(bucket, key), signatureDuration, builder)
