package io.bluetape4k.aws.s3vectors

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
 * [S3VectorsAsyncClient]를 사용하는 기본 [S3VectorsOperations] 구현입니다.
 *
 * ## 계약
 *
 * 각 메서드는 suspend 호출을 추가로 감싸지 않고 AWS SDK `CompletableFuture`를 직접 기다립니다.
 * 따라서 코루틴 취소와 예외 완료는 일반적인 `CompletableFuture.await()` 동작을 유지합니다.
 */
class S3VectorsCoroutinesTemplate(
    private val s3VectorsAsyncClient: S3VectorsAsyncClient,
): S3VectorsOperations {

    override suspend fun listVectorBuckets(request: ListVectorBucketsRequest): ListVectorBucketsResponse =
        s3VectorsAsyncClient.listVectorBucketsSuspend(request)

    override suspend fun getVectorBucket(request: GetVectorBucketRequest): GetVectorBucketResponse =
        s3VectorsAsyncClient.getVectorBucketSuspend(request)

    override suspend fun listIndexes(request: ListIndexesRequest): ListIndexesResponse =
        s3VectorsAsyncClient.listIndexesSuspend(request)

    override suspend fun getIndex(request: GetIndexRequest): GetIndexResponse =
        s3VectorsAsyncClient.getIndexSuspend(request)

    override suspend fun putVectors(request: PutVectorsRequest): PutVectorsResponse =
        s3VectorsAsyncClient.putVectorsSuspend(request)

    override suspend fun getVectors(request: GetVectorsRequest): GetVectorsResponse =
        s3VectorsAsyncClient.getVectorsSuspend(request)

    override suspend fun listVectors(request: ListVectorsRequest): ListVectorsResponse =
        s3VectorsAsyncClient.listVectorsSuspend(request)

    override suspend fun queryVectors(request: QueryVectorsRequest): QueryVectorsResponse =
        s3VectorsAsyncClient.queryVectorsSuspend(request)
}
