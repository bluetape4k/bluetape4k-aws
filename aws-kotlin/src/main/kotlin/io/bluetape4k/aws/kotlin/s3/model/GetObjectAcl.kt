package io.bluetape4k.aws.kotlin.s3.model

import aws.sdk.kotlin.services.s3.model.GetObjectAclRequest
import io.bluetape4k.support.requireNotBlank

/**
 * Creates a [GetObjectAclRequest] for the ACL of the object at [key] in [bucket].
 *
 * ```kotlin
 * val request = getObjectAclRequestOf(
 *     bucket = "my-bucket",
 *     key = "path/to/object.txt"
 * )
 * val response = s3Client.getObjectAcl(request)
 * ```
 *
 * @param bucket bucket name
 * @param key object key
 * @param versionId specific version ID; when null, uses the latest version
 * @return the [GetObjectAclRequest]
 */
inline fun getObjectAclRequestOf(
    bucket: String,
    key: String,
    versionId: String? = null,
    crossinline builder: GetObjectAclRequest.Builder.() -> Unit = {},
): GetObjectAclRequest {
    bucket.requireNotBlank("bucket")
    key.requireNotBlank("key")

    return GetObjectAclRequest {
        this.bucket = bucket
        this.key = key
        this.versionId = versionId

        builder()
    }
}
