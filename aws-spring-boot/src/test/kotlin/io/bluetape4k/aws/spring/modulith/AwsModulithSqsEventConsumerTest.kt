package io.bluetape4k.aws.spring.modulith

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeInstanceOf
import io.bluetape4k.assertions.shouldBeSameInstanceAs
import io.bluetape4k.aws.spring.sqs.SqsAcknowledgement
import io.bluetape4k.aws.spring.sqs.SqsReceivedMessage
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import org.springframework.context.ApplicationEventPublisher
import org.springframework.modulith.events.EventExternalizationConfiguration
import software.amazon.awssdk.services.sqs.model.Message
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class AwsModulithSqsEventConsumerTest {

    @Test
    fun `cleanup timeout uses the smaller SQS stop timeout or lease third`() {
        AwsModulithSqsEventConsumer.cleanupTimeout(1_000, Duration.ofSeconds(30)) shouldBeEqualTo
            Duration.ofSeconds(1)
        AwsModulithSqsEventConsumer.cleanupTimeout(25_000, Duration.ofSeconds(30)) shouldBeEqualTo
            Duration.ofSeconds(10)
        AwsModulithSqsEventConsumer.cleanupTimeout(0, Duration.ofNanos(1)) shouldBeEqualTo
            Duration.ofMillis(1)
    }

    @Test
    fun `decode and registry validation finish before claim and completed duplicate skips publish`() = runTest {
        val store = RecordingStore(AwsModulithClaimResult.Completed)
        val published = AtomicInteger()
        val consumer = consumer(store = store, publisher = ApplicationEventPublisher { published.incrementAndGet() })

        consumer.consume(message()) shouldBeEqualTo AwsModulithConsumeOutcome.COMPLETED_DUPLICATE

        store.claimedKeys shouldBeEqualTo listOf(KEY)
        published.get() shouldBeEqualTo 0
    }

    @Test
    fun `in progress is retryable and never publishes`() = runTest {
        val store = RecordingStore(AwsModulithClaimResult.InProgress(NOW.plusSeconds(30)))
        val published = AtomicInteger()
        val consumer = consumer(store = store, publisher = ApplicationEventPublisher { published.incrementAndGet() })

        kotlin.runCatching { consumer.consume(message()) }.exceptionOrNull()
            .shouldBeInstanceOf<AwsModulithEventInProgressException>()
        published.get() shouldBeEqualTo 0
    }

    @Test
    fun `handler success completes claim and loop risk releases it`() = runTest {
        val registry = SimpleMeterRegistry()
        val successStore = RecordingStore(acquired())
        consumer(store = successStore, metrics = AwsModulithMetrics(registry)).consume(message()) shouldBeEqualTo
            AwsModulithConsumeOutcome.PROCESSED
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
        kotlin.runCatching { loopConsumer.consume(message()) }.exceptionOrNull()
            .shouldBeInstanceOf<AwsModulithInboundLoopRiskException>()
        loopStore.released.get() shouldBeEqualTo 1
        loopStore.completed.get() shouldBeEqualTo 0
    }

    @Test
    fun `ack failure after complete keeps completed state and duplicate skips dispatch`() = runTest {
        val store = RecordingStore(acquired())
        val published = AtomicInteger()
        val listener = AwsModulithSqsEventListener(
            consumer(store = store, publisher = ApplicationEventPublisher { published.incrementAndGet() })
        )
        val failingAcknowledgement = object : SqsAcknowledgement {
            override val completed: Boolean = false
            override suspend fun acknowledge() = throw IllegalStateException(HOSTILE)
            override suspend fun nack(timeoutSeconds: Int) = Unit
            override suspend fun changeVisibility(timeoutSeconds: Int) = Unit
        }

        repeat(2) {
            kotlin.runCatching { listener.onMessage(message(), failingAcknowledgement) }.exceptionOrNull()
                .shouldBeInstanceOf<AwsModulithAcknowledgementException>()
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
            publisher = ApplicationEventPublisher { throw IllegalArgumentException(HOSTILE) },
        )

        val actual = kotlin.runCatching { consumer.consume(message()) }.exceptionOrNull()
            .shouldBeInstanceOf<AwsModulithDispatchException>()

        actual.cause shouldBeEqualTo null
        actual.message.orEmpty().contains(HOSTILE) shouldBeEqualTo false
        actual.suppressed.single().shouldBeInstanceOf<AwsModulithCleanupException>()
        actual.suppressed.single().message.orEmpty().contains(HOSTILE) shouldBeEqualTo false
    }

    @Test
    fun `handler cancellation and JVM Error keep identity after release`() = runTest {
        val cancellation = CancellationException(HOSTILE)
        val cancelledStore = RecordingStore(acquired())
        val cancelled = consumer(
            store = cancelledStore,
            publisher = ApplicationEventPublisher { throw cancellation },
        )
        kotlin.runCatching { cancelled.consume(message()) }.exceptionOrNull() shouldBeSameInstanceAs cancellation
        cancelledStore.released.get() shouldBeEqualTo 1

        val fatal = AssertionError(HOSTILE)
        val fatalStore = RecordingStore(acquired())
        val failed = consumer(store = fatalStore, publisher = ApplicationEventPublisher { throw fatal })
        kotlin.runCatching { failed.consume(message()) }.exceptionOrNull() shouldBeSameInstanceAs fatal
        fatalStore.released.get() shouldBeEqualTo 1
    }

    @Test
    fun `complete stale result is not success`() = runTest {
        val registry = SimpleMeterRegistry()
        val store = RecordingStore(acquired(), completeResult = AwsModulithStoreMutation.STALE)

        kotlin.runCatching {
            consumer(store = store, metrics = AwsModulithMetrics(registry)).consume(message())
        }.exceptionOrNull()
            .shouldBeInstanceOf<AwsModulithClaimMutationException>()
        registry.get("bluetape4k.aws.modulith.events")
            .tag("phase", "claim")
            .tag("outcome", "failure")
            .counter()
            .count() shouldBeEqualTo 1.0
    }

    @Test
    fun `heartbeat renews during dispatch and stops before complete`() = runBlocking<Unit> {
        val store = RecordingStore(acquired())
        val dispatchEntered = CountDownLatch(1)
        val releaseDispatch = CountDownLatch(1)
        val consumer = consumer(
            store = store,
            publisher = ApplicationEventPublisher {
                dispatchEntered.countDown()
                check(releaseDispatch.await(TEST_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))
            },
            heartbeatInterval = Duration.ofMillis(10),
        )

        val outcome = async(Dispatchers.Default) { consumer.consume(message()) }
        dispatchEntered.await(TEST_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS) shouldBeEqualTo true
        withTimeout(TEST_TIMEOUT_MILLIS) {
            while (store.renewed.get() == 0) delay(1)
        }
        releaseDispatch.countDown()

        outcome.await() shouldBeEqualTo AwsModulithConsumeOutcome.PROCESSED
        val renewalsAfterComplete = store.renewed.get()
        delay(40)
        store.renewed.get() shouldBeEqualTo renewalsAfterComplete
        store.completed.get() shouldBeEqualTo 1
        store.activeClaims.get() shouldBeEqualTo 0
    }

    @Test
    fun `renew and complete raw failures stay no-ack typed claim failures`() = runBlocking<Unit> {
        val renewStore = RecordingStore(acquired(), renewFailure = IllegalStateException(HOSTILE))
        val dispatchEntered = CountDownLatch(1)
        val releaseDispatch = CountDownLatch(1)
        val renewConsumer = consumer(
            store = renewStore,
            publisher = blockingPublisher(dispatchEntered, releaseDispatch),
            heartbeatInterval = Duration.ofMillis(5),
        )
        val renewOutcome = async(Dispatchers.Default) {
            kotlin.runCatching { renewConsumer.consume(message()) }.exceptionOrNull()
        }
        dispatchEntered.await(TEST_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS) shouldBeEqualTo true
        withTimeout(TEST_TIMEOUT_MILLIS) {
            while (renewStore.renewed.get() == 0) delay(1)
        }
        releaseDispatch.countDown()
        val renewFailure = renewOutcome.await()
            .shouldBeInstanceOf<AwsModulithClaimMutationException>()
        renewFailure.cause shouldBeEqualTo null
        renewStore.completed.get() shouldBeEqualTo 0

        val completeStore = RecordingStore(acquired(), completeFailure = IllegalStateException(HOSTILE))
        val completeFailure = kotlin.runCatching { consumer(store = completeStore).consume(message()) }
            .exceptionOrNull()
            .shouldBeInstanceOf<AwsModulithClaimMutationException>()
        completeFailure.cause shouldBeEqualTo null
        completeStore.released.get() shouldBeEqualTo 0
    }

    @Test
    fun `renew cancellation is a no-complete failure and keeps identity`() = runBlocking<Unit> {
        val cancellation = CancellationException(HOSTILE)
        val store = RecordingStore(acquired(), renewFailure = cancellation)
        val dispatchEntered = CountDownLatch(1)
        val releaseDispatch = CountDownLatch(1)
        val consumer = consumer(
            store = store,
            publisher = blockingPublisher(dispatchEntered, releaseDispatch),
            heartbeatInterval = Duration.ofMillis(5),
        )
        val observed = java.util.concurrent.atomic.AtomicReference<Throwable?>()

        val outcome = async(Dispatchers.Default) {
            try {
                consumer.consume(message())
            } catch (failure: Throwable) {
                observed.set(failure)
                throw failure
            }
        }
        dispatchEntered.await(TEST_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS) shouldBeEqualTo true
        withTimeout(TEST_TIMEOUT_MILLIS) {
            while (store.renewed.get() == 0) delay(1)
        }
        releaseDispatch.countDown()
        kotlin.runCatching { outcome.await() }

        observed.get() shouldBeSameInstanceAs cancellation
        store.completed.get() shouldBeEqualTo 0
        store.released.get() shouldBeEqualTo 1
        store.activeClaims.get() shouldBeEqualTo 0
    }

    @Test
    fun `complete cancellation releases claim and keeps identity`() = runTest {
        val cancellation = CancellationException(HOSTILE)
        val store = RecordingStore(acquired(), completeFailure = cancellation)

        val actual = kotlin.runCatching { consumer(store = store).consume(message()) }.exceptionOrNull()

        actual shouldBeSameInstanceAs cancellation
        store.completed.get() shouldBeEqualTo 1
        store.released.get() shouldBeEqualTo 1
        store.activeClaims.get() shouldBeEqualTo 0
    }

    @Test
    fun `parent cancellation after blocked dispatch releases claim before propagation`() = runBlocking<Unit> {
        val store = RecordingStore(acquired())
        val dispatchEntered = CountDownLatch(1)
        val releaseDispatch = CountDownLatch(1)
        val cancellation = CancellationException(HOSTILE)
        val observed = java.util.concurrent.atomic.AtomicReference<Throwable?>()
        val consumer = consumer(
            store = store,
            publisher = ApplicationEventPublisher {
                dispatchEntered.countDown()
                check(releaseDispatch.await(TEST_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))
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
        dispatchEntered.await(TEST_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS) shouldBeEqualTo true
        outcome.cancel(cancellation)
        releaseDispatch.countDown()

        kotlin.runCatching { outcome.await() }
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
            sourceDecoder = AwsModulithInboundSourceDecoder { throw AwsModulithSourceException() },
            metrics = metrics,
        )
        kotlin.runCatching { sourceFailure.consume(message()) }.exceptionOrNull()
            .shouldBeInstanceOf<AwsModulithSourceException>()
        store.claimedKeys.size shouldBeEqualTo 0

        val capacityStore = RecordingStore(acquired(), claimFailure = AwsModulithClaimCapacityException())
        kotlin.runCatching { consumer(store = capacityStore, metrics = metrics).consume(message()) }.exceptionOrNull()
            .shouldBeInstanceOf<AwsModulithClaimCapacityException>()
        registry.get("bluetape4k.aws.modulith.events").tag("phase", "source").counter().count() shouldBeEqualTo 1.0
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
            publisher = ApplicationEventPublisher { throw IllegalStateException(HOSTILE) },
            cleanupTimeout = Duration.ofMillis(20),
        )
        val actual = withTimeout(500) {
            kotlin.runCatching { consumer.consume(message()) }.exceptionOrNull()
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

    private fun externalizing(): EventExternalizationConfiguration = object : EventExternalizationConfiguration {
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
        check(release.await(TEST_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))
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
    ) : AwsModulithEventIdempotencyStore {
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

    private companion object {
        const val HOSTILE = "secret:event-id:arn:payload"
        const val TEST_TIMEOUT_MILLIS = 2_000L
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
            idempotency = AwsModulithEventsProperties.Idempotency(leaseDuration = Duration.ofSeconds(30)),
        )
    }
}
