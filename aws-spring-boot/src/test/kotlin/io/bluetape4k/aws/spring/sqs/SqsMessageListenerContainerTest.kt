package io.bluetape4k.aws.spring.sqs

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeInstanceOf
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.RepeatedTest
import org.junit.jupiter.api.Test
import software.amazon.awssdk.services.sqs.model.DeleteMessageResponse
import software.amazon.awssdk.services.sqs.model.Message
import software.amazon.awssdk.services.sqs.model.MessageSystemAttributeName
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.CopyOnWriteArrayList

@Suppress("LargeClass")
class SqsMessageListenerContainerTest {

    @Test
    fun `single message handlers run concurrently up to the in flight limit`() = runSuspendIO {
        val operations = mockk<SqsOperations>()
        val invoker = mockk<SqsListenerMethodInvoker>()
        val messages = listOf(message(), message("message-2"))
        val receiveCalls = AtomicInteger()
        val startedIds = CopyOnWriteArrayList<String>()
        val bothStarted = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        coEvery { operations.receive(QUEUE_URL, 2, 0, null) } coAnswers {
            if (receiveCalls.incrementAndGet() == 1) messages else awaitCancellation()
        }
        coEvery { operations.delete(QUEUE_URL, any()) } returns DeleteMessageResponse.builder().build()
        every { invoker.manualAcknowledgement } returns false
        coEvery { invoker.invoke(any(), any()) } coAnswers {
            startedIds += firstArg<SqsReceivedMessage>().messageId
            if (startedIds.size == 2) {
                bothStarted.complete(Unit)
            }
            release.await()
        }
        val container = container(
            operations,
            invoker,
            maxMessages = 2,
            maxInFlight = 2,
        )

        container.start()
        try {
            withTimeout(2_000) { bothStarted.await() }
            startedIds.toSet() shouldBeEqualTo setOf("message-1", "message-2")
        } finally {
            release.complete(Unit)
            val stopped = CompletableDeferred<Unit>()
            container.stop { stopped.complete(Unit) }
            withTimeout(2_000) { stopped.await() }
        }
    }

    @RepeatedTest(100)
    fun `fifo message group is serialized while another group runs in parallel`() = runSuspendIO {
        val operations = mockk<SqsOperations>()
        val invoker = mockk<SqsListenerMethodInvoker>()
        val messages = listOf(message("a-1", "group-a"), message("a-2", "group-a"), message("b-1", "group-b"))
        val receiveCalls = AtomicInteger()
        val firstGroupStarted = CompletableDeferred<Unit>()
        val otherGroupStarted = CompletableDeferred<Unit>()
        val secondGroupMessageStarted = CompletableDeferred<Unit>()
        val releaseFirstGroup = CompletableDeferred<Unit>()
        coEvery { operations.receive(QUEUE_URL, 3, 0, null) } coAnswers {
            if (receiveCalls.incrementAndGet() == 1) messages else awaitCancellation()
        }
        coEvery { operations.delete(QUEUE_URL, any()) } returns DeleteMessageResponse.builder().build()
        every { invoker.manualAcknowledgement } returns false
        coEvery { invoker.invoke(any(), any()) } coAnswers {
            when (val id = firstArg<SqsReceivedMessage>().messageId) {
                "a-1" -> {
                    firstGroupStarted.complete(Unit)
                    releaseFirstGroup.await()
                }
                "a-2" -> secondGroupMessageStarted.complete(Unit)
                "b-1" -> otherGroupStarted.complete(Unit)
            }
        }
        val container = container(
            operations,
            invoker,
            maxMessages = 3,
            maxInFlight = 3,
        )

        container.start()
        try {
            withTimeout(2_000) { firstGroupStarted.await() }
            withTimeout(2_000) { otherGroupStarted.await() }
            secondGroupMessageStarted.isCompleted shouldBeEqualTo false
            releaseFirstGroup.complete(Unit)
            withTimeout(2_000) { secondGroupMessageStarted.await() }
        } finally {
            releaseFirstGroup.complete(Unit)
            val stopped = CompletableDeferred<Unit>()
            container.stop { stopped.complete(Unit) }
            withTimeout(2_000) { stopped.await() }
        }
    }

    @Test
    fun `batch receive invokes handler once with all messages`() = runSuspendIO {
        val operations = mockk<SqsOperations>()
        val invoker = mockk<SqsListenerMethodInvoker>()
        val invocation = CompletableDeferred<List<SqsReceivedMessage>>()
        val deleteBatchCalled = CompletableDeferred<Unit>()
        val receiveCalls = AtomicInteger(0)
        val messages = listOf(message(), message("message-2"))
        coEvery { operations.receive(QUEUE_URL, 2, 0, null) } coAnswers {
            if (receiveCalls.incrementAndGet() == 1) messages else awaitCancellation()
        }
        coEvery { operations.deleteBatch(QUEUE_URL, any()) } coAnswers {
            deleteBatchCalled.complete(Unit)
            SqsBatchDeleteResult(listOf("entry-0", "entry-1"), emptyList())
        }
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
            withTimeout(2_000) { deleteBatchCalled.await() }
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
    fun `explicit start starts listener when automatic startup is disabled`() = runSuspendIO {
        val operations = mockk<SqsOperations>()
        val invoker = mockk<SqsListenerMethodInvoker>()
        val pollerStarted = CompletableDeferred<Unit>()
        coEvery { operations.receive(QUEUE_URL, 1, 0, null) } coAnswers {
            pollerStarted.complete(Unit)
            awaitCancellation()
        }
        every { invoker.manualAcknowledgement } returns false
        val container = container(operations, invoker, autoStartup = false)

        try {
            container.start()
            withTimeout(2_000) { pollerStarted.await() }
            container.isRunning.shouldBeTrue()
        } finally {
            val stopped = CompletableDeferred<Unit>()
            container.stop { stopped.complete(Unit) }
            withTimeout(2_000) { stopped.await() }
        }
    }

    @Test
    fun `receive cancellation invokes terminal interceptor hook`() = runSuspendIO {
        val operations = mockk<SqsOperations>()
        val invoker = mockk<SqsListenerMethodInvoker>()
        val interceptor = mockk<SqsListenerInterceptor>(relaxed = true)
        val receiveStarted = CompletableDeferred<Unit>()
        val cancellation = slot<Throwable>()
        coEvery { operations.receive(QUEUE_URL, 1, 0, null) } coAnswers {
            receiveStarted.complete(Unit)
            awaitCancellation()
        }
        every { invoker.manualAcknowledgement } returns false
        val container = container(operations, invoker, interceptors = listOf(interceptor))

        try {
            container.start()
            withTimeout(2_000) { receiveStarted.await() }
        } finally {
            val stopped = CompletableDeferred<Unit>()
            container.stop { stopped.complete(Unit) }
            withTimeout(2_000) { stopped.await() }
        }

        coVerify(exactly = 1) {
            interceptor.afterReceive(
                "listener",
                QUEUE_URL,
                emptyList(),
                capture(cancellation),
                any(),
            )
        }
        cancellation.captured.shouldBeInstanceOf(CancellationException::class)
    }

    @Test
    fun `handler cancellation invokes terminal interceptor hook`() = runSuspendIO {
        val operations = mockk<SqsOperations>()
        val invoker = mockk<SqsListenerMethodInvoker>()
        val interceptor = mockk<SqsListenerInterceptor>(relaxed = true)
        val handlerStarted = CompletableDeferred<Unit>()
        val cancellation = slot<Throwable>()
        coEvery { operations.receive(QUEUE_URL, 1, 0, null) } returns listOf(message())
        every { invoker.manualAcknowledgement } returns false
        coEvery { invoker.invoke(any(), any()) } coAnswers {
            handlerStarted.complete(Unit)
            awaitCancellation()
        }
        val container = container(
            operations,
            invoker,
            stopTimeoutMillis = 50,
            interceptors = listOf(interceptor),
        )

        container.start()
        withTimeout(2_000) { handlerStarted.await() }
        val stopped = CompletableDeferred<Unit>()
        container.stop { stopped.complete(Unit) }
        withTimeout(2_000) { stopped.await() }

        coVerify(exactly = 1) { interceptor.afterHandle(any(), capture(cancellation)) }
        cancellation.captured.shouldBeInstanceOf(CancellationException::class)
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
        assertFailsWith<IllegalStateException> { container.start() }
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

    @Test
    fun `single message heartbeat repeats while handler is active and stops after success`() = runTest {
        val operations = mockk<SqsOperations>()
        val invoker = mockk<SqsListenerMethodInvoker>()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val handlerStarted = CompletableDeferred<Unit>()
        val handlerRelease = CompletableDeferred<Unit>()
        val secondHeartbeat = CompletableDeferred<Unit>()
        val heartbeatCalls = AtomicInteger()
        val receiveCalls = AtomicInteger()
        coEvery { operations.receive(QUEUE_URL, 1, 0, null) } coAnswers {
            if (receiveCalls.incrementAndGet() == 1) listOf(message()) else awaitCancellation()
        }
        coEvery { operations.changeVisibility(QUEUE_URL, "receipt-message-1", 30) } coAnswers {
            if (heartbeatCalls.incrementAndGet() >= 2) {
                secondHeartbeat.complete(Unit)
            }
            software.amazon.awssdk.services.sqs.model.ChangeMessageVisibilityResponse.builder().build()
        }
        coEvery { operations.delete(QUEUE_URL, "receipt-message-1") } returns
            DeleteMessageResponse.builder().build()
        every { invoker.manualAcknowledgement } returns false
        coEvery { invoker.invoke(any(), any()) } coAnswers {
            handlerStarted.complete(Unit)
            handlerRelease.await()
        }
        val container = container(
            operations = operations,
            invoker = invoker,
            dispatcher = dispatcher,
            messageVisibilityHeartbeatIntervalSeconds = 1,
            messageVisibilityHeartbeatSeconds = 30,
        )

        container.start()
        runCurrent()
        handlerStarted.await()
        advanceTimeBy(2_000)
        runCurrent()
        withContext(Dispatchers.Default.limitedParallelism(1)) {
            withTimeout(2_000) { secondHeartbeat.await() }
        }
        (heartbeatCalls.get() >= 2).shouldBeTrue()

        handlerRelease.complete(Unit)
        runCurrent()
        coVerify(exactly = 1) { operations.delete(QUEUE_URL, "receipt-message-1") }
        val callsAfterSuccess = heartbeatCalls.get()
        val stopped = CompletableDeferred<Unit>()
        container.stop { stopped.complete(Unit) }
        runCurrent()
        stopped.await()
        advanceTimeBy(2_000)
        runCurrent()
        heartbeatCalls.get() shouldBeEqualTo callsAfterSuccess
    }

    @Test
    fun `single message heartbeat stops after manual acknowledgement`() = runTest {
        val operations = mockk<SqsOperations>()
        val invoker = mockk<SqsListenerMethodInvoker>()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val handlerStarted = CompletableDeferred<Unit>()
        val acknowledgementCompleted = CompletableDeferred<Unit>()
        val handlerRelease = CompletableDeferred<Unit>()
        val receiveCalls = AtomicInteger()
        coEvery { operations.receive(QUEUE_URL, 1, 0, null) } coAnswers {
            if (receiveCalls.incrementAndGet() == 1) listOf(message()) else awaitCancellation()
        }
        coEvery { operations.changeVisibility(QUEUE_URL, "receipt-message-1", 30) } returns
            software.amazon.awssdk.services.sqs.model.ChangeMessageVisibilityResponse.builder().build()
        coEvery { operations.delete(QUEUE_URL, "receipt-message-1") } returns
            DeleteMessageResponse.builder().build()
        every { invoker.manualAcknowledgement } returns true
        coEvery { invoker.invoke(any(), any()) } coAnswers {
            handlerStarted.complete(Unit)
            secondArg<SqsAcknowledgement>().acknowledge()
            acknowledgementCompleted.complete(Unit)
            handlerRelease.await()
        }
        val container = container(
            operations = operations,
            invoker = invoker,
            dispatcher = dispatcher,
            messageVisibilityHeartbeatIntervalSeconds = 1,
            messageVisibilityHeartbeatSeconds = 30,
        )

        container.start()
        runCurrent()
        handlerStarted.await()
        acknowledgementCompleted.await()
        advanceTimeBy(1_000)
        runCurrent()
        coVerify(exactly = 1) { operations.delete(QUEUE_URL, "receipt-message-1") }
        coVerify(exactly = 0) { operations.changeVisibility(QUEUE_URL, "receipt-message-1", 30) }

        handlerRelease.complete(Unit)
        runCurrent()
        val stopped = CompletableDeferred<Unit>()
        container.stop { stopped.complete(Unit) }
        runCurrent()
        stopped.await()
    }

    @Test
    fun `heartbeat failure does not change successful handler outcome`() = runTest {
        val operations = mockk<SqsOperations>()
        val invoker = mockk<SqsListenerMethodInvoker>()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val handlerStarted = CompletableDeferred<Unit>()
        val handlerRelease = CompletableDeferred<Unit>()
        val heartbeatFailed = CompletableDeferred<Unit>()
        val deleteCalled = CompletableDeferred<Unit>()
        val receiveCalls = AtomicInteger()
        coEvery { operations.receive(QUEUE_URL, 1, 0, null) } coAnswers {
            if (receiveCalls.incrementAndGet() == 1) listOf(message()) else awaitCancellation()
        }
        coEvery { operations.changeVisibility(QUEUE_URL, "receipt-message-1", 30) } coAnswers {
            heartbeatFailed.complete(Unit)
            throw IllegalStateException("heartbeat unavailable")
        }
        coEvery { operations.delete(QUEUE_URL, "receipt-message-1") } coAnswers {
            deleteCalled.complete(Unit)
            DeleteMessageResponse.builder().build()
        }
        every { invoker.manualAcknowledgement } returns false
        coEvery { invoker.invoke(any(), any()) } coAnswers {
            handlerStarted.complete(Unit)
            heartbeatFailed.await()
            handlerRelease.await()
        }
        val container = container(
            operations = operations,
            invoker = invoker,
            dispatcher = dispatcher,
            messageVisibilityHeartbeatIntervalSeconds = 1,
            messageVisibilityHeartbeatSeconds = 30,
        )

        container.start()
        runCurrent()
        handlerStarted.await()
        advanceTimeBy(1_000)
        runCurrent()
        withContext(Dispatchers.Default.limitedParallelism(1)) {
            withTimeout(2_000) { heartbeatFailed.await() }
        }
        handlerRelease.complete(Unit)
        runCurrent()
        withContext(Dispatchers.Default.limitedParallelism(1)) {
            withTimeout(2_000) { deleteCalled.await() }
        }
        coVerify(exactly = 1) { operations.delete(QUEUE_URL, "receipt-message-1") }

        val stopped = CompletableDeferred<Unit>()
        container.stop { stopped.complete(Unit) }
        runCurrent()
        stopped.await()
    }

    @Test
    fun `batch heartbeat protects pending messages and excludes manually acknowledged items`() = runTest {
        val operations = mockk<SqsOperations>()
        val invoker = mockk<SqsListenerMethodInvoker>()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val handlerStarted = CompletableDeferred<Unit>()
        val handlerRelease = CompletableDeferred<Unit>()
        val heartbeatObserved = CompletableDeferred<List<SqsChangeVisibilityRequest>>()
        val receiveCalls = AtomicInteger()
        val messages = listOf(message(), message("message-2"))
        coEvery { operations.receive(QUEUE_URL, 2, 0, null) } coAnswers {
            if (receiveCalls.incrementAndGet() == 1) messages else awaitCancellation()
        }
        coEvery { operations.changeVisibilityBatch(QUEUE_URL, any()) } coAnswers {
            val requests = secondArg<Collection<SqsChangeVisibilityRequest>>().toList()
            heartbeatObserved.complete(requests)
            SqsBatchVisibilityResult(
                successfulMessageIds = requests.map { it.messageId },
                failed = emptyList(),
            )
        }
        coEvery { operations.deleteBatch(QUEUE_URL, any()) } returns
            SqsBatchDeleteResult(listOf("entry-0"), emptyList())
        coEvery { invoker.invokeBatch(any(), anyNullable<SqsBatchAcknowledgement>()) } coAnswers {
            secondArg<SqsBatchAcknowledgement>().acknowledge(listOf(firstArg<List<SqsReceivedMessage>>().first()))
            handlerStarted.complete(Unit)
            handlerRelease.await()
        }
        every { invoker.manualAcknowledgement } returns true
        val container = container(
            operations = operations,
            invoker = invoker,
            dispatcher = dispatcher,
            batch = true,
            maxMessages = 2,
            acknowledgementMode = SqsAcknowledgementMode.MANUAL,
            messageVisibilityHeartbeatIntervalSeconds = 1,
            messageVisibilityHeartbeatSeconds = 30,
        )

        container.start()
        runCurrent()
        handlerStarted.await()
        advanceTimeBy(1_000)
        runCurrent()
        val requests = withContext(Dispatchers.Default.limitedParallelism(1)) {
            withTimeout(2_000) { heartbeatObserved.await() }
        }
        requests.map { it.messageId } shouldBeEqualTo listOf("message-2")
        requests.single().timeoutSeconds shouldBeEqualTo 30

        handlerRelease.complete(Unit)
        runCurrent()
        val stopped = CompletableDeferred<Unit>()
        container.stop { stopped.complete(Unit) }
        runCurrent()
        stopped.await()
        coVerify(atLeast = 1) { operations.changeVisibilityBatch(QUEUE_URL, any()) }
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
        autoStartup: Boolean = true,
        interceptors: List<SqsListenerInterceptor> = emptyList(),
        messageVisibilityHeartbeatIntervalSeconds: Int? = null,
        messageVisibilityHeartbeatSeconds: Int? = null,
        concurrency: Int = 1,
        maxInFlight: Int = maxMessages * concurrency,
        fifoBatchGroupingStrategy: SqsFifoBatchGroupingStrategy =
            SqsFifoBatchGroupingStrategy.GROUP_BY_MESSAGE_GROUP_ID,
    ): SqsMessageListenerContainer {
        return SqsMessageListenerContainer(
            endpoint = SqsListenerEndpoint(
                id = "listener",
                queue = queue,
                maxMessages = maxMessages,
                waitTimeSeconds = 0,
                visibilityTimeoutSeconds = null,
                errorVisibilityTimeoutSeconds = null,
                messageVisibilityHeartbeatIntervalSeconds = messageVisibilityHeartbeatIntervalSeconds,
                messageVisibilityHeartbeatSeconds = messageVisibilityHeartbeatSeconds,
                autoStartup = autoStartup,
                phase = 0,
                concurrency = concurrency,
                stopTimeoutMillis = stopTimeoutMillis,
                retry = retry,
                batch = batch,
                acknowledgementMode = acknowledgementMode,
                maxInFlight = maxInFlight,
                fifoBatchGroupingStrategy = fifoBatchGroupingStrategy,
            ),
            operations = operations,
            invoker = invoker,
            interceptors = interceptors,
            dispatcher = dispatcher,
        )
    }

    private fun message(messageId: String = "message-1", messageGroupId: String? = null): SqsReceivedMessage =
        SqsReceivedMessage(
            queueUrl = QUEUE_URL,
            message = Message.builder()
                .messageId(messageId)
                .receiptHandle("receipt-$messageId")
                .body("payload")
                .apply {
                    messageGroupId?.let {
                        attributes(mapOf(MessageSystemAttributeName.MESSAGE_GROUP_ID to it))
                    }
                }
                .build(),
        )

    companion object {
        private const val QUEUE_URL = "https://sqs.local/orders"
    }
}
