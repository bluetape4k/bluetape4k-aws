package io.bluetape4k.aws.spring.sqs

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.junit5.coroutines.runSuspendIO
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import software.amazon.awssdk.services.sqs.model.ChangeMessageVisibilityResponse
import software.amazon.awssdk.services.sqs.model.DeleteMessageResponse
import software.amazon.awssdk.services.sqs.model.Message
import software.amazon.awssdk.services.sqs.model.QueueAttributeName
import software.amazon.awssdk.services.sqs.model.SendMessageResponse
import java.util.concurrent.CopyOnWriteArrayList

class SqsBatchAcknowledgementTest {

    @Test
    fun `acknowledge deletes all pending and completes`() = runSuspendIO {
        val messages = messages(3)
        val operations = RecordingBatchOperations()
        val acknowledgement = acknowledgement(messages, operations)

        val result = acknowledgement.acknowledge()

        result.operation shouldBeEqualTo SqsBatchAcknowledgementOperation.ACKNOWLEDGE
        result.status shouldBeEqualTo SqsBatchAcknowledgementStatus.SUCCESS
        result.successfulMessageIds shouldBeEqualTo listOf("message-1", "message-2", "message-3")
        acknowledgement.pending shouldBeEqualTo emptyList()
        acknowledgement.completed.shouldBeTrue()
        operations.deleteBatchCalls shouldBeEqualTo 1
        operations.deleteRequests.single() shouldBeEqualTo listOf("receipt-1", "receipt-2", "receipt-3")
    }

    @Test
    fun `partial delete keeps failed item pending`() = runSuspendIO {
        val messages = messages(3)
        val operations = RecordingBatchOperations().apply {
            deleteBatchResult = SqsBatchDeleteResult(
                successfulEntryIds = listOf("entry-0", "entry-2"),
                failed = listOf(SqsBatchDeleteFailure("entry-1", "AccessDenied", "denied", true)),
            )
        }
        val acknowledgement = acknowledgement(messages, operations)

        val result = acknowledgement.acknowledge()

        result.status shouldBeEqualTo SqsBatchAcknowledgementStatus.PARTIAL_FAILURE
        result.successfulMessageIds shouldBeEqualTo listOf("message-1", "message-3")
        result.failed.single().messageId shouldBeEqualTo "message-2"
        acknowledgement.pending.map { it.messageId } shouldBeEqualTo listOf("message-2")
        acknowledgement.completed.shouldBeFalse()
    }

    @Test
    fun `nack success becomes deferred and does not delete`() = runSuspendIO {
        val messages = messages(2)
        val operations = RecordingBatchOperations()
        val acknowledgement = acknowledgement(messages, operations)

        val result = acknowledgement.nack(messages, timeoutSeconds = 30)

        result.operation shouldBeEqualTo SqsBatchAcknowledgementOperation.NACK
        result.status shouldBeEqualTo SqsBatchAcknowledgementStatus.SUCCESS
        acknowledgement.pending shouldBeEqualTo emptyList()
        acknowledgement.completed.shouldBeTrue()
        operations.deleteBatchCalls shouldBeEqualTo 0
        operations.visibilityRequests.single().map { it.timeoutSeconds } shouldBeEqualTo listOf(30, 30)
    }

    @Test
    fun `change visibility keeps messages pending`() = runSuspendIO {
        val messages = messages(1)
        val operations = RecordingBatchOperations()
        val acknowledgement = acknowledgement(messages, operations)

        val result = acknowledgement.changeVisibility(messages, timeoutSeconds = 15)

        result.operation shouldBeEqualTo SqsBatchAcknowledgementOperation.CHANGE_VISIBILITY
        result.status shouldBeEqualTo SqsBatchAcknowledgementStatus.SUCCESS
        acknowledgement.pending.map { it.messageId } shouldBeEqualTo listOf("message-1")
        acknowledgement.completed.shouldBeFalse()
    }

    @Test
    fun `concurrent duplicate ack is linearized`() = runTest {
        val messages = messages(2)
        val operations = RecordingBatchOperations()
        val acknowledgement = acknowledgement(messages, operations)

        val results = listOf(
            async { acknowledgement.acknowledge(messages) },
            async { acknowledgement.acknowledge(messages) },
        ).awaitAll()

        operations.deleteBatchCalls shouldBeEqualTo 1
        results.forEach { it.successfulMessageIds shouldBeEqualTo listOf("message-1", "message-2") }
        acknowledgement.completed.shouldBeTrue()
    }

    @Test
    fun `foreign and eleven item inputs fail before AWS`() = runSuspendIO {
        val messages = messages(2)
        val operations = RecordingBatchOperations()
        val acknowledgement = acknowledgement(messages, operations)

        assertFailsWith<IllegalArgumentException> {
            acknowledgement.acknowledge(messages + message(99))
        }
        assertFailsWith<IllegalArgumentException> {
            acknowledgement.acknowledge(List(11) { messages.first() })
        }
        operations.deleteBatchCalls shouldBeEqualTo 0
    }

    @Test
    fun `fifo predecessor blocks later acknowledgement`() = runSuspendIO {
        val messages = messages(2, groupId = "group-1")
        val operations = RecordingBatchOperations()
        val acknowledgement = acknowledgement(messages, operations)

        val result = acknowledgement.acknowledge(listOf(messages[1]))

        result.status shouldBeEqualTo SqsBatchAcknowledgementStatus.FAILURE
        result.failed.single().code shouldBeEqualTo "fifo_predecessor_pending"
        operations.deleteBatchCalls shouldBeEqualTo 0
        acknowledgement.pending.map { it.messageId } shouldBeEqualTo listOf("message-1", "message-2")
    }

    @Test
    fun `result string does not expose message identifiers or handles`() = runSuspendIO {
        val messages = messages(1)
        val operations = RecordingBatchOperations()
        val acknowledgement = acknowledgement(messages, operations)

        val result = acknowledgement.acknowledge()
        val rendered = "${result}${result.failed}"

        rendered shouldContain "SqsBatchAcknowledgementResult"
        check("message-1" !in rendered)
        check("receipt-1" !in rendered)
        check("payload-1" !in rendered)
    }

    @Test
    fun `operation guard cancels before an AWS batch call`() = runSuspendIO {
        val operations = RecordingBatchOperations()
        val acknowledgement = DefaultSqsBatchAcknowledgement(
            listenerId = "listener",
            queueUrl = QUEUE_URL,
            messages = messages(2),
            operations = operations,
            interceptors = emptyList(),
            operationGuard = { throw CancellationException("listener is stopping") },
        )

        assertFailsWith<CancellationException> { acknowledgement.acknowledge() }

        operations.deleteBatchCalls shouldBeEqualTo 0
        acknowledgement.pending.size shouldBeEqualTo 2
        acknowledgement.completed.shouldBeFalse()
    }

    private fun acknowledgement(
        messages: List<SqsReceivedMessage>,
        operations: RecordingBatchOperations,
    ): DefaultSqsBatchAcknowledgement =
        DefaultSqsBatchAcknowledgement(
            listenerId = "listener",
            queueUrl = QUEUE_URL,
            messages = messages,
            operations = operations,
            interceptors = emptyList(),
        )

    private fun messages(count: Int, groupId: String? = null): List<SqsReceivedMessage> =
        (1..count).map { message(it, groupId) }

    private fun message(index: Int, groupId: String? = null): SqsReceivedMessage {
        val builder = Message.builder()
            .messageId("message-$index")
            .receiptHandle("receipt-$index")
            .body("payload-$index")
        groupId?.let {
            builder.attributes(
                mapOf(software.amazon.awssdk.services.sqs.model.MessageSystemAttributeName.MESSAGE_GROUP_ID to it)
            )
        }
        return SqsReceivedMessage(QUEUE_URL, builder.build())
    }

    private class RecordingBatchOperations : SqsOperations {
        var deleteBatchCalls = 0
        var deleteBatchResult = SqsBatchDeleteResult(emptyList(), emptyList())
        val deleteRequests = CopyOnWriteArrayList<List<String>>()
        val visibilityRequests = CopyOnWriteArrayList<List<SqsChangeVisibilityRequest>>()

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
        override suspend fun delete(queueUrl: String, receiptHandle: String): DeleteMessageResponse =
            DeleteMessageResponse.builder().build()
        override suspend fun changeVisibility(
            queueUrl: String,
            receiptHandle: String,
            timeoutSeconds: Int,
        ): ChangeMessageVisibilityResponse = ChangeMessageVisibilityResponse.builder().build()
        override fun receiveFlow(
            queueUrl: String,
            maxMessages: Int,
            waitTimeSeconds: Int,
            visibilityTimeoutSeconds: Int?,
        ): Flow<SqsReceivedMessage> = emptyFlow()

        override suspend fun deleteBatch(
            queueUrl: String,
            receiptHandles: Collection<String>,
        ): SqsBatchDeleteResult {
            deleteBatchCalls++
            deleteRequests += receiptHandles.toList()
            return if (deleteBatchResult.successfulEntryIds.isEmpty() && deleteBatchResult.failed.isEmpty()) {
                SqsBatchDeleteResult(receiptHandles.indices.map { "entry-$it" }, emptyList())
            } else {
                deleteBatchResult
            }
        }

        override suspend fun changeVisibilityBatch(
            queueUrl: String,
            requests: Collection<SqsChangeVisibilityRequest>,
        ): SqsBatchVisibilityResult {
            visibilityRequests += requests.toList()
            return SqsBatchVisibilityResult(requests.map { it.messageId }, emptyList())
        }
    }

    companion object {
        private const val QUEUE_URL = "https://sqs.local/orders"
    }
}
