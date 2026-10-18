package io.bluetape4k.aws.spring.modulith

import io.bluetape4k.assertions.assertFails
import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldBeSameInstanceAs
import io.bluetape4k.assertions.shouldNotContain
import io.bluetape4k.aws.spring.sqs.SqsAcknowledgement
import io.bluetape4k.aws.spring.sqs.SqsReceivedMessage
import io.bluetape4k.logging.KLogging
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import software.amazon.awssdk.services.sqs.model.Message
import java.util.concurrent.atomic.AtomicInteger

class AwsModulithSqsEventListenerTest {

    private companion object: KLogging() {
        const val HOSTILE = "secret:event-id:arn:payload"
    }

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

        val actual = assertFails {
            listener.onMessage(message(), acknowledgement(acknowledgements))
        }

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

        val sanitized = assertFailsWith<AwsModulithAcknowledgementException> {
            listener.onMessage(message(), raw)
        }

        sanitized.cause.shouldBeNull()
        sanitized.message shouldNotContain HOSTILE
        completed.get() shouldBeEqualTo 1

        registry.get("bluetape4k.aws.modulith.events")
            .tag("phase", "ack")
            .tag("outcome", "failure")
            .counter()
            .count() shouldBeEqualTo 1.0

        assertFailsWith<AwsModulithAcknowledgementException> {
            listener.onMessage(message(), acknowledgement(failure = AwsModulithSourceException()))
        }

        val duplicateListener = AwsModulithSqsEventListener(
            consumeEvent = { AwsModulithConsumeOutcome.COMPLETED_DUPLICATE }
        )

        assertFailsWith<AwsModulithAcknowledgementException> {
            duplicateListener.onMessage(message(), acknowledgement(failure = IllegalStateException(HOSTILE)))
        }

        val cancellation = CancellationException(HOSTILE)
        val cancelled = assertFails {
            duplicateListener.onMessage(message(), acknowledgement(failure = cancellation))
        }
        cancelled shouldBeSameInstanceAs cancellation
    }

    private fun acknowledgement(
        calls: AtomicInteger = AtomicInteger(),
        failure: Throwable? = null,
    ): SqsAcknowledgement = object: SqsAcknowledgement {
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
        message = Message.builder()
            .messageId("message-1")
            .receiptHandle("receipt")
            .body("{}")
            .build(),
    )
}
