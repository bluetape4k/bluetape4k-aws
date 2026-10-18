package io.bluetape4k.aws.spring.modulith

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeAfter
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeInstanceOf
import io.bluetape4k.assertions.shouldBeSameInstanceAs
import io.bluetape4k.javatimes.hours
import io.bluetape4k.javatimes.minutes
import io.bluetape4k.javatimes.nanos
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicInteger

class InMemoryAwsModulithEventIdempotencyStoreTest: AwsModulithEventIdempotencyStoreContract() {

    private companion object {
        val LEASE_DURATION: Duration = 2.minutes()

        fun key(eventId: String): AwsModulithEventKey = AwsModulithEventKey("order.placed", eventId)
    }

    override fun createStore(): AwsModulithEventIdempotencyStore = InMemoryAwsModulithEventIdempotencyStore()

    @Test
    fun `expired lease is taken over with a higher fencing generation`() = runTest {
        val clock = MutableClock()
        store(clock = clock).use { store ->
            val first = store.claim(key("evt-1"), LEASE_DURATION).acquiredToken()
            clock.advance(LEASE_DURATION + 1.nanos())

            val takeover = store.claim(key("evt-1"), LEASE_DURATION).acquiredToken()

            takeover.generation shouldBeEqualTo first.generation + 1
            takeover.leaseUntil shouldBeAfter first.leaseUntil
            store.complete(first) shouldBeEqualTo AwsModulithStoreMutation.STALE
        }
    }

    @Test
    fun `entry and in progress capacity never evict active claims`() = runTest {
        store(maxEntries = 2, maxInProgress = 2).use { store ->
            store.claim(key("evt-1"), LEASE_DURATION)
            store.claim(key("evt-2"), LEASE_DURATION)

            assertFailsWith<AwsModulithClaimCapacityException> {
                store.claim(key("evt-3"), LEASE_DURATION)
            }
            store.claim(key("evt-1"), LEASE_DURATION)
                .shouldBeInstanceOf<AwsModulithClaimResult.InProgress>()
        }

        store(maxEntries = 3, maxInProgress = 1).use { store ->
            store.claim(key("evt-1"), LEASE_DURATION)

            assertFailsWith<AwsModulithClaimCapacityException> {
                store.claim(key("evt-2"), LEASE_DURATION)
            }
        }
    }

    @Test
    fun `recovered claims release entry and key byte capacity`() = runTest {
        store(maxEntries = 1, maxInProgress = 1, maxKeyBytes = 17).use { store ->
            val recovered = store.claim(key("evt-1"), LEASE_DURATION).acquiredToken()

            store.recoverExpired(recovered.leaseUntil.plusNanos(1)) shouldBeEqualTo 1

            store.claim(key("evt-2"), LEASE_DURATION)
                .shouldBeInstanceOf<AwsModulithClaimResult.Acquired>()
        }
    }

    @Test
    fun `total UTF8 key bytes are bounded independently`() = runTest {
        store(maxEntries = 10, maxInProgress = 10, maxKeyBytes = 4).use { store ->
            store.claim(AwsModulithEventKey("a", "123"), LEASE_DURATION)

            assertFailsWith<AwsModulithClaimCapacityException> {
                store.claim(AwsModulithEventKey("가", "1"), LEASE_DURATION)
            }
        }
    }

    @Test
    fun `completed entries expire by retention and are evicted by LRU`() = runTest {
        val clock = MutableClock()
        store(maxEntries = 2, maxInProgress = 2, retention = 1.minutes(), clock = clock).use { store ->
            complete(store, "evt-1")
            complete(store, "evt-2")
            store.claim(key("evt-1"), LEASE_DURATION) shouldBeSameInstanceAs AwsModulithClaimResult.Completed

            store.claim(key("evt-3"), LEASE_DURATION).shouldBeInstanceOf<AwsModulithClaimResult.Acquired>()
            store.claim(key("evt-2"), LEASE_DURATION).shouldBeInstanceOf<AwsModulithClaimResult.Acquired>()
        }

        store(retention = 1.minutes(), clock = clock).use { store ->
            complete(store, "ttl-event")
            clock.advance(1.minutes() + 1.nanos())

            store.claim(key("ttl-event"), LEASE_DURATION)
                .shouldBeInstanceOf<AwsModulithClaimResult.Acquired>()
        }
    }

    @Test
    fun `close clears owned state and rejects new claims`() = runTest {
        val store = store()
        store.claim(key("evt-1"), LEASE_DURATION)

        store.close()

        assertFailsWith<AwsModulithClaimMutationException> {
            store.claim(key("evt-2"), LEASE_DURATION)
        }
        store.close()
    }

    @Test
    fun `mutable properties are snapshotted at construction`() = runTest {
        val properties = AwsModulithEventsProperties.Idempotency(maxEntries = 3, maxInProgress = 1)
        val store = InMemoryAwsModulithEventIdempotencyStore(properties)
        properties.maxInProgress = 2

        store.use {
            it.claim(key("evt-1"), LEASE_DURATION)

            assertFailsWith<AwsModulithClaimCapacityException> {
                it.claim(key("evt-2"), LEASE_DURATION)
            }
        }
    }

    private suspend fun complete(store: AwsModulithEventIdempotencyStore, eventId: String) {
        val token = store.claim(key(eventId), LEASE_DURATION).acquiredToken()
        store.complete(token) shouldBeEqualTo AwsModulithStoreMutation.APPLIED
    }

    private fun store(
        maxEntries: Int = 10,
        maxInProgress: Int = maxEntries,
        maxKeyBytes: Int = 1_024,
        retention: Duration = 1.hours(),
        clock: Clock = MutableClock(),
    ): InMemoryAwsModulithEventIdempotencyStore {
        val sequence = AtomicInteger()
        return InMemoryAwsModulithEventIdempotencyStore(
            properties = AwsModulithEventsProperties.Idempotency(
                maxEntries = maxEntries,
                maxInProgress = maxInProgress,
                maxKeyBytes = maxKeyBytes,
                retention = retention,
                leaseDuration = LEASE_DURATION,
            ),
            clock = clock,
            ownerIdSupplier = { "owner-${sequence.incrementAndGet()}" },
        )
    }
}

private fun AwsModulithClaimResult.acquiredToken(): AwsModulithClaimToken {
    return this.shouldBeInstanceOf<AwsModulithClaimResult.Acquired>().token
}

private class MutableClock(
    private var current: Instant = Instant.parse("2026-08-26T00:00:00Z"),
    private val zone: ZoneId = ZoneOffset.UTC,
): Clock() {
    override fun getZone(): ZoneId = zone

    override fun withZone(zone: ZoneId): Clock = MutableClock(current, zone)

    override fun instant(): Instant = current

    fun advance(duration: Duration) {
        current = current.plus(duration)
    }
}
