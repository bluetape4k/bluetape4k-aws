package io.bluetape4k.aws.s3vectors

import software.amazon.awssdk.services.s3vectors.model.GetIndexRequest
import software.amazon.awssdk.services.s3vectors.model.GetIndexResponse
import software.amazon.awssdk.services.s3vectors.model.GetVectorBucketRequest
import software.amazon.awssdk.services.s3vectors.model.GetVectorBucketResponse
import software.amazon.awssdk.services.s3vectors.model.GetVectorsRequest
import software.amazon.awssdk.services.s3vectors.model.GetVectorsResponse
import software.amazon.awssdk.services.s3vectors.model.ListIndexesRequest
import software.amazon.awssdk.services.s3vectors.model.ListIndexesResponse
import software.amazon.awssdk.services.s3vectors.model.ListVectorBucketsRequest
import software.amazon.awssdk.services.s3vectors.model.ListVectorBucketsResponse
import software.amazon.awssdk.services.s3vectors.model.ListVectorsRequest
import software.amazon.awssdk.services.s3vectors.model.ListVectorsResponse
import software.amazon.awssdk.services.s3vectors.model.PutVectorsRequest
import software.amazon.awssdk.services.s3vectors.model.PutVectorsResponse
import software.amazon.awssdk.services.s3vectors.model.QueryVectorsRequest
import software.amazon.awssdk.services.s3vectors.model.QueryVectorsResponse

/**
 * Coroutine facade for the common Amazon S3 Vectors application workflow.
 *
 * ## Contract
 *
 * This facade intentionally covers discovery and vector read/write/query
 * operations only. Policy, tagging, and destructive administrative operations
 * remain available through the raw AWS SDK `S3VectorsAsyncClient`.
 *
 * ```kotlin
 * val indexes = s3Vectors.listIndexes(ListIndexesRequest.builder()
 *     .vectorBucketName("semantic-search")
 *     .build())
 * ```
 */
interface S3VectorsOperations {

    /** Lists vector buckets owned by the current AWS caller. */
    suspend fun listVectorBuckets(request: ListVectorBucketsRequest): ListVectorBucketsResponse

    /** Returns attributes for one vector bucket. */
    suspend fun getVectorBucket(request: GetVectorBucketRequest): GetVectorBucketResponse

    /** Lists indexes within a vector bucket. */
    suspend fun listIndexes(request: ListIndexesRequest): ListIndexesResponse

    /** Returns attributes for one vector index. */
    suspend fun getIndex(request: GetIndexRequest): GetIndexResponse

    /** Adds one or more vectors to a vector index. */
    suspend fun putVectors(request: PutVectorsRequest): PutVectorsResponse

    /** Returns vector attributes from a vector index. */
    suspend fun getVectors(request: GetVectorsRequest): GetVectorsResponse

    /** Lists vectors in a vector index. */
    suspend fun listVectors(request: ListVectorsRequest): ListVectorsResponse

    /** Runs an approximate nearest-neighbor query in a vector index. */
    suspend fun queryVectors(request: QueryVectorsRequest): QueryVectorsResponse
}
