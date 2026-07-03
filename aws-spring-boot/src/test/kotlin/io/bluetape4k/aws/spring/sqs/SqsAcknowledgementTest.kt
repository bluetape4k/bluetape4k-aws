package io.bluetape4k.aws.spring.sqs

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.junit5.coroutines.runSuspendIO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.jupiter.api.Test
import software.amazon.awssdk.services.sqs.model.ChangeMessageVisibilityResponse
import software.amazon.awssdk.services.sqs.model.DeleteMessageResponse
import software.amazon.awssdk.services.sqs.model.Message
import software.amazon.awssdk.services.sqs.model.QueueAttributeName
import software.amazon.awssdk.services.sqs.model.SendMessageResponse

class SqsAcknowledgementTest {

    @Test
    fun `acknowledge marks completed only after delete succeeds`() = runSuspendIO {
        val operations = RecordingSqsOperations()
        val interceptor = RecordingInterceptor()
        val acknowledgement = acknowledgement(operations, interceptor)

        acknowledgement.completed.shouldBeFalse()

        acknowledgement.acknowledge()

        acknowledgement.completed.shouldBeTrue()
        operations.deleteCalls shouldBeEqualTo 1
        interceptor.afterFailures shouldBeEqualTo listOf(null)
    }

    @Test
    fun `failed acknowledge leaves acknowledgement incomplete and retryable`() = runSuspendIO {
        val operations = RecordingSqsOperations().apply {
            deleteFailure = IllegalStateException("delete failed")
        }
        val interceptor = RecordingInterceptor()
        val acknowledgement = acknowledgement(operations, interceptor)

        assertFailsWith<IllegalStateException> {
            acknowledgement.acknowledge()
        }

        acknowledgement.completed.shouldBeFalse()
        operations.deleteCalls shouldBeEqualTo 1
        interceptor.afterFailures.single()?.message shouldBeEqualTo "delete failed"

        operations.deleteFailure = null
        acknowledgement.acknowledge()

        acknowledgement.completed.shouldBeTrue()
        operations.deleteCalls shouldBeEqualTo 2
    }

    @Test
    fun `failed nack leaves acknowledgement incomplete and retryable`() = runSuspendIO {
        val operations = RecordingSqsOperations().apply {
            changeVisibilityFailure = IllegalStateException("visibility failed")
        }
        val acknowledgement = acknowledgement(operations)

        assertFailsWith<IllegalStateException> {
            acknowledgement.nack(timeoutSeconds = 0)
        }

        acknowledgement.completed.shouldBeFalse()
        operations.changeVisibilityCalls shouldBeEqualTo 1

        operations.changeVisibilityFailure = null
        acknowledgement.nack(timeoutSeconds = 0)

        acknowledgement.completed.shouldBeTrue()
        operations.changeVisibilityCalls shouldBeEqualTo 2
    }

    @Test
    fun `changeVisibility does not complete acknowledgement`() = runSuspendIO {
        val operations = RecordingSqsOperations()
        val acknowledgement = acknowledgement(operations)

        acknowledgement.changeVisibility(timeoutSeconds = 5)

        acknowledgement.completed.shouldBeFalse()
        operations.changeVisibilityCalls shouldBeEqualTo 1
    }

    private fun acknowledgement(
        operations: RecordingSqsOperations,
        interceptor: SqsListenerInterceptor = RecordingInterceptor(),
    ): DefaultSqsAcknowledgement =
        DefaultSqsAcknowledgement(
            context = SqsListenerInvocationContext(
                listenerId = "listener",
                queueUrl = "https://sqs.us-east-1.amazonaws.com/123/orders",
                message = SqsReceivedMessage(
                    queueUrl = "https://sqs.us-east-1.amazonaws.com/123/orders",
                    message = Message.builder()
                        .messageId("message-1")
                        .body("payload")
                        .receiptHandle("receipt-1")
                        .build(),
                ),
                attempt = 1,
            ),
            operations = operations,
            interceptors = listOf(interceptor),
        )

    private class RecordingInterceptor : SqsListenerInterceptor {
        val afterFailures = mutableListOf<Throwable?>()

        override suspend fun afterAcknowledgement(
            context: SqsListenerInvocationContext,
            action: SqsAcknowledgementAction,
            error: Throwable?,
        ) {
            afterFailures += error
        }
    }

    private class RecordingSqsOperations : SqsOperations {
        var deleteCalls = 0
        var changeVisibilityCalls = 0
        var deleteFailure: Throwable? = null
        var changeVisibilityFailure: Throwable? = null

        override suspend fun getQueueUrl(queueName: String): String = "queue-url"

        override suspend fun createQueue(
            queueName: String,
            attributes: Map<QueueAttributeName, String>,
        ): String = "queue-url"

        override suspend fun createConfiguredQueue(queueName: String): String = "queue-url"

        override suspend fun send(
            queueUrl: String,
            body: String,
            delaySeconds: Int?,
        ): SendMessageResponse =
            SendMessageResponse.builder().messageId("message-id").build()

        override suspend fun receive(
            queueUrl: String,
            maxMessages: Int,
            waitTimeSeconds: Int,
            visibilityTimeoutSeconds: Int?,
        ): List<SqsReceivedMessage> = emptyList()

        override suspend fun delete(
            queueUrl: String,
            receiptHandle: String,
        ): DeleteMessageResponse {
            deleteCalls++
            deleteFailure?.let { throw it }
            return DeleteMessageResponse.builder().build()
        }

        override suspend fun changeVisibility(
            queueUrl: String,
            receiptHandle: String,
            timeoutSeconds: Int,
        ): ChangeMessageVisibilityResponse {
            changeVisibilityCalls++
            changeVisibilityFailure?.let { throw it }
            return ChangeMessageVisibilityResponse.builder().build()
        }

        override fun receiveFlow(
            queueUrl: String,
            maxMessages: Int,
            waitTimeSeconds: Int,
            visibilityTimeoutSeconds: Int?,
        ): Flow<SqsReceivedMessage> =
            emptyFlow()
    }
}
