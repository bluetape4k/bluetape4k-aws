package io.bluetape4k.aws.kotlin.s3.model

import aws.sdk.kotlin.services.s3.model.CopyObjectRequest
import aws.sdk.kotlin.services.s3.model.ObjectCannedAcl
import io.bluetape4k.support.requireNotBlank
import java.net.URLEncoder

/**
 * Creates a URL-encoded copy source from bucket and key information, then builds a [CopyObjectRequest].
 *
 * ```kotlin
 * val request = copyObjectRequestOf(
 *     srcBucket = "src-bucket",
 *     srcKey = "path/to/src-object.txt",
 *     destBucket = "dest-bucket",
 *     destKey = "path/to/dest-object.txt"
 * )
 * s3Client.copyObject(request)
 * ```
 *
 * @param srcBucket source bucket name
 * @param srcKey source object key
 * @param destBucket destination bucket name
 * @param destKey destination object key
 * @param acl access control list
 * @return the [CopyObjectRequest]
 */
inline fun copyObjectRequestOf(
    srcBucket: String,
    srcKey: String,
    destBucket: String,
    destKey: String,
    acl: ObjectCannedAcl? = null,
    crossinline builder: CopyObjectRequest.Builder.() -> Unit = {},
): CopyObjectRequest {
    srcBucket.requireNotBlank("srcBucket")
    srcKey.requireNotBlank("srcKey")
    destBucket.requireNotBlank("destBucket")
    destKey.requireNotBlank("destKey")

    return CopyObjectRequest {
        this.copySource = URLEncoder.encode("$srcBucket/$srcKey", Charsets.UTF_8)
        this.bucket = destBucket
        this.key = destKey
        this.acl = acl

        builder()
    }
}

/**
 * Creates a [CopyObjectRequest] from an existing copy source string.
 *
 * ```kotlin
 * val request = copyObjectRequestOf(
 *     copySource = "src-bucket/path/to/src-object.txt",
 *     destBucket = "dest-bucket",
 *     destKey = "path/to/dest-object.txt"
 * )
 * s3Client.copyObject(request)
 * ```
 *
 * @param copySource URL-encoded copy source string, such as "src-bucket/src-key"
 * @param destBucket destination bucket name
 * @param destKey destination object key
 * @param acl access control list
 * @return the [CopyObjectRequest]
 */
inline fun copyObjectRequestOf(
    copySource: String,
    destBucket: String,
    destKey: String,
    acl: ObjectCannedAcl? = null,
    crossinline builder: CopyObjectRequest.Builder.() -> Unit = {},
): CopyObjectRequest {
    copySource.requireNotBlank("copySource")
    destBucket.requireNotBlank("destBucket")
    destKey.requireNotBlank("destKey")

    return CopyObjectRequest {
        this.copySource = copySource
        this.bucket = destBucket
        this.key = destKey
        this.acl = acl

        builder()
    }
}
