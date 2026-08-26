package io.bluetape4k.aws.spring.modulith

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeInstanceOf
import io.bluetape4k.assertions.shouldBeSameInstanceAs
import io.bluetape4k.assertions.shouldBeTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.future.await
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import org.springframework.modulith.events.RoutingTarget
import org.springframework.modulith.events.support.EventExternalizationTransport
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class AwsModulithEventExternalizationTransportTest {

    @Test
    fun `implements the exact Modulith transport signature`() {
        EventExternalizationTransport::class.java.isAssignableFrom(
            AwsModulithEventExternalizationTransport::class.java,
        ).shouldBeTrue()

        val method = AwsModulithEventExternalizationTransport::class.java.getMethod(
            "externalize",
            Any::class.java,
            RoutingTarget::class.java,
        )
        method.returnType shouldBeSameInstanceAs CompletableFuture::class.java
    }

    @Test
    fun `future completes only after the target operation succeeds`() = runTest {
        val publisher = BlockingPublisher()
        transport(publisher = publisher).use { transport ->
            val future = transport.externalize(EVENT, target("events"))
            val pending = publisher.next()

            future.isDone.shouldBeFalse()
            pending.command.targetAlias shouldBeEqualTo "events"
            pending.command.destination shouldBeEqualTo "events"
            pending.command.routingKey shouldBeEqualTo null
            pending.command.eventId shouldBeEqualTo EVENT.id

            pending.succeed()

            future.await().shouldBeInstanceOf<AwsModulithPublishResult>()
        }
    }

    @Test
    fun `operation failures complete the future exceptionally`() = runTest {
        val publisher = BlockingPublisher()
        transport(publisher = publisher).use { transport ->
            val future = transport.externalize(EVENT, target("events"))
            val pending = publisher.next()
            val failure = AwsModulithPublishException()

            pending.fail(failure)

            val actual = kotlin.runCatching { future.join() }.exceptionOrNull()
                .shouldBeInstanceOf<CompletionException>()
            actual.cause shouldBeSameInstanceAs failure
        }
    }

    @Test
    fun `raw publisher failure is replaced while JVM Error keeps identity`() = runTest {
        val publisher = BlockingPublisher()
        transport(publisher = publisher).use { transport ->
            val rawFuture = transport.externalize(EVENT, target("events"))
            publisher.next().fail(IllegalStateException(HOSTILE_MARKER))

            val sanitized = rawFuture.failureCause().shouldBeInstanceOf<AwsModulithPublishException>()
            sanitized.cause shouldBeEqualTo null
            sanitized.message.orEmpty().contains(HOSTILE_MARKER).shouldBeFalse()
        }

        val fatal = AssertionError(HOSTILE_MARKER)
        transport(publisher = AwsModulithTargetPublisher { throw fatal }).use { transport ->
            val fatalFuture = transport.externalize(EVENT.copy(id = "evt-fatal"), target("events"))
            fatalFuture.failureCause() shouldBeSameInstanceAs fatal
        }
    }

    @Test
    fun `concurrent bounded admission rejects excess calls without a child and permits readmission`() {
        runBlocking {
            val publisher = BlockingPublisher()
            val executor = Executors.newFixedThreadPool(TOTAL_ADMISSIONS)
            try {
                transport(publisher = publisher, maxInFlight = MAX_IN_FLIGHT).use { transport ->
                    val ready = CountDownLatch(TOTAL_ADMISSIONS)
                    val start = CountDownLatch(1)
                    val admissionCalls = (1..TOTAL_ADMISSIONS).map { index ->
                        CompletableFuture.supplyAsync(
                            {
                                ready.countDown()
                                check(start.await(TEST_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))
                                transport.externalize(EVENT.copy(id = "evt-$index"), target("events"))
                            },
                            executor,
                        )
                    }
                    ready.await(TEST_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS).shouldBeTrue()
                    start.countDown()
                    val admissions = admissionCalls.map { it.get(TEST_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS) }
                    val accepted = admissions.filterNot(CompletableFuture<*>::isDone)
                    val rejected = admissions.filter(CompletableFuture<*>::isDone)
                    val pending = (1..MAX_IN_FLIGHT).map { publisher.next() }

                    accepted.size shouldBeEqualTo MAX_IN_FLIGHT
                    rejected.size shouldBeEqualTo EXCESS_ADMISSIONS
                    rejected.forEach { future ->
                        future.failureCause().shouldBeInstanceOf<AwsModulithProducerCapacityException>()
                    }
                    publisher.calls.get() shouldBeEqualTo MAX_IN_FLIGHT
                    publisher.maxActive.get() shouldBeEqualTo MAX_IN_FLIGHT
                    transport.metrics().acceptedCount shouldBeEqualTo MAX_IN_FLIGHT

                    pending.first().succeed()
                    withTimeout(TEST_TIMEOUT_MILLIS) {
                        while (accepted.none(CompletableFuture<*>::isDone)) kotlinx.coroutines.yield()
                    }
                    val readmitted = transport.externalize(EVENT.copy(id = "readmitted"), target("events"))
                    val readmittedPending = publisher.next()
                    publisher.calls.get() shouldBeEqualTo MAX_IN_FLIGHT + 1

                    pending.drop(1).forEach(PendingPublish::succeed)
                    readmittedPending.succeed()
                    accepted.forEach { it.await() }
                    readmitted.await()
                }
            } finally {
                executor.shutdownNow()
            }
        }
    }

    @Test
    fun `caller cancellation cancels the publisher child and releases admission`() = runTest {
        val publisher = BlockingPublisher()
        transport(publisher = publisher, maxInFlight = 1).use { transport ->
            val future = transport.externalize(EVENT, target("events"))
            publisher.next()

            future.cancel(false).shouldBeTrue()
            withTimeout(TEST_TIMEOUT_MILLIS) { publisher.cancelled.await() }
            withTimeout(TEST_TIMEOUT_MILLIS) { transport.awaitIdle() }

            val readmitted = transport.externalize(EVENT.copy(id = "evt-2"), target("events"))
            publisher.next().succeed()
            readmitted.await()
        }
    }

    @Test
    fun `observed AWS success wins over later caller cancellation`() = runTest {
        val publisher = BlockingPublisher()
        transport(publisher = publisher).use { transport ->
            val future = transport.externalize(EVENT, target("events"))
            publisher.next().succeed()
            future.await()

            future.cancel(false).shouldBeFalse()
            future.isCancelled.shouldBeFalse()
        }
    }

    @Test
    fun `future terminal state is published before close observes cleanup`() {
        runBlocking {
            val publisher = BlockingPublisher()
            val completionEntered = CountDownLatch(1)
            val releaseCompletion = CountDownLatch(1)
            val transport = transport(
                publisher = publisher,
                dispatcher = Dispatchers.Default,
                futureFactory = { PausingCompletionFuture(completionEntered, releaseCompletion) },
            )
            val future = transport.externalize(EVENT, target("events"))
            publisher.next().succeed()

            completionEntered.await(TEST_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS).shouldBeTrue()
            transport.metrics().acceptedCount shouldBeEqualTo 1
            val closeStarted = CountDownLatch(1)
            val closing = CompletableFuture.runAsync {
                closeStarted.countDown()
                transport.close()
            }
            closeStarted.await(TEST_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS).shouldBeTrue()
            closing.isDone.shouldBeFalse()

            releaseCompletion.countDown()
            future.await()
            closing.await()
            transport.metrics().acceptedCount shouldBeEqualTo 0
        }
    }

    @Test
    fun `close observes an admitted lazy child before its start`() {
        runBlocking {
            val publisher = BlockingPublisher()
            val executor = Executors.newFixedThreadPool(2)
            val startHookEntered = CountDownLatch(1)
            val releaseStartHook = CountDownLatch(1)
            val transport = transport(
                publisher = publisher,
                beforeJobStart = {
                    startHookEntered.countDown()
                    check(releaseStartHook.await(TEST_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))
                },
            )
            try {
                val externalize = CompletableFuture.supplyAsync(
                    { transport.externalize(EVENT, target("events")) },
                    executor,
                )
                startHookEntered.await(TEST_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS).shouldBeTrue()
                val closeStarted = CountDownLatch(1)
                val closing = CompletableFuture.runAsync(
                    {
                        closeStarted.countDown()
                        transport.close()
                    },
                    executor,
                )
                closeStarted.await(TEST_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS).shouldBeTrue()
                withTimeout(TEST_TIMEOUT_MILLIS) {
                    while (!transport.metrics().closing) kotlinx.coroutines.yield()
                }
                closing.isDone.shouldBeFalse()
                transport.metrics().acceptedCount shouldBeEqualTo 1

                releaseStartHook.countDown()
                val future = externalize.get(TEST_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
                publisher.next().succeed()
                future.await()
                closing.await()
                transport.metrics().acceptedCount shouldBeEqualTo 0
            } finally {
                releaseStartHook.countDown()
                transport.close()
                executor.shutdownNow()
            }
        }
    }

    @Test
    fun `close is shared rejects later admission and never closes publisher operations`() = runTest {
        val publisher = BlockingPublisher()
        val transport = transport(publisher = publisher)
        val inFlight = transport.externalize(EVENT, target("events"))
        val pending = publisher.next()

        val firstClose = CompletableFuture.runAsync { transport.close() }
        val secondClose = CompletableFuture.runAsync { transport.close() }
        withTimeout(TEST_TIMEOUT_MILLIS) {
            while (!transport.metrics().closing) kotlinx.coroutines.yield()
        }

        val rejected = transport.externalize(EVENT.copy(id = "evt-2"), target("events"))
        rejected.failureCause().shouldBeInstanceOf<AwsModulithProducerClosedException>()
        publisher.calls.get() shouldBeEqualTo 1

        pending.succeed()
        inFlight.await()
        firstClose.await()
        secondClose.await()
        transport.close()

        publisher.closed.get() shouldBeEqualTo 0
        transport.metrics().acceptedCount shouldBeEqualTo 0
        transport.metrics().residentChildCount shouldBeEqualTo 0
    }

    @Test
    fun `close timeout cancels remaining publication and leaves no resident child`() = runTest {
        val publisher = BlockingPublisher()
        val transport = transport(
            publisher = publisher,
            shutdownTimeout = Duration.ofMillis(50),
        )
        val future = transport.externalize(EVENT, target("events"))
        publisher.next()

        CompletableFuture.runAsync { transport.close() }.await()

        future.isCancelled.shouldBeTrue()
        withTimeout(TEST_TIMEOUT_MILLIS) { publisher.cancelled.await() }
        transport.metrics().acceptedCount shouldBeEqualTo 0
        transport.metrics().residentChildCount shouldBeEqualTo 0
        publisher.closed.get() shouldBeEqualTo 0
    }

    @Test
    fun `close timeout keeps a non-cooperative child visible until it actually terminates`() = runTest {
        val publisher = NonCooperativePublisher()
        val transport = transport(
            publisher = publisher,
            shutdownTimeout = Duration.ofMillis(50),
        )
        val future = transport.externalize(EVENT, target("events"))
        publisher.started.await()

        CompletableFuture.runAsync { transport.close() }.await()

        future.isCancelled.shouldBeTrue()
        transport.metrics().acceptedCount shouldBeEqualTo 1
        transport.metrics().residentChildCount shouldBeEqualTo 1

        publisher.release.complete(Unit)
        withTimeout(TEST_TIMEOUT_MILLIS) { transport.awaitIdle() }
        transport.metrics().acceptedCount shouldBeEqualTo 0
        transport.metrics().residentChildCount shouldBeEqualTo 0
    }

    private fun transport(
        publisher: AwsModulithTargetPublisher,
        maxInFlight: Int = 4,
        shutdownTimeout: Duration = Duration.ofSeconds(2),
        dispatcher: CoroutineDispatcher = Dispatchers.Unconfined,
        futureFactory: () -> CompletableFuture<AwsModulithPublishResult> = { CompletableFuture() },
        beforeJobStart: (() -> Unit)? = null,
    ): AwsModulithEventExternalizationTransport = AwsModulithEventExternalizationTransport(
        targets = mapOf(
            "events" to AwsModulithEventsProperties.Target(
                service = AwsModulithTargetService.SNS,
                destination = "events",
            )
        ),
        codec = object : AwsModulithEventCodec {
            override fun encode(event: Any): AwsModulithEncodedEvent {
                val typed = event as TestEvent
                return AwsModulithEncodedEvent(
                    body = "{\"id\":\"${typed.id}\"}",
                    messageAttributes = mapOf(DefaultAwsModulithEventCodec.SYSTEM_EVENT_ID to typed.id),
                )
            }

            override fun decode(body: String, attributes: Map<String, String>): Any = error("not used")
        },
        publishers = mapOf(AwsModulithTargetService.SNS to publisher),
        maxInFlight = maxInFlight,
        shutdownTimeout = shutdownTimeout,
        dispatcher = dispatcher,
        futureFactory = futureFactory,
        beforeJobStart = beforeJobStart,
    )

    private fun target(alias: String): RoutingTarget = RoutingTarget.forTarget(alias).withoutKey()

    private data class TestEvent(val id: String)

    private class BlockingPublisher : AwsModulithTargetPublisher, AutoCloseable {
        val calls = AtomicInteger()
        val closed = AtomicInteger()
        val cancelled = CompletableDeferred<Unit>()
        val maxActive = AtomicInteger()
        private val activePublishers = AtomicInteger()
        private val pending = Channel<PendingPublish>(Channel.UNLIMITED)

        override suspend fun publish(command: AwsModulithPublishCommand): AwsModulithPublishResult {
            calls.incrementAndGet()
            val active = activePublishers.incrementAndGet()
            maxActive.updateAndGet { current -> maxOf(current, active) }
            val response = CompletableDeferred<AwsModulithPublishResult>()
            pending.send(PendingPublish(command, response))
            return try {
                response.await()
            } catch (cancellation: CancellationException) {
                cancelled.complete(Unit)
                throw cancellation
            } finally {
                activePublishers.decrementAndGet()
            }
        }

        suspend fun next(): PendingPublish = withTimeout(TEST_TIMEOUT_MILLIS) { pending.receive() }

        override fun close() {
            closed.incrementAndGet()
        }
    }

    private class NonCooperativePublisher : AwsModulithTargetPublisher {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()

        override suspend fun publish(command: AwsModulithPublishCommand): AwsModulithPublishResult {
            started.complete(Unit)
            withContext(NonCancellable) { release.await() }
            return AwsModulithPublishResult(
                service = AwsModulithTargetService.SNS,
                targetAlias = command.targetAlias,
                providerMessageIdPresent = true,
            )
        }
    }

    private class PausingCompletionFuture(
        private val completionEntered: CountDownLatch,
        private val releaseCompletion: CountDownLatch,
    ) : CompletableFuture<AwsModulithPublishResult>() {
        override fun complete(value: AwsModulithPublishResult): Boolean {
            completionEntered.countDown()
            check(releaseCompletion.await(TEST_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))
            return super.complete(value)
        }
    }

    private class PendingPublish(
        val command: AwsModulithPublishCommand,
        private val response: CompletableDeferred<AwsModulithPublishResult>,
    ) {
        fun succeed() {
            response.complete(
                AwsModulithPublishResult(
                    service = AwsModulithTargetService.SNS,
                    targetAlias = command.targetAlias,
                    providerMessageIdPresent = true,
                )
            )
        }

        fun fail(failure: Throwable) {
            response.completeExceptionally(failure)
        }
    }

    private companion object {
        const val TEST_TIMEOUT_MILLIS = 2_000L
        const val MAX_IN_FLIGHT = 4
        const val EXCESS_ADMISSIONS = 32
        const val TOTAL_ADMISSIONS = MAX_IN_FLIGHT + EXCESS_ADMISSIONS
        const val HOSTILE_MARKER = "secret-value:event-id:header-value:arn:request-response"
        val EVENT = TestEvent("evt-1")
    }
}

private fun CompletableFuture<*>.failureCause(): Throwable =
    kotlin.runCatching { join() }.exceptionOrNull()
        .shouldBeInstanceOf<CompletionException>()
        .cause
        ?: error("missing completion cause")
