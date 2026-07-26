package io.bluetape4k.aws.kotlin.s3.model

import aws.sdk.kotlin.services.s3.model.HeadObjectRequest
import io.bluetape4k.support.requireNotBlank

/**
 * Creates a [HeadObjectRequest] for retrieving metadata about the object at [key] in [bucket].
 *
 * ```kotlin
 * val request = headObjectRequestOf(
 *     bucket = "my-bucket",
 *     key = "path/to/object.txt"
 * )
 * val response = s3Client.headObject(request)
 * // response.contentLength is the object size in bytes
 * ```
 *
 * @param bucket bucket name
 * @param key object key
 * @return the [HeadObjectRequest]
 */
inline fun headObjectRequestOf(
    bucket: String,
    key: String,
    crossinline builder: HeadObjectRequest.Builder.() -> Unit = {},
): HeadObjectRequest {
    bucket.requireNotBlank("bucket")
    key.requireNotBlank("key")

    return HeadObjectRequest {
        this.bucket = bucket
        this.key = key

        builder()
    }
}
