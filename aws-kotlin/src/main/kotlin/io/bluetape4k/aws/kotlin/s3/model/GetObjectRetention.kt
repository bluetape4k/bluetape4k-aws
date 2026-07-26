package io.bluetape4k.aws.kotlin.s3.model

import aws.sdk.kotlin.services.s3.model.GetObjectRetentionRequest
import io.bluetape4k.support.requireNotBlank

/**
 * Creates a [GetObjectRetentionRequest] for the retention settings of the object at [key] in [bucket].
 *
 * ```kotlin
 * val request = getObjectRetentionRequestOf(
 *     bucket = "my-bucket",
 *     key = "path/to/object.txt"
 * )
 * val response = s3Client.getObjectRetention(request)
 * ```
 *
 * @param bucket bucket name
 * @param key object key
 * @param versionId specific version ID; when null, uses the latest version
 * @return the [GetObjectRetentionRequest]
 */
inline fun getObjectRetentionRequestOf(
    bucket: String,
    key: String,
    versionId: String? = null,
    crossinline builder: GetObjectRetentionRequest.Builder.() -> Unit = {},
): GetObjectRetentionRequest {
    bucket.requireNotBlank("bucket")
    key.requireNotBlank("key")

    return GetObjectRetentionRequest {
        this.bucket = bucket
        this.key = key
        this.versionId = versionId

        builder()
    }
}
