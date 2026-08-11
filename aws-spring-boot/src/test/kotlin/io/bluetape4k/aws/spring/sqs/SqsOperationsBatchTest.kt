package io.bluetape4k.aws.spring.sqs

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.jupiter.api.Test
import software.amazon.awssdk.services.sqs.model.ChangeMessageVisibilityResponse
import software.amazon.awssdk.services.sqs.SqsAsyncClient
import software.amazon.awssdk.services.sqs.model.BatchResultErrorEntry
import software.amazon.awssdk.services.sqs.model.ChangeMessageVisibilityBatchRequest
import software.amazon.awssdk.services.sqs.model.ChangeMessageVisibilityBatchResponse
import software.amazon.awssdk.services.sqs.model.ChangeMessageVisibilityBatchResultEntry
import software.amazon.awssdk.services.sqs.model.DeleteMessageBatchRequest
import software.amazon.awssdk.services.sqs.model.DeleteMessageBatchResponse
import software.amazon.awssdk.services.sqs.model.DeleteMessageBatchResultEntry
import software.amazon.awssdk.services.sqs.model.DeleteMessageResponse
import software.amazon.awssdk.services.sqs.model.QueueAttributeName
import software.amazon.awssdk.services.sqs.model.SendMessageResponse
import java.util.concurrent.CompletableFuture
import java.util.function.Consumer

class SqsOperationsBatchTest {

    @Test
    fun `default deleteBatch falls back to single delete in input order`() = runSuspendIO {
        val operations = FallbackOperations()

        val result = operations.deleteBatch(QUEUE_URL, listOf("receipt-1", "receipt-2", "receipt-3"))

        result.successfulEntryIds shouldBeEqualTo listOf("entry-0", "entry-1", "entry-2")
        operations.deleteRequests shouldBeEqualTo listOf("receipt-1", "receipt-2", "receipt-3")
    }

    @Test
    fun `default visibility batch falls back to single visibility in input order`() = runSuspendIO {
        val operations = FallbackOperations()
        val requests = listOf(
            SqsChangeVisibilityRequest("message-1", "receipt-1", 10),
            SqsChangeVisibilityRequest("message-2", "receipt-2", 20),
        )

        val result = operations.changeVisibilityBatch(QUEUE_URL, requests)

        result.successfulMessageIds shouldBeEqualTo listOf("message-1", "message-2")
        operations.visibilityRequests.map { it.receiptHandle } shouldBeEqualTo listOf("receipt-1", "receipt-2")
    }

    @Test
    fun `batch validation happens before fallback AWS calls`() = runSuspendIO {
        val operations = FallbackOperations()

        assertFailsWith<IllegalArgumentException> {
            operations.deleteBatch(QUEUE_URL, listOf("receipt-1", "receipt-1"))
        }
        assertFailsWith<IllegalArgumentException> {
            operations.changeVisibilityBatch(
                QUEUE_URL,
                listOf(SqsChangeVisibilityRequest("message-1", "receipt-1", -1)),
            )
        }
        operations.deleteRequests shouldBeEqualTo emptyList()
        operations.visibilityRequests shouldBeEqualTo emptyList()
    }

    @Test
    fun `template sends one delete batch request and maps successful entries`() = runSuspendIO {
        val client = mockk<SqsAsyncClient>()
        val requests = mutableListOf<DeleteMessageBatchRequest>()
        every { client.deleteMessageBatch(any<Consumer<DeleteMessageBatchRequest.Builder>>()) } answers {
            val builder = DeleteMessageBatchRequest.builder()
            firstArg<Consumer<DeleteMessageBatchRequest.Builder>>().accept(builder)
            requests += builder.build()
            CompletableFuture.completedFuture(
                DeleteMessageBatchResponse.builder()
                    .successful(
                        DeleteMessageBatchResultEntry.builder().id("entry-0").build(),
                        DeleteMessageBatchResultEntry.builder().id("entry-1").build(),
                    )
                    .build()
            )
        }
        val template = SqsCoroutinesTemplate(client, SqsProperties())

        val result = template.deleteBatch(QUEUE_URL, listOf("receipt-1", "receipt-2"))

        result.successfulEntryIds shouldBeEqualTo listOf("entry-0", "entry-1")
        requests.single().entries().map { it.id() } shouldBeEqualTo listOf("entry-0", "entry-1")
        requests.single().entries().map { it.receiptHandle() } shouldBeEqualTo listOf("receipt-1", "receipt-2")
    }

    @Test
    fun `template sends one visibility batch request and preserves item failures`() = runSuspendIO {
        val client = mockk<SqsAsyncClient>()
        val requests = mutableListOf<ChangeMessageVisibilityBatchRequest>()
        every {
            client.changeMessageVisibilityBatch(
                any<Consumer<ChangeMessageVisibilityBatchRequest.Builder>>(),
            )
        } answers {
            val builder = ChangeMessageVisibilityBatchRequest.builder()
            firstArg<Consumer<ChangeMessageVisibilityBatchRequest.Builder>>().accept(builder)
            requests += builder.build()
            CompletableFuture.completedFuture(
                ChangeMessageVisibilityBatchResponse.builder()
                    .successful(ChangeMessageVisibilityBatchResultEntry.builder().id("entry-1").build())
                    .failed(
                        BatchResultErrorEntry.builder()
                            .id("entry-0")
                            .code("visibility-failed")
                            .message("rejected")
                            .senderFault(false)
                            .build()
                    )
                    .build()
            )
        }
        val template = SqsCoroutinesTemplate(client, SqsProperties())

        val result = template.changeVisibilityBatch(
            QUEUE_URL,
            listOf(
                SqsChangeVisibilityRequest("message-1", "receipt-1", 0),
                SqsChangeVisibilityRequest("message-2", "receipt-2", 30),
            ),
        )

        result.successfulMessageIds shouldBeEqualTo listOf("message-2")
        result.failed.single().messageId shouldBeEqualTo "message-1"
        requests.single().entries().map { it.id() } shouldBeEqualTo listOf("entry-0", "entry-1")
        requests.single().entries().map { it.visibilityTimeout() } shouldBeEqualTo listOf(0, 30)
    }

    @Test
    fun `template rejects incomplete delete batch response`() = runSuspendIO {
        val client = mockk<SqsAsyncClient>()
        every { client.deleteMessageBatch(any<Consumer<DeleteMessageBatchRequest.Builder>>()) } returns
            CompletableFuture.completedFuture(
                DeleteMessageBatchResponse.builder()
                    .successful(DeleteMessageBatchResultEntry.builder().id("entry-0").build())
                    .build()
            )
        val template = SqsCoroutinesTemplate(client, SqsProperties())

        assertFailsWith<SqsBatchDeleteProtocolException> {
            template.deleteBatch(QUEUE_URL, listOf("receipt-1", "receipt-2"))
        }
    }

    private class FallbackOperations : SqsOperations {
        val deleteRequests = mutableListOf<String>()
        val visibilityRequests = mutableListOf<SqsChangeVisibilityRequest>()

        override suspend fun getQueueUrl(queueName: String): String = QUEUE_URL
        override suspend fun createQueue(
            queueName: String,
            attributes: Map<QueueAttributeName, String>,
        ): String = QUEUE_URL
        override suspend fun createConfiguredQueue(queueName: String): String = QUEUE_URL
        override suspend fun send(queueUrl: String, body: String, delaySeconds: Int?): SendMessageResponse =
            SendMessageResponse.builder().build()
        override suspend fun receive(
            queueUrl: String,
            maxMessages: Int,
            waitTimeSeconds: Int,
            visibilityTimeoutSeconds: Int?,
        ): List<SqsReceivedMessage> = emptyList()
        override suspend fun delete(queueUrl: String, receiptHandle: String): DeleteMessageResponse {
            deleteRequests += receiptHandle
            return DeleteMessageResponse.builder().build()
        }
        override suspend fun changeVisibility(
            queueUrl: String,
            receiptHandle: String,
            timeoutSeconds: Int,
        ): ChangeMessageVisibilityResponse {
            visibilityRequests += SqsChangeVisibilityRequest(
                "message-${visibilityRequests.size + 1}",
                receiptHandle,
                timeoutSeconds,
            )
            return ChangeMessageVisibilityResponse.builder().build()
        }
        override fun receiveFlow(
            queueUrl: String,
            maxMessages: Int,
            waitTimeSeconds: Int,
            visibilityTimeoutSeconds: Int?,
        ): Flow<SqsReceivedMessage> = emptyFlow()
    }

    companion object {
        private const val QUEUE_URL = "https://sqs.local/orders"
    }
}
