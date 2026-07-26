package io.bluetape4k.aws.kotlin.s3.model

import aws.sdk.kotlin.services.s3.model.ListBucketsRequest

/**
 * Creates a [ListBucketsRequest] for listing S3 buckets.
 *
 * ```kotlin
 * val request = listBucketsRequestOf(maxBuckets = 100)
 * val response = s3Client.listBuckets(request)
 * val buckets = response.buckets
 * ```
 *
 * @param maxBuckets maximum number of buckets to return; when null, uses the service default
 * @param continuationToken pagination token; when null, requests the first page
 * @return the [ListBucketsRequest]
 */
inline fun listBucketsRequestOf(
    maxBuckets: Int? = null,
    continuationToken: String? = null,
    crossinline builder: ListBucketsRequest.Builder.() -> Unit = {},
): ListBucketsRequest =
    ListBucketsRequest {
        this.maxBuckets = maxBuckets
        this.continuationToken = continuationToken

        builder()
    }
