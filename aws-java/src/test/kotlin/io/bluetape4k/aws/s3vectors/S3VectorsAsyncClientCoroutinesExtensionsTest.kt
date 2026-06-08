package io.bluetape4k.aws.s3vectors

import io.bluetape4k.assertions.shouldBeSameInstanceAs
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import software.amazon.awssdk.services.s3vectors.S3VectorsAsyncClient
import software.amazon.awssdk.services.s3vectors.model.QueryVectorsRequest
import software.amazon.awssdk.services.s3vectors.model.QueryVectorsResponse
import java.util.concurrent.CompletableFuture

class S3VectorsAsyncClientCoroutinesExtensionsTest {

    @Test
    fun `queryVectorsSuspend awaits async client future`() = runSuspendIO {
        val client = mockk<S3VectorsAsyncClient>()
        val request = QueryVectorsRequest.builder().vectorBucketName("vectors").indexName("semantic").build()
        val response = QueryVectorsResponse.builder().build()
        every { client.queryVectors(request) } returns CompletableFuture.completedFuture(response)

        client.queryVectorsSuspend(request) shouldBeSameInstanceAs response

        verify(exactly = 1) { client.queryVectors(request) }
    }
}
