package io.bluetape4k.aws.kotlin.s3.model

import aws.sdk.kotlin.services.s3.model.GetObjectRequest
import io.bluetape4k.support.requireNotBlank

/**
 * Creates a [GetObjectRequest] for the object at [key] in [bucket].
 *
 * ```kotlin
 * val request = getObjectRequestOf(
 *     bucket = "my-bucket",
 *     key = "path/to/object.txt"
 * )
 * s3Client.getObject(request) { response ->
 *     response.body?.decodeToString()
 * }
 * ```
 *
 * @param bucket bucket name
 * @param key object key
 * @param versionId specific version ID; when null, uses the latest version
 * @param partNumber part number of a multipart object; when null, retrieves the entire object
 * @return the [GetObjectRequest]
 */
inline fun getObjectRequestOf(
    bucket: String,
    key: String,
    versionId: String? = null,
    partNumber: Int? = null,
    crossinline builder: GetObjectRequest.Builder.() -> Unit = {},
): GetObjectRequest {
    bucket.requireNotBlank("bucket")
    key.requireNotBlank("key")

    return GetObjectRequest {
        this.bucket = bucket
        this.key = key
        this.versionId = versionId
        this.partNumber = partNumber

        builder()
    }
}
