package io.bluetape4k.aws.sns

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.concurrent.completableFutureOf
import io.bluetape4k.aws.sns.model.publishBatchRequestEntryOf
import io.bluetape4k.aws.sns.model.publishBatchRequestOf
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import software.amazon.awssdk.services.sns.SnsAsyncClient
import software.amazon.awssdk.services.sns.model.CreatePlatformEndpointRequest
import software.amazon.awssdk.services.sns.model.CreatePlatformEndpointResponse
import software.amazon.awssdk.services.sns.model.PublishBatchRequest
import software.amazon.awssdk.services.sns.model.PublishBatchResponse
import java.util.function.Consumer
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture

class SnsAsyncClientCoroutinesExtensionsTest {

    @Test
    fun `createPlatformEndpoint는 createPlatformEndpoint 결과를 반환한다`() = runTest {
        val client = mockk<SnsAsyncClient>()
        lateinit var capturedConsumer: Consumer<CreatePlatformEndpointRequest.Builder>
        val response = CreatePlatformEndpointResponse.builder()
            .endpointArn("arn:aws:sns:ap-northeast-2:000000000000:endpoint/test")
            .build()

        every {
            client.createPlatformEndpoint(any<Consumer<CreatePlatformEndpointRequest.Builder>>())
        } answers {
            capturedConsumer = firstArg()
            completableFutureOf(response)
        }

        val result = client.createPlatformEndpoint(
            token = "token-1",
            platformApplicationArn = "arn:aws:sns:ap-northeast-2:000000000000:app/test",
        )

        val requestBuilder = CreatePlatformEndpointRequest.builder()
        capturedConsumer.accept(requestBuilder)
        val capturedRequest = requestBuilder.build()

        result.endpointArn() shouldBeEqualTo "arn:aws:sns:ap-northeast-2:000000000000:endpoint/test"
        capturedRequest.token() shouldBeEqualTo "token-1"
        capturedRequest.platformApplicationArn() shouldBeEqualTo "arn:aws:sns:ap-northeast-2:000000000000:app/test"

        verify(exactly = 1) { client.createPlatformEndpoint(any<Consumer<CreatePlatformEndpointRequest.Builder>>()) }
    }

    @Test
    fun `publishBatchAsync는 request를 전달하고 future를 반환한다`() {
        val client = mockk<SnsAsyncClient>()
        val request = publishBatchRequestOf(
            topicArn = "arn:aws:sns:ap-northeast-2:000000000000:topic",
            entries = listOf(publishBatchRequestEntryOf("entry-1", "message-1")),
        )
        val response = PublishBatchResponse.builder().build()
        val future = CompletableFuture.completedFuture(response)
        every { client.publishBatch(request) } returns future

        client.publishBatchAsync(request).join() shouldBeEqualTo response

        verify(exactly = 1) { client.publishBatch(request) }
    }

    @Test
    fun `publishBatchSuspend는 원본 cancellation을 전달한다`() = runTest {
        val client = mockk<SnsAsyncClient>()
        val request = PublishBatchRequest.builder()
            .topicArn("arn:aws:sns:ap-northeast-2:000000000000:topic")
            .publishBatchRequestEntries(
                publishBatchRequestEntryOf("entry-1", "message-1"),
            )
            .build()
        val future = CompletableFuture<PublishBatchResponse>().also { it.cancel(false) }
        every { client.publishBatch(request) } returns future

        assertThrows<CancellationException> {
            client.publishBatchSuspend(request)
        }
    }
}
