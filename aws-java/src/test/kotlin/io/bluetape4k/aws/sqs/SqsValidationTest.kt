package io.bluetape4k.aws.sqs

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.aws.sqs.model.receiveMessageRequestOf
import org.junit.jupiter.api.Test
import software.amazon.awssdk.services.sqs.model.ChangeMessageVisibilityBatchRequestEntry
import software.amazon.awssdk.services.sqs.model.DeleteMessageBatchRequestEntry
import software.amazon.awssdk.services.sqs.model.SendMessageBatchRequestEntry

class SqsValidationTest: AbstractSqsTest() {

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
            client.sendBatch(queueUrl, entries = emptyList<SendMessageBatchRequestEntry>())
        }
        assertFailsWith<IllegalArgumentException> {
            client.changeMessageVisibilityBatch(
                queueUrl,
                entries = emptyList<ChangeMessageVisibilityBatchRequestEntry>()
            )
        }
        assertFailsWith<IllegalArgumentException> {
            client.deleteMessageBatch(queueUrl, entries = emptyList<DeleteMessageBatchRequestEntry>())
        }

        assertFailsWith<IllegalArgumentException> {
            asyncClient.sendBatchAsync(queueUrl, entries = emptyList<SendMessageBatchRequestEntry>())
        }
        assertFailsWith<IllegalArgumentException> {
            asyncClient.changeMessageVisibilityBatchAsync(
                queueUrl,
                entries = emptyList<ChangeMessageVisibilityBatchRequestEntry>()
            )
        }
        assertFailsWith<IllegalArgumentException> {
            asyncClient.deleteMessageBatchAsync(queueUrl, entries = emptyList<DeleteMessageBatchRequestEntry>())
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

}
