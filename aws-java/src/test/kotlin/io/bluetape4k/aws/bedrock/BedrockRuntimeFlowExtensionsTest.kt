package io.bluetape4k.aws.bedrock

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeSameInstanceAs
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.aws.bedrock.model.userMessageOf
import io.bluetape4k.coroutines.flow.extensions.takeUntil
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeAsyncClient
import software.amazon.awssdk.services.bedrockruntime.model.ContentBlockDelta
import software.amazon.awssdk.services.bedrockruntime.model.ContentBlockDeltaEvent
import software.amazon.awssdk.services.bedrockruntime.model.ConverseStreamOutput
import software.amazon.awssdk.services.bedrockruntime.model.ConverseStreamRequest
import software.amazon.awssdk.services.bedrockruntime.model.ConverseStreamResponseHandler
import software.amazon.awssdk.services.bedrockruntime.model.MessageStopEvent
import software.amazon.awssdk.services.bedrockruntime.model.ValidationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@Suppress("LargeClass")
class BedrockRuntimeFlowExtensionsTest {

    private val client = mockk<BedrockRuntimeAsyncClient>()
    private val request = ConverseStreamRequest.builder()
        .modelId("model-id")
        .messages(userMessageOf("hello"))
        .build()

    @BeforeEach
    fun setup() {
        clearMocks(client)
    }

    @Test
    fun `collection is cold and each collector invokes SDK once`() = runTest {
        val handlers = mutableListOf<ConverseStreamResponseHandler>()
        every { client.converseStream(request, capture(handlers)) } answers {
            CompletableFuture.completedFuture(null)
        }
        val flow = client.converseStreamFlow(request)

        verify(exactly = 0) { client.converseStream(any<ConverseStreamRequest>(), any()) }
        flow.toList()
        flow.toList()

        handlers.size shouldBeEqualTo 2
        verify(exactly = 2) { client.converseStream(any<ConverseStreamRequest>(), any()) }
    }

    @Test
    fun `first event arrives before operation future completes`() = runTest {
        val handler = slot<ConverseStreamResponseHandler>()
        val future = CompletableFuture<Void>()
        every { client.converseStream(request, capture(handler)) } returns future
        val first = contentDelta("a")
        val seen = CompletableDeferred<ConverseStreamOutput>()
        val collector = launch {
            client.converseStreamFlow(request).collect { seen.complete(it) }
        }
        runCurrent()

        val publisher = RecordingSdkPublisher<ConverseStreamOutput>()
        handler.captured.onEventStream(publisher)
        runCurrent()
        publisher.emitOne(first).shouldBeTrue()
        runCurrent()

        seen.await() shouldBeSameInstanceAs first
        future.isDone.shouldBeFalse()
        publisher.complete()
        handler.captured.complete()
        future.complete(null)
        collector.join()
    }

    @Test
    fun `slow collector preserves order with one outstanding request`() = runTest {
        val handler = slot<ConverseStreamResponseHandler>()
        val future = CompletableFuture<Void>()
        every { client.converseStream(request, capture(handler)) } returns future
        val release = Channel<Unit>(Channel.RENDEZVOUS)
        val seen = mutableListOf<ConverseStreamOutput>()
        val collector = launch {
            client.converseStreamFlow(request).collect {
                seen += it
                release.receive()
            }
        }
        runCurrent()
        val publisher = RecordingSdkPublisher<ConverseStreamOutput>()
        handler.captured.onEventStream(publisher)
        runCurrent()
        val first = contentDelta("a")
        val second = contentDelta("b")

        publisher.emitOne(first).shouldBeTrue()
        publisher.emitOne(second).shouldBeFalse()
        publisher.maxOutstanding shouldBeEqualTo 1L
        runCurrent()
        publisher.emitOne(second).shouldBeTrue()
        release.send(Unit)
        runCurrent()
        release.send(Unit)
        publisher.complete()
        future.complete(null)
        collector.join()

        publisher.requests.all { it == 1L }.shouldBeTrue()
        seen shouldBeEqualTo listOf(first, second)
    }

    @Test
    fun `first cancels subscription and operation future once`() = runTest {
        val handler = slot<ConverseStreamResponseHandler>()
        val future = CancelCountingFuture()
        every { client.converseStream(request, capture(handler)) } returns future
        val result = CompletableDeferred<ConverseStreamOutput>()
        val collector = launch { result.complete(client.converseStreamFlow(request).first()) }
        runCurrent()
        val publisher = RecordingSdkPublisher<ConverseStreamOutput>()
        handler.captured.onEventStream(publisher)
        runCurrent()
        val event = contentDelta("first")
        publisher.emitOne(event)
        runCurrent()

        result.await() shouldBeSameInstanceAs event
        collector.join()
        future.isCancelled.shouldBeTrue()
        future.cancelCount shouldBeEqualTo 1
        publisher.cancelCount shouldBeEqualTo 1
    }

    @Test
    fun `future success waits for latest publisher terminal and preserves publisher failure`() = runTest {
        val handler = slot<ConverseStreamResponseHandler>()
        val future = CompletableFuture<Void>()
        every { client.converseStream(request, capture(handler)) } returns future
        val error = ValidationException.builder().message("stream failed").build()
        val terminal = CompletableDeferred<Throwable>()
        val collector = launch {
            try {
                client.converseStreamFlow(request).toList()
            } catch (cause: Throwable) {
                terminal.complete(cause)
            }
        }
        runCurrent()
        val publisher = RecordingSdkPublisher<ConverseStreamOutput>()
        handler.captured.onEventStream(publisher)
        runCurrent()

        future.complete(null)
        runCurrent()
        terminal.isCompleted.shouldBeFalse()
        publisher.fail(error)
        runCurrent()

        terminal.await() shouldBeSameInstanceAs error
        collector.join()
    }

    @Test
    fun `publisher completion waits for operation future success`() = runTest {
        val handler = slot<ConverseStreamResponseHandler>()
        val future = CompletableFuture<Void>()
        every { client.converseStream(request, capture(handler)) } returns future
        val completed = CompletableDeferred<Unit>()
        val collector = launch {
            client.converseStreamFlow(request).toList()
            completed.complete(Unit)
        }
        runCurrent()
        val publisher = RecordingSdkPublisher<ConverseStreamOutput>()
        handler.captured.onEventStream(publisher)
        runCurrent()

        publisher.complete()
        runCurrent()
        completed.isCompleted.shouldBeFalse()
        publisher.terminalCount shouldBeEqualTo 1
        future.complete(null)
        collector.join()

        completed.isCompleted.shouldBeTrue()
    }

    @Test
    fun `operation future failure before publisher preserves error`() = runTest {
        val handler = slot<ConverseStreamResponseHandler>()
        val future = CompletableFuture<Void>()
        every { client.converseStream(request, capture(handler)) } returns future
        val expected = ValidationException.builder().message("operation failed").build()
        val terminal = CompletableDeferred<Throwable>()
        val collector = launch {
            try {
                client.converseStreamFlow(request).toList()
            } catch (cause: Throwable) {
                terminal.complete(cause)
            }
        }
        runCurrent()

        future.completeExceptionally(expected)
        runCurrent()

        terminal.await() shouldBeSameInstanceAs expected
        collector.join()
    }

    @Test
    fun `publisher error can be replaced by a successful generation`() = runTest {
        val handler = slot<ConverseStreamResponseHandler>()
        val future = CompletableFuture<Void>()
        every { client.converseStream(request, capture(handler)) } returns future
        val seen = mutableListOf<ConverseStreamOutput>()
        val collector = launch { client.converseStreamFlow(request).toList(seen) }
        runCurrent()
        val failed = RecordingSdkPublisher<ConverseStreamOutput>()
        val replacement = RecordingSdkPublisher<ConverseStreamOutput>()
        handler.captured.onEventStream(failed)
        runCurrent()
        failed.fail(ValidationException.builder().message("retryable").build())
        runCurrent()

        handler.captured.onEventStream(replacement)
        runCurrent()
        val event = contentDelta("replacement")
        replacement.emitOne(event)
        runCurrent()
        replacement.complete()
        future.complete(null)
        collector.join()

        seen shouldBeEqualTo listOf(event)
    }

    @Test
    fun `handler failure from old generation does not beat replacement`() = runTest {
        val handler = slot<ConverseStreamResponseHandler>()
        val future = CompletableFuture<Void>()
        every { client.converseStream(request, capture(handler)) } returns future
        val seen = mutableListOf<ConverseStreamOutput>()
        val collector = launch { client.converseStreamFlow(request).toList(seen) }
        runCurrent()
        val failed = RecordingSdkPublisher<ConverseStreamOutput>()
        val replacement = RecordingSdkPublisher<ConverseStreamOutput>()
        handler.captured.onEventStream(failed)
        runCurrent()

        handler.captured.exceptionOccurred(ValidationException.builder().message("retry").build())
        handler.captured.onEventStream(replacement)
        runCurrent()
        val event = contentDelta("replacement")
        replacement.emitOne(event)
        runCurrent()
        replacement.complete()
        future.complete(null)
        collector.join()

        failed.cancelCount shouldBeEqualTo 1
        seen shouldBeEqualTo listOf(event)
    }

    @Test
    fun `replacement activates while previous event collector is suspended`() = runTest {
        val handler = slot<ConverseStreamResponseHandler>()
        val future = CompletableFuture<Void>()
        every { client.converseStream(request, capture(handler)) } returns future
        val release = Channel<Unit>(Channel.RENDEZVOUS)
        val seen = mutableListOf<ConverseStreamOutput>()
        val collector = launch {
            client.converseStreamFlow(request).collect {
                seen += it
                release.receive()
            }
        }
        runCurrent()
        val first = RecordingSdkPublisher<ConverseStreamOutput>()
        val latest = RecordingSdkPublisher<ConverseStreamOutput>()
        handler.captured.onEventStream(first)
        runCurrent()
        val firstEvent = contentDelta("first")
        first.emitOne(firstEvent)
        runCurrent()

        handler.captured.onEventStream(latest)
        runCurrent()
        first.cancelCount shouldBeEqualTo 1
        latest.requests shouldBeEqualTo listOf(1L)
        val latestEvent = contentDelta("latest")
        latest.emitOne(latestEvent)
        first.adversarialNext(contentDelta("late"))
        runCurrent()

        release.send(Unit)
        runCurrent()
        release.send(Unit)
        latest.complete()
        future.complete(null)
        collector.join()

        seen shouldBeEqualTo listOf(firstEvent, latestEvent)
    }

    @Test
    fun `callbacks received before scheduler advancement activate newest generation`() = runTest {
        val handler = slot<ConverseStreamResponseHandler>()
        val future = CompletableFuture<Void>()
        every { client.converseStream(request, capture(handler)) } returns future
        val seen = mutableListOf<ConverseStreamOutput>()
        val collector = launch { client.converseStreamFlow(request).toList(seen) }
        runCurrent()
        val first = RecordingSdkPublisher<ConverseStreamOutput>()
        val latest = RecordingSdkPublisher<ConverseStreamOutput>()

        handler.captured.onEventStream(first)
        handler.captured.onEventStream(latest)
        runCurrent()

        first.cancelCount shouldBeEqualTo 1
        val event = contentDelta("latest")
        latest.emitOne(event)
        runCurrent()
        latest.complete()
        future.complete(null)
        collector.join()

        seen shouldBeEqualTo listOf(event)
    }

    @Test
    fun `callbacks received before immediate future success still honor newest generation`() = runTest {
        val handler = slot<ConverseStreamResponseHandler>()
        val future = CompletableFuture<Void>()
        every { client.converseStream(request, capture(handler)) } returns future
        val seen = mutableListOf<ConverseStreamOutput>()
        val collector = launch { client.converseStreamFlow(request).toList(seen) }
        runCurrent()
        val first = RecordingSdkPublisher<ConverseStreamOutput>()
        val latest = RecordingSdkPublisher<ConverseStreamOutput>()

        handler.captured.onEventStream(first)
        handler.captured.onEventStream(latest)
        future.complete(null)
        runCurrent()

        first.cancelCount shouldBeEqualTo 1
        val event = contentDelta("latest")
        latest.emitOne(event)
        runCurrent()
        latest.complete()
        collector.join()

        seen shouldBeEqualTo listOf(event)
    }

    @Test
    fun `replacement cancels old generation and ignores late signals`() = runTest {
        val handler = slot<ConverseStreamResponseHandler>()
        val future = CompletableFuture<Void>()
        every { client.converseStream(request, capture(handler)) } returns future
        val seen = mutableListOf<ConverseStreamOutput>()
        val collector = launch { client.converseStreamFlow(request).toList(seen) }
        runCurrent()
        val old = RecordingSdkPublisher<ConverseStreamOutput>()
        val latest = RecordingSdkPublisher<ConverseStreamOutput>()
        handler.captured.onEventStream(old)
        runCurrent()
        val partial = contentDelta("partial")
        old.emitOne(partial)
        runCurrent()
        handler.captured.onEventStream(latest)
        runCurrent()

        old.cancelCount shouldBeEqualTo 1
        old.adversarialNext(contentDelta("late"))
        old.adversarialError(IllegalStateException("late"))
        old.adversarialComplete()
        val duplicate = contentDelta("partial")
        latest.emitOne(duplicate)
        runCurrent()
        latest.complete()
        future.complete(null)
        collector.join()

        seen shouldBeEqualTo listOf(partial, duplicate)
    }

    @Test
    fun `cancellation before publisher callback cancels future and late publisher`() = runTest {
        val handler = slot<ConverseStreamResponseHandler>()
        val future = CancelCountingFuture()
        every { client.converseStream(request, capture(handler)) } returns future
        val collector = launch { client.converseStreamFlow(request).toList() }
        runCurrent()

        collector.cancelAndJoin()
        val late = RecordingSdkPublisher<ConverseStreamOutput>()
        handler.captured.onEventStream(late)
        runCurrent()

        future.isCancelled.shouldBeTrue()
        future.cancelCount shouldBeEqualTo 1
        late.cancelCount shouldBeEqualTo 1
        verify(exactly = 1) { client.converseStream(any<ConverseStreamRequest>(), any()) }
    }

    @Test
    fun `publisher callback racing collector cancellation is cancelled once`() = runTest {
        val handler = slot<ConverseStreamResponseHandler>()
        val future = CancelCountingFuture()
        every { client.converseStream(request, capture(handler)) } returns future
        val collector = launch { client.converseStreamFlow(request).toList() }
        runCurrent()
        val publisher = RecordingSdkPublisher<ConverseStreamOutput>()

        handler.captured.onEventStream(publisher)
        collector.cancel()
        runCurrent()
        collector.join()

        future.isCancelled.shouldBeTrue()
        future.cancelCount shouldBeEqualTo 1
        publisher.cancelCount shouldBeEqualTo 1
        verify(exactly = 1) { client.converseStream(any<ConverseStreamRequest>(), any()) }
    }

    @Test
    fun `cancellation before retry publisher handoff cancels publisher once`() = runTest {
        val handlerReady = CompletableDeferred<ConverseStreamResponseHandler>()
        val future = CancelCountingFuture()
        every { client.converseStream(request, any<ConverseStreamResponseHandler>()) } answers {
            handlerReady.complete(secondArg())
            future
        }
        val collector = launch(Dispatchers.Default) {
            client.converseStreamFlow(request).toList()
        }
        val handler = handlerReady.await()
        val firstSubscribed = CountDownLatch(1)
        val firstCancelEntered = CountDownLatch(1)
        val firstCancelRelease = CountDownLatch(1)
        val first = RecordingSdkPublisher<ConverseStreamOutput>(
            onSubscribed = firstSubscribed::countDown,
            onCancelled = {
                firstCancelEntered.countDown()
                firstCancelRelease.await(5, TimeUnit.SECONDS)
            },
        )
        val replacement = RecordingSdkPublisher<ConverseStreamOutput>()
        handler.onEventStream(first)
        firstSubscribed.await(5, TimeUnit.SECONDS).shouldBeTrue()

        handler.onEventStream(replacement)
        firstCancelEntered.await(5, TimeUnit.SECONDS).shouldBeTrue()
        collector.cancel()
        firstCancelRelease.countDown()
        collector.join()

        future.isCancelled.shouldBeTrue()
        future.cancelCount shouldBeEqualTo 1
        first.cancelCount shouldBeEqualTo 1
        replacement.cancelCount shouldBeEqualTo 1
    }

    @Test
    fun `timeout cancels operation and subscription without another call`() = runTest {
        val handler = slot<ConverseStreamResponseHandler>()
        val future = CancelCountingFuture()
        every { client.converseStream(request, capture(handler)) } returns future
        val timed = async {
            assertFailsWith<TimeoutCancellationException> {
                withTimeout(1) {
                    client.converseStreamFlow(request).toList()
                }
            }
        }
        runCurrent()
        val publisher = RecordingSdkPublisher<ConverseStreamOutput>()
        handler.captured.onEventStream(publisher)
        runCurrent()

        timed.await()

        future.isCancelled.shouldBeTrue()
        future.cancelCount shouldBeEqualTo 1
        publisher.cancelCount shouldBeEqualTo 1
        verify(exactly = 1) { client.converseStream(any<ConverseStreamRequest>(), any()) }
    }

    @Test
    fun `synchronous SDK failure cancels callback publisher and preserves error`() = runTest {
        val handler = slot<ConverseStreamResponseHandler>()
        val publisher = RecordingSdkPublisher<ConverseStreamOutput>()
        val expected = ValidationException.builder().message("invalid").build()
        every { client.converseStream(request, capture(handler)) } answers {
            handler.captured.onEventStream(publisher)
            throw expected
        }

        val actual = assertFailsWith<ValidationException> {
            client.converseStreamFlow(request).toList()
        }

        actual shouldBeSameInstanceAs expected
        publisher.cancelCount shouldBeEqualTo 1
    }

    @Test
    fun operationFailureRemainsPrimaryWhenPostHandoffCancellationFails() = runTest {
        val handler = slot<ConverseStreamResponseHandler>()
        val future = CompletableFuture<Void>()
        every { client.converseStream(request, capture(handler)) } returns future
        val operationFailure = ValidationException.builder().message("operation").build()
        val cancellationFailure = IllegalStateException("cancel")
        val terminal = CompletableDeferred<Throwable>()
        val collector = launch {
            try {
                client.converseStreamFlow(request).toList()
            } catch (cause: Throwable) {
                terminal.complete(cause)
            }
        }
        runCurrent()
        val publisher = RecordingSdkPublisher<ConverseStreamOutput>(onCancelled = { throw cancellationFailure })
        handler.captured.onEventStream(publisher)
        runCurrent()

        future.completeExceptionally(operationFailure)

        val actual = withTimeout(1_000) { terminal.await() }
        actual shouldBeSameInstanceAs operationFailure
        actual.suppressed.toList() shouldBeEqualTo listOf(cancellationFailure)
        collector.join()
    }

    @Test
    fun collectorCancellationPreservesPrimaryWhenPostHandoffCancellationFails() = runTest {
        val handler = slot<ConverseStreamResponseHandler>()
        val future = CancelCountingFuture()
        every { client.converseStream(request, capture(handler)) } returns future
        val cancellationFailure = IllegalStateException("cancel")
        val publisher = RecordingSdkPublisher<ConverseStreamOutput>(onCancelled = { throw cancellationFailure })
        val collector = launch {
            client.converseStreamFlow(request).toList()
        }
        runCurrent()
        handler.captured.onEventStream(publisher)
        runCurrent()

        collector.cancel(CancellationException("collector"))
        collector.join()

        future.cancelCount shouldBeEqualTo 1
        publisher.cancelCount shouldBeEqualTo 1
    }

    @Test
    fun successfulCancellationHasNoSuppressedCleanupFailure() = runTest {
        val handler = slot<ConverseStreamResponseHandler>()
        val future = CancelCountingFuture()
        every { client.converseStream(request, capture(handler)) } returns future
        val publisher = RecordingSdkPublisher<ConverseStreamOutput>()
        val collector = launch {
            client.converseStreamFlow(request).toList()
        }
        runCurrent()
        handler.captured.onEventStream(publisher)
        runCurrent()

        collector.cancel(CancellationException("collector"))
        collector.join()

        future.cancelCount shouldBeEqualTo 1
        publisher.cancelCount shouldBeEqualTo 1
    }

    @Test
    fun cancelOnceNormalCancellationDoesNotSuppressDeferredCancellationException() = runTest {
        val handler = slot<ConverseStreamResponseHandler>()
        val future = CancelCountingFuture()
        every { client.converseStream(request, capture(handler)) } returns future
        val publisher = RecordingSdkPublisher<ConverseStreamOutput>()
        val collector = launch {
            client.converseStreamFlow(request).toList()
        }
        runCurrent()
        handler.captured.onEventStream(publisher)
        runCurrent()

        collector.cancel(CancellationException("collector"))
        collector.join()

        publisher.cancelCount shouldBeEqualTo 1
        future.cancelCount shouldBeEqualTo 1
    }

    @Test
    fun outerFinallyDoesNotDuplicateCancellationSuppression() = runTest {
        val handler = slot<ConverseStreamResponseHandler>()
        val future = CompletableFuture<Void>()
        every { client.converseStream(request, capture(handler)) } returns future
        val operationFailure = ValidationException.builder().message("operation").build()
        val cancellationFailure = IllegalStateException("cancel")
        val terminal = CompletableDeferred<Throwable>()
        val collector = launch {
            try {
                client.converseStreamFlow(request).toList()
            } catch (cause: Throwable) {
                terminal.complete(cause)
            }
        }
        runCurrent()
        val publisher = RecordingSdkPublisher<ConverseStreamOutput>(onCancelled = { throw cancellationFailure })
        handler.captured.onEventStream(publisher)
        runCurrent()
        future.completeExceptionally(operationFailure)

        val actual = withTimeout(1_000) { terminal.await() }
        actual shouldBeSameInstanceAs operationFailure
        actual.suppressed.count { it === cancellationFailure } shouldBeEqualTo 1
        collector.join()
    }

    @Test
    fun rejectedCallbackReportsPreHandoffCancellationFailure() = runTest {
        val handler = slot<ConverseStreamResponseHandler>()
        val future = CancelCountingFuture()
        every { client.converseStream(request, capture(handler)) } returns future
        val collector = launch { client.converseStreamFlow(request).toList() }
        runCurrent()
        collector.cancelAndJoin()

        val cancellationFailure = IllegalStateException("late-cancel")
        val late = RecordingSdkPublisher<ConverseStreamOutput>(onCancelled = { throw cancellationFailure })
        val thrown = assertFailsWith<IllegalStateException> {
            handler.captured.onEventStream(late)
        }
        thrown shouldBeSameInstanceAs cancellationFailure
        late.cancelCount shouldBeEqualTo 1
    }

    @Test
    fun replacementCancellationFailuresPreserveFirstFailure() = runTest {
        val handler = slot<ConverseStreamResponseHandler>()
        val future = CompletableFuture<Void>()
        every { client.converseStream(request, capture(handler)) } returns future
        val firstFailure = IllegalStateException("first")
        val secondFailure = IllegalStateException("second")
        val terminal = CompletableDeferred<Throwable>()
        val collector = launch {
            try {
                client.converseStreamFlow(request).toList()
            } catch (cause: Throwable) {
                terminal.complete(cause)
            }
        }
        runCurrent()
        val first = RecordingSdkPublisher<ConverseStreamOutput>(onCancelled = { throw firstFailure })
        val second = RecordingSdkPublisher<ConverseStreamOutput>(onCancelled = { throw secondFailure })
        handler.captured.onEventStream(first)
        runCurrent()
        handler.captured.onEventStream(second)
        runCurrent()
        future.completeExceptionally(ValidationException.builder().message("operation").build())

        val actual = withTimeout(1_000) { terminal.await() }
        actual.suppressed.any { it === firstFailure }.shouldBeTrue()
        actual.suppressed.any { it === secondFailure }.shouldBeTrue()
        collector.join()
    }

    @Test
    fun boundedCancellationFailuresRetainBoundedSamplesAndOverflowCount() = runTest {
        val handler = slot<ConverseStreamResponseHandler>()
        val future = CompletableFuture<Void>()
        every { client.converseStream(request, capture(handler)) } returns future
        val operationFailure = ValidationException.builder().message("operation").build()
        val failures = (0 until 20).map { index -> IllegalStateException("cancel-$index") }
        val terminal = CompletableDeferred<Throwable>()
        val collector = launch {
            try {
                client.converseStreamFlow(request).toList()
            } catch (cause: Throwable) {
                terminal.complete(cause)
            }
        }
        runCurrent()
        failures.forEach { failure ->
            handler.captured.onEventStream(
                RecordingSdkPublisher<ConverseStreamOutput>(onCancelled = { throw failure }),
            )
            runCurrent()
        }
        future.completeExceptionally(operationFailure)

        val actual = withTimeout(1_000) { terminal.await() }
        actual shouldBeSameInstanceAs operationFailure
        actual.suppressed.count {
            it is RuntimeException && it.message?.startsWith("suppressed failure count") == true
        } shouldBeEqualTo 1
        actual.suppressed.filterNot {
            it.message?.startsWith("suppressed failure count") == true
        }.size shouldBeEqualTo 16
        collector.join()
    }

    @Test
    fun repeatedCancellationFailureDoesNotDuplicateRetainedThrowable() = runTest {
        val handler = slot<ConverseStreamResponseHandler>()
        val future = CompletableFuture<Void>()
        every { client.converseStream(request, capture(handler)) } returns future
        val operationFailure = ValidationException.builder().message("operation").build()
        val cancellationFailure = IllegalStateException("same")
        val terminal = CompletableDeferred<Throwable>()
        val collector = launch {
            try {
                client.converseStreamFlow(request).toList()
            } catch (cause: Throwable) {
                terminal.complete(cause)
            }
        }
        runCurrent()
        repeat(4) {
            handler.captured.onEventStream(
                RecordingSdkPublisher<ConverseStreamOutput>(onCancelled = { throw cancellationFailure }),
            )
            runCurrent()
        }
        future.completeExceptionally(operationFailure)

        val actual = withTimeout(1_000) { terminal.await() }
        actual.suppressed.count { it === cancellationFailure } shouldBeEqualTo 1
        collector.join()
    }

    @Test
    fun boundedFailureAccumulatorMaterializesOverflowOnce() = runTest {
        val handler = slot<ConverseStreamResponseHandler>()
        val future = CompletableFuture<Void>()
        every { client.converseStream(request, capture(handler)) } returns future
        val operationFailure = ValidationException.builder().message("operation").build()
        val terminal = CompletableDeferred<Throwable>()
        val collector = launch {
            try {
                client.converseStreamFlow(request).toList()
            } catch (cause: Throwable) {
                terminal.complete(cause)
            }
        }
        runCurrent()
        repeat(20) { index ->
            handler.captured.onEventStream(
                RecordingSdkPublisher<ConverseStreamOutput>(
                    onCancelled = { throw IllegalStateException("cancel-$index") },
                ),
            )
            runCurrent()
        }
        future.completeExceptionally(operationFailure)

        val actual = withTimeout(1_000) { terminal.await() }
        actual.suppressed.count { it.message?.startsWith("suppressed failure count") == true } shouldBeEqualTo 1
        collector.join()
    }

    @Test
    fun completedCallbackFailureIsClearedAfterClose() = runTest {
        val handler = slot<ConverseStreamResponseHandler>()
        val future = CompletableFuture<Void>()
        every { client.converseStream(request, capture(handler)) } returns future
        val terminal = CompletableDeferred<Throwable>()
        val collector = launch {
            try {
                client.converseStreamFlow(request).toList()
            } catch (cause: Throwable) {
                terminal.complete(cause)
            }
        }
        runCurrent()
        val publisher = RecordingSdkPublisher<ConverseStreamOutput>(
            onCancelled = { throw IllegalStateException("callback") },
        )
        handler.captured.onEventStream(publisher)
        runCurrent()
        future.completeExceptionally(ValidationException.builder().message("operation").build())
        withTimeout(1_000) { terminal.await() }
        collector.join()

        val late = RecordingSdkPublisher<ConverseStreamOutput>()
        handler.captured.onEventStream(late)
        late.cancelCount shouldBeEqualTo 1
    }

    @Test
    fun currentHandlerFailureUsesOperationFutureCause() = runTest {
        val handler = slot<ConverseStreamResponseHandler>()
        val future = CompletableFuture<Void>()
        every { client.converseStream(request, capture(handler)) } returns future
        val expected = ValidationException.builder().message("handler").build()
        val terminal = CompletableDeferred<Throwable>()
        val collector = launch {
            try {
                client.converseStreamFlow(request).toList()
            } catch (cause: Throwable) {
                terminal.complete(cause)
            }
        }
        runCurrent()
        handler.captured.exceptionOccurred(expected)
        future.completeExceptionally(expected)

        val actual = withTimeout(1_000) { terminal.await() }
        actual shouldBeSameInstanceAs expected
        collector.join()
    }

    @Test
    fun lateOldGenerationHandlerFailureDoesNotContaminateReplacementSuccess() = runTest {
        val handler = slot<ConverseStreamResponseHandler>()
        val future = CompletableFuture<Void>()
        every { client.converseStream(request, capture(handler)) } returns future
        val seen = mutableListOf<ConverseStreamOutput>()
        val collector = launch { client.converseStreamFlow(request).toList(seen) }
        runCurrent()
        val old = RecordingSdkPublisher<ConverseStreamOutput>()
        val replacement = RecordingSdkPublisher<ConverseStreamOutput>()
        handler.captured.onEventStream(old)
        runCurrent()
        handler.captured.onEventStream(replacement)
        runCurrent()
        handler.captured.exceptionOccurred(IllegalStateException("late-old"))
        val event = contentDelta("replacement")
        replacement.emitOne(event)
        runCurrent()
        replacement.complete()
        future.complete(null)
        collector.join()

        seen shouldBeEqualTo listOf(event)
    }

    @Test
    fun acceptedCallbackIsDrainedWhenOperationFailsBeforeHandoff() = runTest {
        val handler = slot<ConverseStreamResponseHandler>()
        val future = CompletableFuture<Void>()
        every { client.converseStream(request, capture(handler)) } returns future
        val publisher = RecordingSdkPublisher<ConverseStreamOutput>()
        val terminal = CompletableDeferred<Throwable>()
        val collector = launch {
            try {
                client.converseStreamFlow(request).toList()
            } catch (cause: Throwable) {
                terminal.complete(cause)
            }
        }
        runCurrent()
        handler.captured.onEventStream(publisher)
        future.completeExceptionally(ValidationException.builder().message("operation").build())
        runCurrent()

        withTimeout(1_000) { terminal.await() }
        publisher.cancelCount shouldBeEqualTo 1
        collector.join()
    }

    @Test
    fun closeDrainsSuspendedCallbackCompletion() = runTest {
        val handler = slot<ConverseStreamResponseHandler>()
        val future = CompletableFuture<Void>()
        every { client.converseStream(request, capture(handler)) } returns future
        val release = CompletableDeferred<Unit>()
        val publisher = RecordingSdkPublisher<ConverseStreamOutput>()
        val collector = launch {
            client.converseStreamFlow(request).collect {
                release.await()
            }
        }
        runCurrent()
        handler.captured.onEventStream(publisher)
        runCurrent()
        publisher.emitOne(contentDelta("suspended"))
        runCurrent()
        future.complete(null)
        runCurrent()
        release.complete(Unit)
        publisher.complete()
        collector.join()
    }

    @Test
    fun lateCallbackIsRejectedAfterClose() = runTest {
        val handler = slot<ConverseStreamResponseHandler>()
        val future = CompletableFuture<Void>()
        every { client.converseStream(request, capture(handler)) } returns future
        val collector = launch { client.converseStreamFlow(request).toList() }
        runCurrent()
        future.complete(null)
        runCurrent()
        collector.join()
        val late = RecordingSdkPublisher<ConverseStreamOutput>()
        handler.captured.onEventStream(late)
        late.cancelCount shouldBeEqualTo 1
    }

    @Test
    fun highVolumeReplacementDrainsCompletedCallbacks() = runTest {
        val handler = slot<ConverseStreamResponseHandler>()
        val future = CompletableFuture<Void>()
        every { client.converseStream(request, capture(handler)) } returns future
        val seen = mutableListOf<ConverseStreamOutput>()
        val collector = launch { client.converseStreamFlow(request).toList(seen) }
        runCurrent()
        repeat(100) {
            handler.captured.onEventStream(RecordingSdkPublisher())
            runCurrent()
        }
        val latest = RecordingSdkPublisher<ConverseStreamOutput>()
        handler.captured.onEventStream(latest)
        runCurrent()
        val event = contentDelta("latest")
        latest.emitOne(event)
        runCurrent()
        latest.complete()
        future.complete(null)
        withTimeout(1_000) { collector.join() }
        seen shouldBeEqualTo listOf(event)
    }

    @Test
    fun concurrentReplacementCleanupAndTerminalFailureIsSerialized() = runTest {
        val handler = slot<ConverseStreamResponseHandler>()
        val future = CompletableFuture<Void>()
        val handlerReady = CountDownLatch(1)
        every { client.converseStream(request, capture(handler)) } answers {
            handlerReady.countDown()
            future
        }
        val operationFailure = ValidationException.builder().message("operation").build()
        val terminal = CompletableDeferred<Throwable>()
        val collector = launch(Dispatchers.Default) {
            try {
                client.converseStreamFlow(request).toList()
            } catch (cause: Throwable) {
                terminal.complete(cause)
            }
        }
        check(handlerReady.await(5, TimeUnit.SECONDS)) { "handler was not captured" }
        val first = RecordingSdkPublisher<ConverseStreamOutput>(onCancelled = { throw IllegalStateException("cancel") })
        handler.captured.onEventStream(first)
        handler.captured.onEventStream(RecordingSdkPublisher())
        future.completeExceptionally(operationFailure)

        val actual = withContext(Dispatchers.Default.limitedParallelism(2)) {
            withTimeout(5_000) { terminal.await() }
        }
        actual shouldBeSameInstanceAs operationFailure
        collector.join()
    }

    @Test
    fun `text delta flow preserves order empty text and SDK errors`() = runTest {
        val expected = ValidationException.builder().message("failed").build()
        val values = mutableListOf<String>()
        val actual = assertFailsWith<ValidationException> {
            flow {
                emit(contentDelta("a"))
                emit(MessageStopEvent.builder().build())
                emit(contentDelta(""))
                throw expected
            }.textDeltaFlow().toList(values)
        }

        values shouldBeEqualTo listOf("a", "")
        actual shouldBeSameInstanceAs expected
    }

    @Test
    fun `takeUntil ends after signalled upstream event`() = runTest {
        val stop = MutableSharedFlow<Unit>()
        val gate = Channel<Unit>(Channel.RENDEZVOUS)
        val values = mutableListOf<String>()
        val collector = launch {
            flow {
                emit("before")
                gate.receive()
                emit("after")
                emit("never")
            }.takeUntil(stop).toList(values)
        }
        runCurrent()

        stop.emit(Unit)
        gate.send(Unit)
        collector.join()

        values shouldBeEqualTo listOf("before")
    }

    private fun contentDelta(text: String): ContentBlockDeltaEvent =
        ContentBlockDeltaEvent.builder()
            .delta(ContentBlockDelta.builder().text(text).build())
            .build()

    private class CancelCountingFuture : CompletableFuture<Void>() {
        private val cancellations = AtomicInteger()

        val cancelCount: Int
            get() = cancellations.get()

        override fun cancel(mayInterruptIfRunning: Boolean): Boolean {
            cancellations.incrementAndGet()
            return super.cancel(mayInterruptIfRunning)
        }
    }
}
