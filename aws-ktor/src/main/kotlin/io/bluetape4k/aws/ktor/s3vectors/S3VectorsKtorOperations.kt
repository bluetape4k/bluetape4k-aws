package io.bluetape4k.aws.ktor.s3vectors

import aws.sdk.kotlin.services.s3vectors.model.GetIndexRequest
import aws.sdk.kotlin.services.s3vectors.model.GetIndexResponse
import aws.sdk.kotlin.services.s3vectors.model.GetVectorBucketRequest
import aws.sdk.kotlin.services.s3vectors.model.GetVectorBucketResponse
import aws.sdk.kotlin.services.s3vectors.model.GetVectorsRequest
import aws.sdk.kotlin.services.s3vectors.model.GetVectorsResponse
import aws.sdk.kotlin.services.s3vectors.model.ListIndexesRequest
import aws.sdk.kotlin.services.s3vectors.model.ListIndexesResponse
import aws.sdk.kotlin.services.s3vectors.model.ListVectorBucketsRequest
import aws.sdk.kotlin.services.s3vectors.model.ListVectorBucketsResponse
import aws.sdk.kotlin.services.s3vectors.model.ListVectorsRequest
import aws.sdk.kotlin.services.s3vectors.model.ListVectorsResponse
import aws.sdk.kotlin.services.s3vectors.model.PutVectorsRequest
import aws.sdk.kotlin.services.s3vectors.model.PutVectorsResponse
import aws.sdk.kotlin.services.s3vectors.model.QueryVectorsRequest
import aws.sdk.kotlin.services.s3vectors.model.QueryVectorsResponse

/**
 * Ktor 애플리케이션을 위한 AWS Kotlin SDK 기반 S3 Vectors 작업입니다.
 *
 * 탐색 및 벡터 읽기·쓰기·쿼리 작업을 suspend API와 Kotlin SDK 모델로 제공합니다.
 */
interface S3VectorsKtorOperations {

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
