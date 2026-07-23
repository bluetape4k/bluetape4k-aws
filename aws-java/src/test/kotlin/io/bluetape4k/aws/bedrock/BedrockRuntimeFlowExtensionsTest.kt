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
import java.util.concurrent.atomic.AtomicInteger

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
