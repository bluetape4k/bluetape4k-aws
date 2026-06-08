package io.bluetape4k.aws.s3vectors

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeSameInstanceAs
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
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
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class S3VectorsCoroutinesTemplateTest {

    private val client = mockk<S3VectorsAsyncClient>()
    private val template = S3VectorsCoroutinesTemplate(client)

    @BeforeEach
    fun resetMocks() {
        clearMocks(client)
    }

    @Test
    fun `listVectorBuckets delegates to S3 Vectors async client`() = runSuspendIO {
        val request = ListVectorBucketsRequest.builder().build()
        val response = ListVectorBucketsResponse.builder().build()
        every { client.listVectorBuckets(request) } returns CompletableFuture.completedFuture(response)

        template.listVectorBuckets(request) shouldBeSameInstanceAs response

        verify(exactly = 1) { client.listVectorBuckets(request) }
    }

    @Test
    fun `getVectorBucket delegates to S3 Vectors async client`() = runSuspendIO {
        val request = GetVectorBucketRequest.builder().vectorBucketName("vectors").build()
        val response = GetVectorBucketResponse.builder().build()
        every { client.getVectorBucket(request) } returns CompletableFuture.completedFuture(response)

        template.getVectorBucket(request) shouldBeSameInstanceAs response

        verify(exactly = 1) { client.getVectorBucket(request) }
    }

    @Test
    fun `listIndexes delegates to S3 Vectors async client`() = runSuspendIO {
        val request = ListIndexesRequest.builder().vectorBucketName("vectors").build()
        val response = ListIndexesResponse.builder().build()
        every { client.listIndexes(request) } returns CompletableFuture.completedFuture(response)

        template.listIndexes(request) shouldBeSameInstanceAs response

        verify(exactly = 1) { client.listIndexes(request) }
    }

    @Test
    fun `getIndex delegates to S3 Vectors async client`() = runSuspendIO {
        val request = GetIndexRequest.builder().vectorBucketName("vectors").indexName("semantic").build()
        val response = GetIndexResponse.builder().build()
        every { client.getIndex(request) } returns CompletableFuture.completedFuture(response)

        template.getIndex(request) shouldBeSameInstanceAs response

        verify(exactly = 1) { client.getIndex(request) }
    }

    @Test
    fun `putVectors delegates to S3 Vectors async client`() = runSuspendIO {
        val request = PutVectorsRequest.builder().vectorBucketName("vectors").indexName("semantic").build()
        val response = PutVectorsResponse.builder().build()
        every { client.putVectors(request) } returns CompletableFuture.completedFuture(response)

        template.putVectors(request) shouldBeSameInstanceAs response

        verify(exactly = 1) { client.putVectors(request) }
    }

    @Test
    fun `getVectors delegates to S3 Vectors async client`() = runSuspendIO {
        val request = GetVectorsRequest.builder().vectorBucketName("vectors").indexName("semantic").build()
        val response = GetVectorsResponse.builder().build()
        every { client.getVectors(request) } returns CompletableFuture.completedFuture(response)

        template.getVectors(request) shouldBeSameInstanceAs response

        verify(exactly = 1) { client.getVectors(request) }
    }

    @Test
    fun `listVectors delegates to S3 Vectors async client`() = runSuspendIO {
        val request = ListVectorsRequest.builder().vectorBucketName("vectors").indexName("semantic").build()
        val response = ListVectorsResponse.builder().build()
        every { client.listVectors(request) } returns CompletableFuture.completedFuture(response)

        template.listVectors(request) shouldBeSameInstanceAs response

        verify(exactly = 1) { client.listVectors(request) }
    }

    @Test
    fun `queryVectors delegates to S3 Vectors async client`() = runSuspendIO {
        val request = QueryVectorsRequest.builder().vectorBucketName("vectors").indexName("semantic").build()
        val response = QueryVectorsResponse.builder().build()
        every { client.queryVectors(request) } returns CompletableFuture.completedFuture(response)

        template.queryVectors(request) shouldBeSameInstanceAs response

        verify(exactly = 1) { client.queryVectors(request) }
    }

    @Test
    fun `cancelled vector bucket lookup propagates cancellation`() = runSuspendIO {
        val request = GetVectorBucketRequest.builder().vectorBucketName("vectors").build()
        val future = CompletableFuture<GetVectorBucketResponse>()
        future.cancel(true)
        every { client.getVectorBucket(request) } returns future

        assertFailsWith<CancellationException> {
            template.getVectorBucket(request)
        }
    }
}
