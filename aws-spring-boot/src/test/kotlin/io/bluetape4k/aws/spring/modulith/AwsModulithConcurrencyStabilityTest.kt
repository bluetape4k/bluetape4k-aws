package io.bluetape4k.aws.spring.modulith

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeInstanceOf
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.aws.spring.sqs.SqsReceivedMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.future.await
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.jupiter.api.RepeatedTest
import org.springframework.context.ApplicationEventPublisher
import org.springframework.modulith.events.EventExternalizationConfiguration
import org.springframework.modulith.events.RoutingTarget
import software.amazon.awssdk.services.sqs.model.Message
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds

class AwsModulithConcurrencyStabilityTest {

    @RepeatedTest(REPETITIONS)
    fun `transport close rejects later admission and releases every child and permit`() = runTest(timeout = 5.seconds) {
        val publisher = BarrierTargetPublisher()
        val transport = transport(publisher)
        val accepted = transport.externalize(EVENT, target())
        publisher.entered.await()

        val closing = CompletableFuture.runAsync(transport::close)
        withTimeout(TEST_TIMEOUT_MILLIS) {
            while (!transport.metrics().closing) yield()
        }
        val rejected = transport.externalize(EVENT.copy(id = "evt-rejected"), target())

        rejected.failureCause().shouldBeInstanceOf<AwsModulithProducerClosedException>()
        publisher.calls.get() shouldBeEqualTo 1
        publisher.release.complete(Unit)
        accepted.await()
        closing.await()
        transport.awaitIdle()

        publisher.active.get() shouldBeEqualTo 0
        transport.metrics().acceptedCount shouldBeEqualTo 0
        transport.metrics().residentChildCount shouldBeEqualTo 0
        transport.metrics().availablePermits shouldBeEqualTo 1
    }

    @RepeatedTest(REPETITIONS)
    fun `expired claim takeover fences the stale owner and releases active capacity`() = runTest(timeout = 5.seconds) {
        val clock = MutableStabilityClock()
        val lease = Duration.ofSeconds(30)
        val ownerSequence = AtomicInteger()
        val store = InMemoryAwsModulithEventIdempotencyStore(
            properties = AwsModulithEventsProperties.Idempotency(
                maxEntries = 1,
                maxInProgress = 1,
                maxKeyBytes = 1_024,
                retention = Duration.ofHours(1),
                leaseDuration = lease,
            ),
            clock = clock,
            ownerIdSupplier = { "owner-${ownerSequence.incrementAndGet()}" },
        )

        store.use {
            val first = store.claim(KEY, lease).acquiredToken()
            clock.advance(lease.plusNanos(1))
            val takeover = store.claim(KEY, lease).acquiredToken()

            takeover.generation shouldBeEqualTo first.generation + 1
            takeover.ownerId shouldBeEqualTo "owner-2"
            store.complete(first) shouldBeEqualTo AwsModulithStoreMutation.STALE
            store.metrics().inProgressCount shouldBeEqualTo 1
            store.complete(takeover) shouldBeEqualTo AwsModulithStoreMutation.APPLIED
            store.metrics().inProgressCount shouldBeEqualTo 0
        }
    }

    @RepeatedTest(REPETITIONS)
    fun `consumer cancellation releases its claim without duplicate dispatch`() = runTest(timeout = 5.seconds) {
        val store = TrackingClaimStore()
        val dispatchEntered = CountDownLatch(1)
        val releaseDispatch = CountDownLatch(1)
        val publisherCalls = AtomicInteger()
        val consumer = consumer(
            store = store,
            publisher = ApplicationEventPublisher {
                publisherCalls.incrementAndGet()
                dispatchEntered.countDown()
                check(releaseDispatch.await(TEST_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))
            },
        )
        val outcome = async(Dispatchers.Default) { consumer.consume(message()) }

        dispatchEntered.await(TEST_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS).shouldBeTrue()
        outcome.cancel(CancellationException("test cancellation"))
        releaseDispatch.countDown()
        kotlin.runCatching { outcome.await() }

        publisherCalls.get() shouldBeEqualTo 1
        store.completed.get() shouldBeEqualTo 0
        store.released.get() shouldBeEqualTo 1
        store.activeClaims.get() shouldBeEqualTo 0
        outcome.isCancelled.shouldBeTrue()
    }

    private fun transport(publisher: AwsModulithTargetPublisher): AwsModulithEventExternalizationTransport =
        AwsModulithEventExternalizationTransport(
            targets = mapOf(
                TARGET_ALIAS to AwsModulithEventsProperties.Target(
                    service = AwsModulithTargetService.SNS,
                    destination = TARGET_ALIAS,
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
            maxInFlight = 1,
            shutdownTimeout = Duration.ofSeconds(2),
            dispatcher = Dispatchers.Default,
        )

    private fun consumer(
        store: AwsModulithEventIdempotencyStore,
        publisher: ApplicationEventPublisher,
    ): AwsModulithSqsEventConsumer = AwsModulithSqsEventConsumer(
        sourceDecoder = AwsModulithInboundSourceDecoder { AwsModulithDecodedInboundEvent(EVENT, KEY) },
        registry = REGISTRY,
        store = store,
        externalization = EventExternalizationConfiguration.disabled(),
        eventPublisher = publisher,
        properties = CONSUMER_PROPERTIES,
        metrics = AwsModulithMetrics(),
        clock = Clock.fixed(NOW, ZoneOffset.UTC),
        cleanupTimeout = Duration.ofMillis(100),
        heartbeatInterval = Duration.ofSeconds(10),
    )

    private fun target(): RoutingTarget = RoutingTarget.forTarget(TARGET_ALIAS).withoutKey()

    private fun message(): SqsReceivedMessage = SqsReceivedMessage(
        queueUrl = "http://localhost/queue/events",
        message = Message.builder().messageId("message-1").receiptHandle("receipt").body("{}").build(),
    )

    private class BarrierTargetPublisher : AwsModulithTargetPublisher {
        val calls = AtomicInteger()
        val active = AtomicInteger()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()

        override suspend fun publish(command: AwsModulithPublishCommand): AwsModulithPublishResult {
            calls.incrementAndGet()
            active.incrementAndGet()
            entered.complete(Unit)
            return try {
                release.await()
                AwsModulithPublishResult(
                    service = AwsModulithTargetService.SNS,
                    targetAlias = command.targetAlias,
                    providerMessageIdPresent = true,
                )
            } finally {
                active.decrementAndGet()
            }
        }
    }

    private class TrackingClaimStore : AwsModulithEventIdempotencyStore {
        val activeClaims = AtomicInteger()
        val completed = AtomicInteger()
        val released = AtomicInteger()

        override suspend fun claim(
            key: AwsModulithEventKey,
            leaseDuration: Duration,
        ): AwsModulithClaimResult = AwsModulithClaimResult.Acquired(
            AwsModulithClaimToken(key, "owner", 1, NOW.plus(leaseDuration))
        ).also { activeClaims.incrementAndGet() }

        override suspend fun renew(
            token: AwsModulithClaimToken,
            leaseDuration: Duration,
        ): AwsModulithClaimToken = token.copy(leaseUntil = token.leaseUntil.plus(leaseDuration))

        override suspend fun complete(token: AwsModulithClaimToken): AwsModulithStoreMutation {
            completed.incrementAndGet()
            activeClaims.decrementAndGet()
            return AwsModulithStoreMutation.APPLIED
        }

        override suspend fun release(token: AwsModulithClaimToken): AwsModulithStoreMutation {
            released.incrementAndGet()
            activeClaims.decrementAndGet()
            return AwsModulithStoreMutation.APPLIED
        }

        override suspend fun recoverExpired(now: Instant): Int = 0
    }

    private class MutableStabilityClock(
        private var current: Instant = NOW,
        private val zone: ZoneId = ZoneOffset.UTC,
    ) : Clock() {
        override fun getZone(): ZoneId = zone

        override fun withZone(zone: ZoneId): Clock = MutableStabilityClock(current, zone)

        override fun instant(): Instant = current

        fun advance(duration: Duration) {
            current = current.plus(duration)
        }
    }

    private data class TestEvent(val id: String)

    private companion object {
        const val REPETITIONS = 100
        const val TEST_TIMEOUT_MILLIS = 2_000L
        const val TARGET_ALIAS = "events"
        val NOW: Instant = Instant.parse("2026-08-26T00:00:00Z")
        val EVENT = TestEvent("evt-1")
        val KEY = AwsModulithEventKey("orders.created", EVENT.id)
        val REGISTRY = AwsModulithEventTypeRegistry.of(
            AwsModulithEventTypeRegistration("orders.created", 1, TestEvent::class.java, TestEvent::id)
        )
        val CONSUMER_PROPERTIES = AwsModulithEventsProperties.Consumer(
            enabled = true,
            queue = "events",
            sourceMode = AwsModulithSourceMode.DIRECT,
            idempotency = AwsModulithEventsProperties.Idempotency(leaseDuration = Duration.ofSeconds(30)),
        )
    }
}

private fun AwsModulithClaimResult.acquiredToken(): AwsModulithClaimToken {
    require(this is AwsModulithClaimResult.Acquired)
    return token
}

private fun CompletableFuture<*>.failureCause(): Throwable =
    kotlin.runCatching { join() }.exceptionOrNull()
        .shouldBeInstanceOf<CompletionException>()
        .cause
        ?: error("missing completion cause")
