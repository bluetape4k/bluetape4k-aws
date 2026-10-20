package io.bluetape4k.aws.ktor.s3vectors

import aws.sdk.kotlin.services.s3vectors.S3VectorsClient
import aws.sdk.kotlin.services.s3vectors.model.DataType
import aws.sdk.kotlin.services.s3vectors.model.DistanceMetric
import aws.sdk.kotlin.services.s3vectors.model.GetIndexRequest
import aws.sdk.kotlin.services.s3vectors.model.GetIndexResponse
import aws.sdk.kotlin.services.s3vectors.model.Index
import aws.sdk.kotlin.services.s3vectors.model.ListIndexesRequest
import aws.sdk.kotlin.services.s3vectors.model.ListIndexesResponse
import aws.smithy.kotlin.runtime.time.Instant
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeSameInstanceAs
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.slot
import org.junit.jupiter.api.Test

class S3VectorsKtorTemplateTest {

    private val client = mockk<S3VectorsClient>()

    @Test
    fun `listIndexes preserves request and response pagination tokens`() = runSuspendIO {
        val request = ListIndexesRequest {
            vectorBucketName = "semantic-search"
            prefix = "orders/"
            maxResults = 21
            nextToken = "request-page"
        }
        val captured = slot<ListIndexesRequest>()
        val response = ListIndexesResponse {
            indexes = emptyList()
            nextToken = "response-page"
        }
        coEvery { client.listIndexes(capture(captured)) } returns response

        val result = S3VectorsKtorTemplate(client).listIndexes(request)

        captured.captured.vectorBucketName shouldBeEqualTo "semantic-search"
        captured.captured.prefix shouldBeEqualTo "orders/"
        captured.captured.maxResults shouldBeEqualTo 21
        captured.captured.nextToken shouldBeEqualTo "request-page"
        result shouldBeSameInstanceAs response
        result.nextToken shouldBeEqualTo "response-page"
    }

    @Test
    fun `getIndex preserves nullable fields and unknown enum values`() = runSuspendIO {
        val request = GetIndexRequest {
            vectorBucketName = "semantic-search"
            indexName = "orders"
        }
        val unknownDataType = DataType.SdkUnknown("future-float16")
        val unknownDistanceMetric = DistanceMetric.SdkUnknown("future-distance")
        val response = GetIndexResponse {
            index = Index {
                creationTime = Instant.fromEpochSeconds(0)
                vectorBucketName = "semantic-search"
                indexName = "orders"
                indexArn = "arn:aws:s3vectors:us-east-1:123456789012:bucket/semantic-search/index/orders"
                dimension = 3
                dataType = unknownDataType
                distanceMetric = unknownDistanceMetric
            }
        }
        coEvery { client.getIndex(request) } returns response

        val result = S3VectorsKtorTemplate(client).getIndex(request)

        result.index?.dataType shouldBeEqualTo unknownDataType
        result.index?.distanceMetric shouldBeEqualTo unknownDistanceMetric
        result.index?.creationTime shouldBeEqualTo Instant.fromEpochSeconds(0)
        result.index?.encryptionConfiguration shouldBeEqualTo null
        result.index?.metadataConfiguration shouldBeEqualTo null
    }
}
