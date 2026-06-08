package io.bluetape4k.aws.spring.s3vectors

import io.bluetape4k.aws.s3vectors.S3VectorsOperations
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

internal object NoopS3VectorsOperations: S3VectorsOperations {

    override suspend fun listVectorBuckets(request: ListVectorBucketsRequest): ListVectorBucketsResponse =
        ListVectorBucketsResponse.builder().build()

    override suspend fun getVectorBucket(request: GetVectorBucketRequest): GetVectorBucketResponse =
        GetVectorBucketResponse.builder().build()

    override suspend fun listIndexes(request: ListIndexesRequest): ListIndexesResponse =
        ListIndexesResponse.builder().build()

    override suspend fun getIndex(request: GetIndexRequest): GetIndexResponse =
        GetIndexResponse.builder().build()

    override suspend fun putVectors(request: PutVectorsRequest): PutVectorsResponse =
        PutVectorsResponse.builder().build()

    override suspend fun getVectors(request: GetVectorsRequest): GetVectorsResponse =
        GetVectorsResponse.builder().build()

    override suspend fun listVectors(request: ListVectorsRequest): ListVectorsResponse =
        ListVectorsResponse.builder().build()

    override suspend fun queryVectors(request: QueryVectorsRequest): QueryVectorsResponse =
        QueryVectorsResponse.builder().build()
}
