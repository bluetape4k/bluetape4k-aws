package io.bluetape4k.aws.sqs

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.aws.sqs.model.receiveMessageRequestOf
import io.bluetape4k.aws.sqs.model.sendMessageBatchRequestEntryOf
import io.bluetape4k.aws.sqs.model.sendMessageRequestOf
import io.bluetape4k.logging.KLogging
import io.mockk.clearAllMocks
import io.mockk.mockk
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import software.amazon.awssdk.services.sqs.SqsAsyncClient
import software.amazon.awssdk.services.sqs.SqsClient
import software.amazon.awssdk.services.sqs.model.ChangeMessageVisibilityBatchRequestEntry
import software.amazon.awssdk.services.sqs.model.DeleteMessageBatchRequestEntry
import software.amazon.awssdk.services.sqs.model.SendMessageBatchRequestEntry

class SqsValidationTest: AbstractSqsTest() {

    companion object: KLogging()

    private val sync = mockk<SqsClient>(relaxed = true)
    private val async = mockk<SqsAsyncClient>(relaxed = true)

    @BeforeEach
    fun beforeEach() {
        clearAllMocks()
    }

    @Test
    fun `receiveMessages validates maxResults range in sync client`() {
        val queueUrl = "https://example.com/queue/demo"

        assertFailsWith<IllegalArgumentException> {
            client.receiveMessages(queueUrl, maxResults = 0)
        }
        assertFailsWith<IllegalArgumentException> {
            client.receiveMessages(queueUrl, maxResults = 11)
        }
    }

    @Test
    fun `receiveMessages validates maxResults range in async client`() {
        val queueUrl = "https://example.com/queue/demo"

        assertFailsWith<IllegalArgumentException> {
            asyncClient.receiveMessagesAsync(queueUrl, maxResults = 0)
        }
        assertFailsWith<IllegalArgumentException> {
            asyncClient.receiveMessagesAsync(queueUrl, maxResults = 11)
        }
    }

    @Test
    fun `batch operations reject empty entries`() {
        val queueUrl = "https://example.com/queue/demo"

        assertFailsWith<IllegalArgumentException> {
            client.sendBatch(queueUrl, entries = emptyList())
        }
        assertFailsWith<IllegalArgumentException> {
            client.changeMessageVisibilityBatch(queueUrl, entries = emptyList())
        }
        assertFailsWith<IllegalArgumentException> {
            client.deleteMessageBatch(queueUrl, entries = emptyList())
        }

        assertFailsWith<IllegalArgumentException> {
            asyncClient.sendBatchAsync(queueUrl, entries = emptyList())
        }
        assertFailsWith<IllegalArgumentException> {
            asyncClient.changeMessageVisibilityBatchAsync(queueUrl, entries = emptyList())
        }
        assertFailsWith<IllegalArgumentException> {
            asyncClient.deleteMessageBatchAsync(queueUrl, entries = emptyList())
        }
    }

    @Test
    fun `batch operations reject more than ten entries`() {
        val queueUrl = "https://example.com/queue/demo"

        val sendEntries = tooManySendEntries()
        val visibilityEntries = tooManyVisibilityEntries()
        val deleteEntries = tooManyDeleteEntries()

        assertFailsWith<IllegalArgumentException> {
            sync.sendBatch(queueUrl, sendEntries)
        }
        assertFailsWith<IllegalArgumentException> {
            sync.sendBatch(queueUrl, *sendEntries.toTypedArray())
        }
        assertFailsWith<IllegalArgumentException> {
            sync.changeMessageVisibilityBatch(queueUrl, visibilityEntries)
        }
        assertFailsWith<IllegalArgumentException> {
            sync.changeMessageVisibilityBatch(queueUrl, *visibilityEntries.toTypedArray())
        }
        assertFailsWith<IllegalArgumentException> {
            sync.deleteMessageBatch(queueUrl, deleteEntries)
        }
        assertFailsWith<IllegalArgumentException> {
            sync.deleteMessageBatch(queueUrl, *deleteEntries.toTypedArray())
        }
        assertFailsWith<IllegalArgumentException> {
            async.sendBatchAsync(queueUrl, sendEntries)
        }
        assertFailsWith<IllegalArgumentException> {
            async.sendBatchAsync(queueUrl, *sendEntries.toTypedArray())
        }
        assertFailsWith<IllegalArgumentException> {
            async.changeMessageVisibilityBatchAsync(queueUrl, visibilityEntries)
        }
        assertFailsWith<IllegalArgumentException> {
            async.changeMessageVisibilityBatchAsync(queueUrl, *visibilityEntries.toTypedArray())
        }
        assertFailsWith<IllegalArgumentException> {
            async.deleteMessageBatchAsync(queueUrl, deleteEntries)
        }
        assertFailsWith<IllegalArgumentException> {
            async.deleteMessageBatchAsync(queueUrl, *deleteEntries.toTypedArray())
        }
    }

    @Test
    fun `send message helpers validate delay seconds`() {
        val queueUrl = "https://example.com/queue/demo"

        assertFailsWith<IllegalArgumentException> {
            sendMessageRequestOf(queueUrl, "message", delaySeconds = -1)
        }
        assertFailsWith<IllegalArgumentException> {
            sendMessageRequestOf(queueUrl, "message", delaySeconds = 901)
        }
        assertFailsWith<IllegalArgumentException> {
            sendMessageRequestOf(queueUrl, "message", delaySeconds = 1) {
                delaySeconds(901)
            }
        }
        assertFailsWith<IllegalArgumentException> {
            sendMessageBatchRequestEntryOf("id", "group", "message", delaySeconds = -1)
        }
        assertFailsWith<IllegalArgumentException> {
            sendMessageBatchRequestEntryOf("id", "group", "message", delaySeconds = 901)
        }
        assertFailsWith<IllegalArgumentException> {
            sendMessageBatchRequestEntryOf("id", "group", "message", delaySeconds = 1) {
                delaySeconds(901)
            }
        }
    }

    @Test
    fun `changeMessageVisibility validates visibility timeout`() {
        val queueUrl = "https://example.com/queue/demo"

        assertFailsWith<IllegalArgumentException> {
            sync.changeMessageVisibility(queueUrl, visibilityTimeout = -1)
        }
        assertFailsWith<IllegalArgumentException> {
            sync.changeMessageVisibility(queueUrl, visibilityTimeout = 43_201)
        }
        assertFailsWith<IllegalArgumentException> {
            async.changeMessageVisibilityAsync(queueUrl, visibilityTimeout = -1)
        }
        assertFailsWith<IllegalArgumentException> {
            async.changeMessageVisibilityAsync(queueUrl, visibilityTimeout = 43_201)
        }
    }

    @Test
    fun `receiveMessageRequestOf validates maxNumber and waitTimeSeconds`() {
        val queueUrl = "https://example.com/queue/demo"

        assertFailsWith<IllegalArgumentException> {
            receiveMessageRequestOf(queueUrl, maxNumber = 0)
        }
        assertFailsWith<IllegalArgumentException> {
            receiveMessageRequestOf(queueUrl, maxNumber = 11)
        }
        assertFailsWith<IllegalArgumentException> {
            receiveMessageRequestOf(queueUrl, waitTimeSeconds = -1)
        }
        assertFailsWith<IllegalArgumentException> {
            receiveMessageRequestOf(queueUrl, waitTimeSeconds = 21)
        }
    }

    private fun tooManySendEntries(): List<SendMessageBatchRequestEntry> =
        List(11) { index ->
            SendMessageBatchRequestEntry.builder()
                .id("send-$index")
                .messageBody("message-$index")
                .build()
        }

    private fun tooManyVisibilityEntries(): List<ChangeMessageVisibilityBatchRequestEntry> =
        List(11) { index ->
            ChangeMessageVisibilityBatchRequestEntry.builder()
                .id("visibility-$index")
                .receiptHandle("receipt-$index")
                .visibilityTimeout(30)
                .build()
        }

    private fun tooManyDeleteEntries(): List<DeleteMessageBatchRequestEntry> =
        List(11) { index ->
            DeleteMessageBatchRequestEntry.builder()
                .id("delete-$index")
                .receiptHandle("receipt-$index")
                .build()
        }
}
