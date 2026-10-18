package io.bluetape4k.aws.spring.modulith

import io.bluetape4k.assertions.assertFails
import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeInstanceOf
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldBeSameInstanceAs
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldNotContain
import io.bluetape4k.aws.spring.sqs.SqsAcknowledgement
import io.bluetape4k.aws.spring.sqs.SqsReceivedMessage
import io.bluetape4k.concurrent.await
import io.bluetape4k.javatimes.millis
import io.bluetape4k.javatimes.nanos
import io.bluetape4k.javatimes.seconds
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.awaitility.kotlin.atMost
import org.awaitility.kotlin.await
import org.awaitility.kotlin.until
import org.junit.jupiter.api.Test
import org.springframework.context.ApplicationEventPublisher
import org.springframework.modulith.events.EventExternalizationConfiguration
import software.amazon.awssdk.services.sqs.model.Message
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration.Companion.milliseconds

class AwsModulithSqsEventConsumerTest {

    private companion object: KLogging() {
        const val HOSTILE = "secret:event-id:arn:payload"
        const val TEST_TIMEOUT_MILLIS = 2_000L
        val TEST_TIMEOUT = 2000.milliseconds
        val NOW: Instant = Instant.parse("2026-08-26T00:00:00Z")
        val EVENT = TestEvent("evt-1")
        val KEY = AwsModulithEventKey("orders.created", "evt-1")

        val REGISTRY = AwsModulithEventTypeRegistry.of(
            AwsModulithEventTypeRegistration("orders.created", 1, TestEvent::class.java, TestEvent::id)
        )

        val PROPERTIES = AwsModulithEventsProperties.Consumer(
            enabled = true,
            queue = "events",
            sourceMode = AwsModulithSourceMode.DIRECT,
            idempotency = AwsModulithEventsProperties.Idempotency(leaseDuration = 30.seconds()),
        )
    }

    @Test
    fun `cleanup timeout uses the smaller SQS stop timeout or lease third`() {
        AwsModulithSqsEventConsumer
            .cleanupTimeout(1_000, 30.seconds()) shouldBeEqualTo 1.seconds()

        AwsModulithSqsEventConsumer
            .cleanupTimeout(25_000, 30.seconds()) shouldBeEqualTo 10.seconds()

        AwsModulithSqsEventConsumer
            .cleanupTimeout(0, 1.nanos()) shouldBeEqualTo 1.millis()
    }

    @Test
    fun `decode and registry validation finish before claim and completed duplicate skips publish`() = runTest {
        val store = RecordingStore(AwsModulithClaimResult.Completed)
        val published = AtomicInteger()
        val consumer = consumer(store = store, publisher = { published.incrementAndGet() })

        consumer.consume(message()) shouldBeEqualTo AwsModulithConsumeOutcome.COMPLETED_DUPLICATE

        store.claimedKeys shouldBeEqualTo listOf(KEY)
        published.get() shouldBeEqualTo 0
    }

    @Test
    fun `in progress is retryable and never publishes`() = runTest {
        val store = RecordingStore(AwsModulithClaimResult.InProgress(NOW + 30.seconds()))
        val published = AtomicInteger()
        val consumer = consumer(store = store, publisher = { published.incrementAndGet() })

        assertFailsWith<AwsModulithEventInProgressException> {
            consumer.consume(message())
        }
        published.get() shouldBeEqualTo 0
    }

    @Test
    fun `handler success completes claim and loop risk releases it`() = runTest {
        val registry = SimpleMeterRegistry()
        val successStore = RecordingStore(acquired())

        consumer(store = successStore, metrics = AwsModulithMetrics(registry))
            .consume(message()) shouldBeEqualTo AwsModulithConsumeOutcome.PROCESSED

        successStore.completed.get() shouldBeEqualTo 1
        successStore.released.get() shouldBeEqualTo 0
        successStore.activeClaims.get() shouldBeEqualTo 0

        registry.get("bluetape4k.aws.modulith.events")
            .tag("phase", "claim")
            .tag("outcome", "success")
            .counter()
            .count() shouldBeEqualTo 1.0

        registry.get("bluetape4k.aws.modulith.events")
            .tag("phase", "claim")
            .tag("outcome", "completed")
            .counter()
            .count() shouldBeEqualTo 1.0

        val loopStore = RecordingStore(acquired())
        val loopConsumer = consumer(store = loopStore, externalization = externalizing())

        assertFailsWith<AwsModulithInboundLoopRiskException> {
            loopConsumer.consume(message())
        }
        loopStore.released.get() shouldBeEqualTo 1
        loopStore.completed.get() shouldBeEqualTo 0
    }

    @Test
    fun `ack failure after complete keeps completed state and duplicate skips dispatch`() = runTest {
        val store = RecordingStore(acquired())
        val published = AtomicInteger()
        val listener = AwsModulithSqsEventListener(
            consumer(store = store, publisher = { published.incrementAndGet() })
        )
        val failingAcknowledgement = object: SqsAcknowledgement {
            override val completed: Boolean = false
            override suspend fun acknowledge() = throw IllegalStateException(HOSTILE)
            override suspend fun nack(timeoutSeconds: Int) = Unit
            override suspend fun changeVisibility(timeoutSeconds: Int) = Unit
        }

        repeat(2) {
            assertFailsWith<AwsModulithAcknowledgementException> {
                listener.onMessage(message(), failingAcknowledgement)
            }
        }

        store.completed.get() shouldBeEqualTo 1
        store.released.get() shouldBeEqualTo 0
        published.get() shouldBeEqualTo 1
    }

    @Test
    fun `handler failure is sanitized and cleanup failure is bounded suppressed`() = runTest {
        val store = RecordingStore(acquired(), releaseFailure = IllegalStateException(HOSTILE))
        val consumer = consumer(
            store = store,
            publisher = { throw IllegalArgumentException(HOSTILE) },
        )

        val actual = assertFailsWith<AwsModulithDispatchException> {
            consumer.consume(message())
        }

        actual.cause.shouldBeNull()
        actual.message.orEmpty().contains(HOSTILE).shouldBeFalse()
        actual.suppressed.single().shouldBeInstanceOf<AwsModulithCleanupException>()
        actual.suppressed.single().message shouldNotContain HOSTILE
    }

    @Test
    fun `handler cancellation and JVM Error keep identity after release`() = runTest {
        val cancellation = CancellationException(HOSTILE)
        val cancelledStore = RecordingStore(acquired())
        val cancelled = consumer(
            store = cancelledStore,
            publisher = { throw cancellation },
        )
        assertFails { cancelled.consume(message()) } shouldBeSameInstanceAs cancellation
        cancelledStore.released.get() shouldBeEqualTo 1

        val fatal = AssertionError(HOSTILE)
        val fatalStore = RecordingStore(acquired())
        val failed = consumer(store = fatalStore, publisher = { throw fatal })

        assertFails { failed.consume(message()) } shouldBeSameInstanceAs fatal
        fatalStore.released.get() shouldBeEqualTo 1
    }

    @Test
    fun `complete stale result is not success`() = runTest {
        val registry = SimpleMeterRegistry()
        val store = RecordingStore(acquired(), completeResult = AwsModulithStoreMutation.STALE)

        assertFailsWith<AwsModulithClaimMutationException> {
            consumer(store = store, metrics = AwsModulithMetrics(registry)).consume(message())
        }

        registry.get("bluetape4k.aws.modulith.events")
            .tag("phase", "claim")
            .tag("outcome", "failure")
            .counter()
            .count() shouldBeEqualTo 1.0
    }

    @Test
    fun `heartbeat renews during dispatch and stops before complete`() = runSuspendIO {
        val store = RecordingStore(acquired())
        val dispatchEntered = CountDownLatch(1)
        val releaseDispatch = CountDownLatch(1)
        val consumer = consumer(
            store = store,
            publisher = {
                dispatchEntered.countDown()
                check(releaseDispatch.await(TEST_TIMEOUT))
            },
            heartbeatInterval = 10.millis(),
        )

        val outcome = async(Dispatchers.Default) {
            consumer.consume(message())
        }
        dispatchEntered.await(TEST_TIMEOUT).shouldBeTrue()

        await atMost TEST_TIMEOUT until {
            store.renewed.get() > 0
        }
        releaseDispatch.countDown()

        outcome.await() shouldBeEqualTo AwsModulithConsumeOutcome.PROCESSED
        val renewalsAfterComplete = store.renewed.get()
        delay(40.milliseconds)

        log.debug { "store=$store" }
        store.renewed.get() shouldBeEqualTo renewalsAfterComplete
        store.completed.get() shouldBeEqualTo 1
        store.activeClaims.get() shouldBeEqualTo 0
    }

    @Test
    fun `renew and complete raw failures stay no-ack typed claim failures`() = runSuspendIO {
        val renewStore = RecordingStore(acquired(), renewFailure = IllegalStateException(HOSTILE))
        val dispatchEntered = CountDownLatch(1)
        val releaseDispatch = CountDownLatch(1)
        val renewConsumer = consumer(
            store = renewStore,
            publisher = blockingPublisher(dispatchEntered, releaseDispatch),
            heartbeatInterval = 5.millis(),
        )
        val renewOutcome = async(Dispatchers.Default) {
            runCatching { renewConsumer.consume(message()) }.exceptionOrNull()
        }
        dispatchEntered.await(TEST_TIMEOUT).shouldBeTrue()

        await atMost TEST_TIMEOUT until {
            renewStore.renewed.get() > 0
        }

        releaseDispatch.countDown()
        val renewFailure = renewOutcome.await().shouldBeInstanceOf<AwsModulithClaimMutationException>()
        renewFailure.cause.shouldBeNull()
        renewStore.completed.get() shouldBeEqualTo 0

        val completeStore = RecordingStore(acquired(), completeFailure = IllegalStateException(HOSTILE))
        val completeFailure = assertFailsWith<AwsModulithClaimMutationException> {
            consumer(store = completeStore).consume(message())
        }

        completeFailure.cause.shouldBeNull()
        completeStore.released.get() shouldBeEqualTo 0
    }

    @Test
    fun `renew cancellation is a no-complete failure and keeps identity`() = runSuspendIO {
        val cancellation = CancellationException(HOSTILE)
        val store = RecordingStore(acquired(), renewFailure = cancellation)
        val dispatchEntered = CountDownLatch(1)
        val releaseDispatch = CountDownLatch(1)
        val consumer = consumer(
            store = store,
            publisher = blockingPublisher(dispatchEntered, releaseDispatch),
            heartbeatInterval = 5.millis(),
        )
        val observed = AtomicReference<Throwable?>()

        val outcome = async(Dispatchers.Default) {
            try {
                consumer.consume(message())
            } catch (failure: Throwable) {
                observed.set(failure)
                throw failure
            }
        }
        dispatchEntered.await(TEST_TIMEOUT).shouldBeTrue()

        await atMost TEST_TIMEOUT until {
            store.renewed.get() > 0
        }

        releaseDispatch.countDown()
        assertFails { outcome.await() }

        observed.get() shouldBeSameInstanceAs cancellation
        store.completed.get() shouldBeEqualTo 0
        store.released.get() shouldBeEqualTo 1
        store.activeClaims.get() shouldBeEqualTo 0
    }

    @Test
    fun `complete cancellation releases claim and keeps identity`() = runTest {
        val cancellation = CancellationException(HOSTILE)
        val store = RecordingStore(acquired(), completeFailure = cancellation)

        val actual = assertFails { consumer(store = store).consume(message()) }

        actual shouldBeSameInstanceAs cancellation
        store.completed.get() shouldBeEqualTo 1
        store.released.get() shouldBeEqualTo 1
        store.activeClaims.get() shouldBeEqualTo 0
    }

    @Test
    fun `parent cancellation after blocked dispatch releases claim before propagation`() = runSuspendIO {
        val store = RecordingStore(acquired())
        val dispatchEntered = CountDownLatch(1)
        val releaseDispatch = CountDownLatch(1)
        val cancellation = CancellationException(HOSTILE)
        val observed = java.util.concurrent.atomic.AtomicReference<Throwable?>()
        val consumer = consumer(
            store = store,
            publisher = {
                dispatchEntered.countDown()
                check(releaseDispatch.await(TEST_TIMEOUT))
            },
            heartbeatInterval = Duration.ofMillis(10),
        )

        val outcome = async(Dispatchers.Default) {
            try {
                consumer.consume(message())
            } catch (failure: Throwable) {
                observed.set(failure)
                throw failure
            }
        }
        dispatchEntered.await(TEST_TIMEOUT).shouldBeTrue()
        outcome.cancel(cancellation)
        releaseDispatch.countDown()

        assertFails { outcome.await() }

        observed.get().shouldBeInstanceOf<CancellationException>()
        store.released.get() shouldBeEqualTo 1
        store.completed.get() shouldBeEqualTo 0
        store.activeClaims.get() shouldBeEqualTo 0
    }

    @Test
    fun `source rejection and claim capacity happen before dispatch`() = runTest {
        val registry = SimpleMeterRegistry()
        val metrics = AwsModulithMetrics(registry)
        val store = RecordingStore(acquired())
        val sourceFailure = consumer(
            store = store,
            sourceDecoder = { throw AwsModulithSourceException() },
            metrics = metrics,
        )
        assertFailsWith<AwsModulithSourceException> {
            sourceFailure.consume(message())
        }
        store.claimedKeys.size shouldBeEqualTo 0

        val capacityStore = RecordingStore(acquired(), claimFailure = AwsModulithClaimCapacityException())
        assertFailsWith<AwsModulithClaimCapacityException> {
            consumer(store = capacityStore, metrics = metrics).consume(message())
        }

        registry.get("bluetape4k.aws.modulith.events")
            .tag("phase", "source")
            .counter()
            .count() shouldBeEqualTo 1.0
        registry.get("bluetape4k.aws.modulith.events")
            .tag("phase", "claim")
            .tag("outcome", "rejected")
            .counter()
            .count() shouldBeEqualTo 1.0
    }

    @Test
    fun `cooperative release timeout is bounded and attached without replacing handler failure`() = runTest {
        val store = RecordingStore(acquired(), releaseBlock = { awaitCancellation() })
        val consumer = consumer(
            store = store,
            publisher = { throw IllegalStateException(HOSTILE) },
            cleanupTimeout = Duration.ofMillis(20),
        )
        val actual = withTimeout(500.milliseconds) {
            assertFails { consumer.consume(message()) }
        }

        actual.shouldBeInstanceOf<AwsModulithDispatchException>()
            .suppressed.single().shouldBeInstanceOf<AwsModulithCleanupException>()
    }

    private fun consumer(
        store: AwsModulithEventIdempotencyStore,
        publisher: ApplicationEventPublisher = ApplicationEventPublisher {},
        externalization: EventExternalizationConfiguration = EventExternalizationConfiguration.disabled(),
        sourceDecoder: AwsModulithInboundSourceDecoder = AwsModulithInboundSourceDecoder {
            AwsModulithDecodedInboundEvent(EVENT, KEY)
        },
        heartbeatInterval: Duration = PROPERTIES.idempotency.leaseDuration.dividedBy(3),
        cleanupTimeout: Duration = Duration.ofMillis(100),
        metrics: AwsModulithMetrics = AwsModulithMetrics(),
    ): AwsModulithSqsEventConsumer = AwsModulithSqsEventConsumer(
        sourceDecoder = sourceDecoder,
        registry = REGISTRY,
        store = store,
        externalization = externalization,
        eventPublisher = publisher,
        properties = PROPERTIES,
        metrics = metrics,
        clock = java.time.Clock.fixed(NOW, ZoneOffset.UTC),
        cleanupTimeout = cleanupTimeout,
        heartbeatInterval = heartbeatInterval,
    )

    private fun externalizing(): EventExternalizationConfiguration = object: EventExternalizationConfiguration {
        override fun supports(event: Any): Boolean = true
        override fun map(event: Any): Any = event
        override fun determineTarget(event: Any) = error("not used")
        override fun getHeadersFor(event: Any): Map<String, Any> = emptyMap()
        override fun serializeExternalization(): Boolean = false
    }

    private fun blockingPublisher(
        entered: CountDownLatch,
        release: CountDownLatch,
    ): ApplicationEventPublisher = ApplicationEventPublisher {
        entered.countDown()
        check(release.await(TEST_TIMEOUT))
    }

    private fun message(): SqsReceivedMessage = SqsReceivedMessage(
        queueUrl = "http://localhost/queue/events",
        message = Message.builder().messageId("message-1").receiptHandle("receipt").body("{}").build(),
    )

    private fun acquired(): AwsModulithClaimResult.Acquired = AwsModulithClaimResult.Acquired(
        AwsModulithClaimToken(KEY, "owner", 1, NOW.plusSeconds(30))
    )

    private class RecordingStore(
        private val claimResult: AwsModulithClaimResult,
        private val completeResult: AwsModulithStoreMutation = AwsModulithStoreMutation.APPLIED,
        private val releaseFailure: Throwable? = null,
        private val claimFailure: Throwable? = null,
        private val renewFailure: Throwable? = null,
        private val completeFailure: Throwable? = null,
        private val releaseBlock: (suspend () -> Unit)? = null,
    ): AwsModulithEventIdempotencyStore {
        val claimedKeys = mutableListOf<AwsModulithEventKey>()
        val completed = AtomicInteger()
        val released = AtomicInteger()
        val renewed = AtomicInteger()
        val activeClaims = AtomicInteger()
        private var completedState = false

        override suspend fun claim(key: AwsModulithEventKey, leaseDuration: Duration): AwsModulithClaimResult {
            claimedKeys += key
            claimFailure?.let { throw it }
            return if (completedState) {
                AwsModulithClaimResult.Completed
            } else {
                claimResult.also { if (it is AwsModulithClaimResult.Acquired) activeClaims.set(1) }
            }
        }

        override suspend fun renew(token: AwsModulithClaimToken, leaseDuration: Duration): AwsModulithClaimToken {
            renewed.incrementAndGet()
            renewFailure?.let { throw it }
            return token.copy(leaseUntil = token.leaseUntil.plus(leaseDuration))
        }

        override suspend fun complete(token: AwsModulithClaimToken): AwsModulithStoreMutation {
            completed.incrementAndGet()
            completeFailure?.let { throw it }
            if (completeResult == AwsModulithStoreMutation.APPLIED ||
                completeResult == AwsModulithStoreMutation.ALREADY_APPLIED
            ) {
                completedState = true
                activeClaims.set(0)
            }
            return completeResult
        }

        override suspend fun release(token: AwsModulithClaimToken): AwsModulithStoreMutation {
            released.incrementAndGet()
            releaseBlock?.invoke()
            releaseFailure?.let { throw it }
            activeClaims.set(0)
            return AwsModulithStoreMutation.APPLIED
        }

        override suspend fun recoverExpired(now: Instant): Int = 0
    }

    private data class TestEvent(val id: String)

}
