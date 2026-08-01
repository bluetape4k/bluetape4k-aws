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
 * 일반적인 Amazon S3 Vectors 애플리케이션 워크플로를 위한 코루틴 파사드입니다.
 *
 * ## 계약
 *
 * 이 파사드는 탐색 및 벡터 읽기/쓰기/쿼리 작업만 의도적으로 제공합니다.
 * 정책, 태깅, 파괴적인 관리 작업은 AWS SDK 원본 `S3VectorsAsyncClient`를 통해 사용할 수 있습니다.
 *
 * ```kotlin
 * val indexes = s3Vectors.listIndexes(ListIndexesRequest.builder()
 *     .vectorBucketName("semantic-search")
 *     .build())
 * ```
 */
interface S3VectorsOperations {

/** 현재 AWS 호출자가 소유한 벡터 버킷 목록을 조회합니다. */
    suspend fun listVectorBuckets(request: ListVectorBucketsRequest): ListVectorBucketsResponse

/** 벡터 버킷 하나의 속성을 반환합니다. */
    suspend fun getVectorBucket(request: GetVectorBucketRequest): GetVectorBucketResponse

/** 벡터 버킷 안의 인덱스 목록을 조회합니다. */
    suspend fun listIndexes(request: ListIndexesRequest): ListIndexesResponse

/** 벡터 인덱스 하나의 속성을 반환합니다. */
    suspend fun getIndex(request: GetIndexRequest): GetIndexResponse

/** 벡터 인덱스에 하나 이상의 벡터를 추가합니다. */
    suspend fun putVectors(request: PutVectorsRequest): PutVectorsResponse

/** 벡터 인덱스에서 벡터 속성을 반환합니다. */
    suspend fun getVectors(request: GetVectorsRequest): GetVectorsResponse

/** 벡터 인덱스의 벡터 목록을 조회합니다. */
    suspend fun listVectors(request: ListVectorsRequest): ListVectorsResponse

/** 벡터 인덱스에서 근사 최근접 이웃 쿼리를 실행합니다. */
    suspend fun queryVectors(request: QueryVectorsRequest): QueryVectorsResponse
}
