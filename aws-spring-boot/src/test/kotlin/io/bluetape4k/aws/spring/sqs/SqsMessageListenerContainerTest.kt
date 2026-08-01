package io.bluetape4k.aws.spring.sqs

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import software.amazon.awssdk.services.sqs.model.DeleteMessageResponse
import software.amazon.awssdk.services.sqs.model.Message
import java.util.concurrent.atomic.AtomicInteger

class SqsMessageListenerContainerTest {

    @Test
    fun `stop drains active handler without waiting for a restarted generation`() = runSuspendIO {
        val operations = mockk<SqsOperations>()
        val invoker = mockk<SqsListenerMethodInvoker>()
        val handlerStarted = CompletableDeferred<Unit>()
        val handlerRelease = CompletableDeferred<Unit>()
        val handlerCompleted = CompletableDeferred<Unit>()
        val receiveCalls = AtomicInteger(0)
        coEvery { operations.receive(QUEUE_URL, 1, 0, null) } coAnswers {
            if (receiveCalls.incrementAndGet() == 1) listOf(message()) else awaitCancellation()
        }
        coEvery { operations.delete(any(), any()) } returns DeleteMessageResponse.builder().build()
        every { invoker.manualAcknowledgement } returns false
        coEvery { invoker.invoke(any(), any()) } coAnswers {
            handlerStarted.complete(Unit)
            handlerRelease.await()
            handlerCompleted.complete(Unit)
        }
        val container = container(operations, invoker)

        container.start()
        withTimeout(2_000) { handlerStarted.await() }

        val firstStopCallbacks = AtomicInteger(0)
        val firstStopped = CompletableDeferred<Unit>()
        container.stop {
            firstStopCallbacks.incrementAndGet()
            firstStopped.complete(Unit)
        }
        container.start()
        handlerRelease.complete(Unit)

        withTimeout(2_000) { firstStopped.await() }

        handlerCompleted.isCompleted.shouldBeTrue()
        firstStopCallbacks.get() shouldBeEqualTo 1
        container.isRunning.shouldBeTrue()

        val secondStopped = CompletableDeferred<Unit>()
        container.stop { secondStopped.complete(Unit) }
        withTimeout(2_000) { secondStopped.await() }
    }

    @Test
    fun `stop timeout cancels handler and invokes repeated stop callbacks once`() = runSuspendIO {
        val operations = mockk<SqsOperations>()
        val invoker = mockk<SqsListenerMethodInvoker>()
        val handlerStarted = CompletableDeferred<Unit>()
        val handlerCancelled = CompletableDeferred<Unit>()
        coEvery { operations.receive(QUEUE_URL, 1, 0, null) } returns listOf(message())
        every { invoker.manualAcknowledgement } returns false
        coEvery { invoker.invoke(any(), any()) } coAnswers {
            handlerStarted.complete(Unit)
            try {
                awaitCancellation()
            } finally {
                handlerCancelled.complete(Unit)
            }
        }
        val container = container(operations, invoker, stopTimeoutMillis = 50)
        container.start()
        withTimeout(2_000) { handlerStarted.await() }

        val firstCallbacks = AtomicInteger(0)
        val secondCallbacks = AtomicInteger(0)
        val firstStopped = CompletableDeferred<Unit>()
        val secondStopped = CompletableDeferred<Unit>()
        container.stop {
            firstCallbacks.incrementAndGet()
            firstStopped.complete(Unit)
        }
        container.stop {
            secondCallbacks.incrementAndGet()
            secondStopped.complete(Unit)
        }

        withTimeout(2_000) {
            firstStopped.await()
            secondStopped.await()
            handlerCancelled.await()
        }
        firstCallbacks.get() shouldBeEqualTo 1
        secondCallbacks.get() shouldBeEqualTo 1
    }

    private fun container(
        operations: SqsOperations,
        invoker: SqsListenerMethodInvoker,
        stopTimeoutMillis: Long = 1_000,
    ): SqsMessageListenerContainer {
        return SqsMessageListenerContainer(
            endpoint = SqsListenerEndpoint(
                id = "listener",
                queue = QUEUE_URL,
                maxMessages = 1,
                waitTimeSeconds = 0,
                visibilityTimeoutSeconds = null,
                errorVisibilityTimeoutSeconds = null,
                autoStartup = true,
                phase = 0,
                concurrency = 1,
                stopTimeoutMillis = stopTimeoutMillis,
                retry = SqsProperties.Retry(),
            ),
            operations = operations,
            invoker = invoker,
            interceptors = emptyList(),
        )
    }

    private fun message(): SqsReceivedMessage =
        SqsReceivedMessage(
            queueUrl = QUEUE_URL,
            message = Message.builder()
                .messageId("message-1")
                .receiptHandle("receipt-1")
                .body("payload")
                .build(),
        )

    companion object {
        private const val QUEUE_URL = "https://sqs.local/orders"
    }
}
