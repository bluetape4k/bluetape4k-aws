package io.bluetape4k.aws.s3.model

import io.bluetape4k.support.requireNotBlank
import software.amazon.awssdk.services.s3.model.GetObjectRequest

/**
 * See the API documentation for details.
 *
 * Example:
 * ```kotlin
 * val result = getObjectRequest("demo-bucket", "docs/readme.txt") { partNumber(2) }
 * // result.partNumber() == 2
 * ```
 */
inline fun getObjectRequest(
    bucket: String,
    key: String,
    builder: GetObjectRequest.Builder.() -> Unit = {},
): GetObjectRequest {
    bucket.requireNotBlank("bucket")
    key.requireNotBlank("key")

    return GetObjectRequest.builder()
        .bucket(bucket)
        .key(key)
        .apply(builder)
        .build()
}

/**
 * See the API documentation for details.
 *
 * Example:
 * ```kotlin
 * val result = getObjectRequestOf("demo-bucket", "docs/readme.txt", versionId = "v2")
 * // result.versionId() == "v2"
 * ```
 */
fun getObjectRequestOf(
    bucket: String,
    key: String,
    versionId: String? = null,
    partNumber: Int? = null,
): GetObjectRequest =
    getObjectRequest(bucket, key) {
        versionId?.let { versionId(it) }
        partNumber?.let { partNumber(it) }
    }
