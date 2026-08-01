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
 * 코루틴 친화적인 `CompletableFuture.await()`로 벡터 버킷 목록을 조회합니다.
 */
suspend fun S3VectorsAsyncClient.listVectorBucketsSuspend(
    request: ListVectorBucketsRequest,
): ListVectorBucketsResponse =
    listVectorBuckets(request).await()

/**
 * 코루틴 친화적인 `CompletableFuture.await()`로 벡터 버킷 속성을 반환합니다.
 */
suspend fun S3VectorsAsyncClient.getVectorBucketSuspend(
    request: GetVectorBucketRequest,
): GetVectorBucketResponse =
    getVectorBucket(request).await()

/**
 * 코루틴 친화적인 `CompletableFuture.await()`로 벡터 인덱스 목록을 조회합니다.
 */
suspend fun S3VectorsAsyncClient.listIndexesSuspend(
    request: ListIndexesRequest,
): ListIndexesResponse =
    listIndexes(request).await()

/**
 * 코루틴 친화적인 `CompletableFuture.await()`로 벡터 인덱스 속성을 반환합니다.
 */
suspend fun S3VectorsAsyncClient.getIndexSuspend(
    request: GetIndexRequest,
): GetIndexResponse =
    getIndex(request).await()

/**
 * 코루틴 친화적인 `CompletableFuture.await()`로 인덱스에 벡터를 추가합니다.
 */
suspend fun S3VectorsAsyncClient.putVectorsSuspend(
    request: PutVectorsRequest,
): PutVectorsResponse =
    putVectors(request).await()

/**
 * 코루틴 친화적인 `CompletableFuture.await()`로 인덱스의 벡터를 반환합니다.
 */
suspend fun S3VectorsAsyncClient.getVectorsSuspend(
    request: GetVectorsRequest,
): GetVectorsResponse =
    getVectors(request).await()

/**
 * 코루틴 친화적인 `CompletableFuture.await()`로 인덱스의 벡터 목록을 조회합니다.
 */
suspend fun S3VectorsAsyncClient.listVectorsSuspend(
    request: ListVectorsRequest,
): ListVectorsResponse =
    listVectors(request).await()

/**
 * 코루틴 친화적인 `CompletableFuture.await()`로 인덱스를 쿼리합니다.
 */
suspend fun S3VectorsAsyncClient.queryVectorsSuspend(
    request: QueryVectorsRequest,
): QueryVectorsResponse =
    queryVectors(request).await()
