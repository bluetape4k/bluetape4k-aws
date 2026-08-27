package io.bluetape4k.aws.spring.modulith

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeInstanceOf
import io.bluetape4k.assertions.shouldBeSameInstanceAs
import io.bluetape4k.aws.spring.sqs.SqsAcknowledgement
import io.bluetape4k.aws.spring.sqs.SqsReceivedMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Test
import software.amazon.awssdk.services.sqs.model.Message
import java.util.concurrent.atomic.AtomicInteger

class AwsModulithSqsEventListenerTest {

    @Test
    fun `acknowledges only a successful consumer outcome`() = runTest {
        val acknowledgements = AtomicInteger()
        val listener = AwsModulithSqsEventListener(
            consumeEvent = { AwsModulithConsumeOutcome.PROCESSED }
        )

        listener.onMessage(message(), acknowledgement(acknowledgements))

        acknowledgements.get() shouldBeEqualTo 1
    }

    @Test
    fun `consumer failure is not acknowledged`() = runTest {
        val acknowledgements = AtomicInteger()
        val failure = AwsModulithEventInProgressException()
        val listener = AwsModulithSqsEventListener(consumeEvent = { throw failure })

        val actual = kotlin.runCatching {
            listener.onMessage(message(), acknowledgement(acknowledgements))
        }.exceptionOrNull()

        actual shouldBeSameInstanceAs failure
        acknowledgements.get() shouldBeEqualTo 0
    }

    @Test
    fun `raw ack failure is sanitized while cancellation keeps identity`() = runTest {
        val completed = AtomicInteger()
        val registry = SimpleMeterRegistry()
        val listener = AwsModulithSqsEventListener(
            consumeEvent = {
                completed.incrementAndGet()
                AwsModulithConsumeOutcome.PROCESSED
            },
            metrics = AwsModulithMetrics(registry),
        )
        val raw = acknowledgement(failure = IllegalStateException(HOSTILE))
        val sanitized = kotlin.runCatching { listener.onMessage(message(), raw) }.exceptionOrNull()
            .shouldBeInstanceOf<AwsModulithAcknowledgementException>()
        sanitized.cause shouldBeEqualTo null
        sanitized.message.orEmpty().contains(HOSTILE) shouldBeEqualTo false
        completed.get() shouldBeEqualTo 1
        registry.get("bluetape4k.aws.modulith.events")
            .tag("phase", "ack")
            .tag("outcome", "failure")
            .counter()
            .count() shouldBeEqualTo 1.0

        kotlin.runCatching {
            listener.onMessage(message(), acknowledgement(failure = AwsModulithSourceException()))
        }.exceptionOrNull().shouldBeInstanceOf<AwsModulithAcknowledgementException>()

        val duplicateListener = AwsModulithSqsEventListener(
            consumeEvent = { AwsModulithConsumeOutcome.COMPLETED_DUPLICATE }
        )
        kotlin.runCatching {
            duplicateListener.onMessage(message(), acknowledgement(failure = IllegalStateException(HOSTILE)))
        }.exceptionOrNull().shouldBeInstanceOf<AwsModulithAcknowledgementException>()

        val cancellation = CancellationException(HOSTILE)
        val cancelled = kotlin.runCatching {
            duplicateListener.onMessage(message(), acknowledgement(failure = cancellation))
        }.exceptionOrNull()
        cancelled shouldBeSameInstanceAs cancellation
    }

    private fun acknowledgement(
        calls: AtomicInteger = AtomicInteger(),
        failure: Throwable? = null,
    ): SqsAcknowledgement = object : SqsAcknowledgement {
        override val completed: Boolean = false

        override suspend fun acknowledge() {
            calls.incrementAndGet()
            failure?.let { throw it }
        }

        override suspend fun nack(timeoutSeconds: Int) = Unit

        override suspend fun changeVisibility(timeoutSeconds: Int) = Unit
    }

    private fun message(): SqsReceivedMessage = SqsReceivedMessage(
        queueUrl = "http://localhost/queue/events",
        message = Message.builder().messageId("message-1").receiptHandle("receipt").body("{}").build(),
    )

    private companion object {
        const val HOSTILE = "secret:event-id:arn:payload"
    }
}
