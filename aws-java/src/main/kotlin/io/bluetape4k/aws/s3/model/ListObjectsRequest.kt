package io.bluetape4k.aws.s3.model

import io.bluetape4k.support.requireNotBlank
import software.amazon.awssdk.services.s3.model.ListObjectsRequest

/**
 * See the API documentation for details.
 *
 * See the API documentation for details.
 *
 * Example:
 * ```kotlin
 * val result = listObjectsRequest("logs-bucket") { prefix("2026/") }
 * // result.prefix() == "2026/"
 * ```
 */
inline fun listObjectsRequest(
    bucket: String,
    builder: ListObjectsRequest.Builder.() -> Unit = {},
): ListObjectsRequest {
    bucket.requireNotBlank("bucket")
    return ListObjectsRequest.builder()
        .bucket(bucket)
        .apply(builder)
        .build()
}

/**
 * See the API documentation for details.
 *
 * Example:
 * ```kotlin
 * val result = listObjectsRequestOf("logs-bucket")
 * // result.bucket() == "logs-bucket"
 * ```
 */
inline fun listObjectsRequestOf(
    bucket: String,
    builder: ListObjectsRequest.Builder.() -> Unit = {},
): ListObjectsRequest =
    listObjectsRequest(bucket, builder)
