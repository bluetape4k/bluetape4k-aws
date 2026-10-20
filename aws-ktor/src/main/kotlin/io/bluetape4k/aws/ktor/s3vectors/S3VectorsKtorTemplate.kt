package io.bluetape4k.aws.ktor.s3vectors

import aws.sdk.kotlin.services.s3vectors.S3VectorsClient
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

/** AWS Kotlin SDK [S3VectorsClient]를 사용하는 기본 [S3VectorsKtorOperations] 구현입니다. */
class S3VectorsKtorTemplate(
    private val s3VectorsClient: S3VectorsClient,
): S3VectorsKtorOperations {

    override suspend fun listVectorBuckets(request: ListVectorBucketsRequest): ListVectorBucketsResponse =
        s3VectorsClient.listVectorBuckets(request)

    override suspend fun getVectorBucket(request: GetVectorBucketRequest): GetVectorBucketResponse =
        s3VectorsClient.getVectorBucket(request)

    override suspend fun listIndexes(request: ListIndexesRequest): ListIndexesResponse =
        s3VectorsClient.listIndexes(request)

    override suspend fun getIndex(request: GetIndexRequest): GetIndexResponse =
        s3VectorsClient.getIndex(request)

    override suspend fun putVectors(request: PutVectorsRequest): PutVectorsResponse =
        s3VectorsClient.putVectors(request)

    override suspend fun getVectors(request: GetVectorsRequest): GetVectorsResponse =
        s3VectorsClient.getVectors(request)

    override suspend fun listVectors(request: ListVectorsRequest): ListVectorsResponse =
        s3VectorsClient.listVectors(request)

    override suspend fun queryVectors(request: QueryVectorsRequest): QueryVectorsResponse =
        s3VectorsClient.queryVectors(request)
}
