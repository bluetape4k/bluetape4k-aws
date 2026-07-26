package io.bluetape4k.aws.kotlin.s3.model

import aws.sdk.kotlin.services.s3.model.EncodingType
import aws.sdk.kotlin.services.s3.model.ListObjectsRequest
import io.bluetape4k.support.requireNotBlank

/**
 * Creates a [ListObjectsRequest] for listing objects in [bucket].
 *
 * ```kotlin
 * val request = listObjectsRequestOf(
 *     bucket = "my-bucket",
 *     prefix = "path/to/",
 *     maxKeys = 100
 * )
 * val response = s3Client.listObjects(request)
 * val objects = response.contents
 * ```
 *
 * @param bucket bucket name
 * @param prefix prefix filter; when null, includes all objects
 * @param delimiter delimiter; when null, does not group keys hierarchically
 * @param maxKeys maximum number of objects to return
 * @param encondingType encoding type
 * @return the [ListObjectsRequest]
 */
inline fun listObjectsRequestOf(
    bucket: String,
    prefix: String? = null,
    delimiter: String? = null,
    maxKeys: Int? = null,
    encondingType: EncodingType? = null,
    crossinline builder: ListObjectsRequest.Builder.() -> Unit = {},
): ListObjectsRequest {
    bucket.requireNotBlank("bucket")

    return ListObjectsRequest {
        this.bucket = bucket
        this.prefix = prefix
        this.delimiter = delimiter
        this.maxKeys = maxKeys
        this.encodingType = encondingType

        builder()
    }
}
