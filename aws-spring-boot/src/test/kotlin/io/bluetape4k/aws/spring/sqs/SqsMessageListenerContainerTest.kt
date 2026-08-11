package io.bluetape4k.aws.spring.sqs

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import software.amazon.awssdk.services.sqs.model.DeleteMessageResponse
import software.amazon.awssdk.services.sqs.model.Message
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class SqsMessageListenerContainerTest {

    @Test
    fun `batch receive invokes handler once with all messages`() = runSuspendIO {
        val operations = mockk<SqsOperations>()
        val invoker = mockk<SqsListenerMethodInvoker>()
        val invocation = CompletableDeferred<List<SqsReceivedMessage>>()
        val receiveCalls = AtomicInteger(0)
        val messages = listOf(message(), message("message-2"))
        coEvery { operations.receive(QUEUE_URL, 2, 0, null) } coAnswers {
            if (receiveCalls.incrementAndGet() == 1) messages else awaitCancellation()
        }
        coEvery { operations.deleteBatch(QUEUE_URL, any()) } returns
            SqsBatchDeleteResult(listOf("entry-0", "entry-1"), emptyList())
        coEvery { invoker.invokeBatch(any(), anyNullable<SqsBatchAcknowledgement>()) } coAnswers {
            invocation.complete(firstArg<List<SqsReceivedMessage>>())
        }
        val container = container(
            operations,
            invoker,
            batch = true,
            maxMessages = 2,
            acknowledgementMode = SqsAcknowledgementMode.ON_SUCCESS,
        )

        try {
            container.start()
            withTimeout(2_000) { invocation.await() }.map { it.messageId } shouldBeEqualTo
                listOf("message-1", "message-2")
            coVerify(exactly = 1) { invoker.invokeBatch(any(), null) }
            coVerify(exactly = 1) { operations.deleteBatch(QUEUE_URL, any()) }
        } finally {
            val stopped = CompletableDeferred<Unit>()
            container.stop { stopped.complete(Unit) }
            withTimeout(2_000) { stopped.await() }
        }
    }

    @Test
    fun `manual batch acknowledgement deletes only the selected messages`() = runSuspendIO {
        val operations = mockk<SqsOperations>()
        val invoker = mockk<SqsListenerMethodInvoker>()
        val invocation = CompletableDeferred<Unit>()
        val messages = listOf(message(), message("message-2"))
        coEvery { operations.receive(QUEUE_URL, 2, 0, null) } coAnswers {
            if (!invocation.isCompleted) messages else awaitCancellation()
        }
        coEvery { operations.deleteBatch(QUEUE_URL, any()) } returns
            SqsBatchDeleteResult(listOf("entry-0"), emptyList())
        coEvery { invoker.invokeBatch(any(), anyNullable<SqsBatchAcknowledgement>()) } coAnswers {
            secondArg<SqsBatchAcknowledgement>().acknowledge(listOf(firstArg<List<SqsReceivedMessage>>().first()))
            invocation.complete(Unit)
        }
        val container = container(
            operations,
            invoker,
            batch = true,
            maxMessages = 2,
            acknowledgementMode = SqsAcknowledgementMode.MANUAL,
        )
        try {
            container.start()
            withTimeout(2_000) { invocation.await() }
            coVerify(exactly = 1) { operations.deleteBatch(QUEUE_URL, any()) }
        } finally {
            val stopped = CompletableDeferred<Unit>()
            container.stop { stopped.complete(Unit) }
            withTimeout(2_000) { stopped.await() }
        }
    }

    @Test
    fun `batch retry invokes only pending messages after partial delete`() = runSuspendIO {
        val operations = mockk<SqsOperations>()
        val invoker = mockk<SqsListenerMethodInvoker>()
        val invocations = mutableListOf<List<SqsReceivedMessage>>()
        val completed = CompletableDeferred<Unit>()
        val messages = listOf(message(), message("message-2"))
        val receiveCalls = AtomicInteger(0)
        coEvery { operations.receive(QUEUE_URL, 2, 0, null) } coAnswers {
            if (receiveCalls.incrementAndGet() == 1) messages else awaitCancellation()
        }
        coEvery { operations.deleteBatch(QUEUE_URL, any()) } coAnswers {
            if (invocations.size == 1) SqsBatchDeleteResult(
                successfulEntryIds = listOf("entry-0"),
                failed = listOf(SqsBatchDeleteFailure("entry-1", "failed", "rejected", false)),
            )
            else SqsBatchDeleteResult(listOf("entry-0"), emptyList()).also { completed.complete(Unit) }
        }
        coEvery { invoker.invokeBatch(any(), anyNullable<SqsBatchAcknowledgement>()) } coAnswers {
            invocations += firstArg<List<SqsReceivedMessage>>()
        }
        val container = container(
            operations,
            invoker,
            batch = true,
            maxMessages = 2,
            acknowledgementMode = SqsAcknowledgementMode.ON_SUCCESS,
            retry = SqsProperties.Retry(maxAttempts = 2),
        )
        try {
            container.start()
            withTimeout(2_000) { completed.await() }
            invocations.map { it.map(SqsReceivedMessage::messageId) } shouldBeEqualTo
                listOf(listOf("message-1", "message-2"), listOf("message-2"))
        } finally {
            val stopped = CompletableDeferred<Unit>()
            container.stop { stopped.complete(Unit) }
            withTimeout(2_000) { stopped.await() }
        }
    }

    @Test
    fun `container runs poller on injected dispatcher`() = runSuspendIO {
        val operations = mockk<SqsOperations>()
        val invoker = mockk<SqsListenerMethodInvoker>()
        val pollerStarted = CompletableDeferred<String>()
        val executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "sqs-container-test")
        }
        val dispatcher = executor.asCoroutineDispatcher()
        coEvery { operations.receive(QUEUE_URL, 1, 0, null) } coAnswers {
            pollerStarted.complete(Thread.currentThread().name)
            awaitCancellation()
        }
        every { invoker.manualAcknowledgement } returns false
        val container = container(operations, invoker, dispatcher = dispatcher)

        try {
            container.start()
            withTimeout(2_000) { pollerStarted.await() } shouldContain "sqs-container-test"
        } finally {
            val stopped = CompletableDeferred<Unit>()
            container.stop { stopped.complete(Unit) }
            withTimeout(2_000) { stopped.await() }
            dispatcher.close()
            executor.shutdownNow()
        }
    }

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
        assertThrows<IllegalStateException> { container.start() }
        handlerRelease.complete(Unit)

        withTimeout(2_000) { firstStopped.await() }

        container.start()

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

    @Test
    fun `queue URL lookup retries ordinary failures before polling`() = runSuspendIO {
        val operations = mockk<SqsOperations>()
        val invoker = mockk<SqsListenerMethodInvoker>()
        val lookupCalls = AtomicInteger()
        val resolved = CompletableDeferred<Unit>()
        coEvery { operations.getQueueUrl("orders") } coAnswers {
            if (lookupCalls.incrementAndGet() == 1) error("temporary lookup failure")
            QUEUE_URL
        }
        coEvery { operations.receive(QUEUE_URL, 1, 0, null) } coAnswers {
            resolved.complete(Unit)
            awaitCancellation()
        }
        every { invoker.manualAcknowledgement } returns false
        val container = container(
            operations,
            invoker,
            queue = "orders",
            retry = SqsProperties.Retry(initialBackoff = Duration.ofMillis(1)),
        )

        container.start()
        try {
            withTimeout(2_000) { resolved.await() }
            lookupCalls.get() shouldBeEqualTo 2
        } finally {
            val stopped = CompletableDeferred<Unit>()
            container.stop { stopped.complete(Unit) }
            withTimeout(2_000) { stopped.await() }
        }
    }

    @Test
    fun `fatal handler error stops generation without acknowledgement`() = runSuspendIO {
        val operations = mockk<SqsOperations>()
        val invoker = mockk<SqsListenerMethodInvoker>()
        val started = CompletableDeferred<Unit>()
        coEvery { operations.receive(QUEUE_URL, 1, 0, null) } returns listOf(message())
        every { invoker.manualAcknowledgement } returns false
        coEvery { invoker.invoke(any(), any()) } coAnswers {
            started.complete(Unit)
            throw AssertionError("fatal handler error")
        }
        val container = container(operations, invoker)

        container.start()
        withTimeout(2_000) { started.await() }
        withTimeout(2_000) {
            while (container.isRunning) delay(5)
        }
        coVerify(exactly = 0) { operations.delete(any(), any()) }
    }

    private fun container(
        operations: SqsOperations,
        invoker: SqsListenerMethodInvoker,
        stopTimeoutMillis: Long = 1_000,
        dispatcher: kotlinx.coroutines.CoroutineDispatcher = kotlinx.coroutines.Dispatchers.IO,
        queue: String = QUEUE_URL,
        batch: Boolean = false,
        maxMessages: Int = 1,
        acknowledgementMode: SqsAcknowledgementMode = SqsAcknowledgementMode.INHERIT,
        retry: SqsProperties.Retry = SqsProperties.Retry(),
    ): SqsMessageListenerContainer {
        return SqsMessageListenerContainer(
            endpoint = SqsListenerEndpoint(
                id = "listener",
                queue = queue,
                maxMessages = maxMessages,
                waitTimeSeconds = 0,
                visibilityTimeoutSeconds = null,
                errorVisibilityTimeoutSeconds = null,
                autoStartup = true,
                phase = 0,
                concurrency = 1,
                stopTimeoutMillis = stopTimeoutMillis,
                retry = retry,
                batch = batch,
                acknowledgementMode = acknowledgementMode,
            ),
            operations = operations,
            invoker = invoker,
            interceptors = emptyList(),
            dispatcher = dispatcher,
        )
    }

    private fun message(messageId: String = "message-1"): SqsReceivedMessage =
        SqsReceivedMessage(
            queueUrl = QUEUE_URL,
            message = Message.builder()
                .messageId(messageId)
                .receiptHandle("receipt-$messageId")
                .body("payload")
                .build(),
        )

    companion object {
        private const val QUEUE_URL = "https://sqs.local/orders"
    }
}
