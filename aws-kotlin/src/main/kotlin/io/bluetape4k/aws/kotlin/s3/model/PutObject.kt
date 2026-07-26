package io.bluetape4k.aws.kotlin.s3.model

import aws.sdk.kotlin.services.s3.model.ObjectCannedAcl
import aws.sdk.kotlin.services.s3.model.PutObjectRequest
import aws.smithy.kotlin.runtime.content.ByteStream
import io.bluetape4k.support.requireNotBlank

/**
 * Creates a [PutObjectRequest] for storing an object at [key] in [bucket].
 *
 * ```kotlin
 * val request = putObjectRequestOf(
 *     bucket = "my-bucket",
 *     key = "path/to/object.txt",
 *     body = ByteStream.fromString("Hello, World!"),
 *     contentType = "text/plain"
 * )
 * s3Client.putObject(request)
 * ```
 *
 * @param bucket bucket name
 * @param key object key
 * @param body [aws.smithy.kotlin.runtime.content.ByteStream] to store; when null, stores an empty object
 * @param metadata metadata map
 * @param acl access control list
 * @param contentType content type, such as "text/plain" or "application/json"
 * @return the [PutObjectRequest]
 */
inline fun putObjectRequestOf(
    bucket: String,
    key: String,
    body: ByteStream? = null,
    metadata: Map<String, String>? = null,
    acl: ObjectCannedAcl? = null,
    contentType: String? = null,
    crossinline builder: PutObjectRequest.Builder.() -> Unit = {},
): PutObjectRequest {
    bucket.requireNotBlank("bucket")
    key.requireNotBlank("key")

    return PutObjectRequest {
        this.bucket = bucket
        this.key = key
        this.body = body
        this.metadata = metadata
        this.acl = acl
        this.contentType = contentType

        builder()
    }
}
