package io.bluetape4k.aws.s3vectors

import kotlinx.coroutines.future.await
import software.amazon.awssdk.services.s3vectors.S3VectorsAsyncClient
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
 * Lists vector buckets with coroutine-friendly `CompletableFuture.await()`.
 */
suspend fun S3VectorsAsyncClient.listVectorBucketsSuspend(
    request: ListVectorBucketsRequest,
): ListVectorBucketsResponse =
    listVectorBuckets(request).await()

/**
 * Returns vector bucket attributes with coroutine-friendly `CompletableFuture.await()`.
 */
suspend fun S3VectorsAsyncClient.getVectorBucketSuspend(
    request: GetVectorBucketRequest,
): GetVectorBucketResponse =
    getVectorBucket(request).await()

/**
 * Lists vector indexes with coroutine-friendly `CompletableFuture.await()`.
 */
suspend fun S3VectorsAsyncClient.listIndexesSuspend(
    request: ListIndexesRequest,
): ListIndexesResponse =
    listIndexes(request).await()

/**
 * Returns vector index attributes with coroutine-friendly `CompletableFuture.await()`.
 */
suspend fun S3VectorsAsyncClient.getIndexSuspend(
    request: GetIndexRequest,
): GetIndexResponse =
    getIndex(request).await()

/**
 * Adds vectors to an index with coroutine-friendly `CompletableFuture.await()`.
 */
suspend fun S3VectorsAsyncClient.putVectorsSuspend(
    request: PutVectorsRequest,
): PutVectorsResponse =
    putVectors(request).await()

/**
 * Returns vectors from an index with coroutine-friendly `CompletableFuture.await()`.
 */
suspend fun S3VectorsAsyncClient.getVectorsSuspend(
    request: GetVectorsRequest,
): GetVectorsResponse =
    getVectors(request).await()

/**
 * Lists vectors in an index with coroutine-friendly `CompletableFuture.await()`.
 */
suspend fun S3VectorsAsyncClient.listVectorsSuspend(
    request: ListVectorsRequest,
): ListVectorsResponse =
    listVectors(request).await()

/**
 * Queries an index with coroutine-friendly `CompletableFuture.await()`.
 */
suspend fun S3VectorsAsyncClient.queryVectorsSuspend(
    request: QueryVectorsRequest,
): QueryVectorsResponse =
    queryVectors(request).await()
