package io.bluetape4k.aws.spring.modulith

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeSameInstanceAs
import io.bluetape4k.assertions.shouldBeTrue
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicInteger

class InMemoryAwsModulithEventIdempotencyStoreTest : AwsModulithEventIdempotencyStoreContract() {

    override fun createStore(): AwsModulithEventIdempotencyStore = InMemoryAwsModulithEventIdempotencyStore()

    @Test
    fun `expired lease is taken over with a higher fencing generation`() = runTest {
        val clock = MutableClock()
        store(clock = clock).use { store ->
            val first = store.claim(key("evt-1"), LEASE_DURATION).acquiredToken()
            clock.advance(LEASE_DURATION.plusNanos(1))

            val takeover = store.claim(key("evt-1"), LEASE_DURATION).acquiredToken()

            takeover.generation shouldBeEqualTo first.generation + 1
            takeover.leaseUntil.isAfter(first.leaseUntil).shouldBeTrue()
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
            (store.claim(key("evt-1"), LEASE_DURATION) is AwsModulithClaimResult.InProgress).shouldBeTrue()
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

            (store.claim(key("evt-2"), LEASE_DURATION) is AwsModulithClaimResult.Acquired).shouldBeTrue()
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
        store(maxEntries = 2, maxInProgress = 2, retention = Duration.ofMinutes(1), clock = clock).use { store ->
            complete(store, "evt-1")
            complete(store, "evt-2")
            store.claim(key("evt-1"), LEASE_DURATION) shouldBeSameInstanceAs AwsModulithClaimResult.Completed

            (store.claim(key("evt-3"), LEASE_DURATION) is AwsModulithClaimResult.Acquired).shouldBeTrue()
            (store.claim(key("evt-2"), LEASE_DURATION) is AwsModulithClaimResult.Acquired).shouldBeTrue()
        }

        store(retention = Duration.ofMinutes(1), clock = clock).use { store ->
            complete(store, "ttl-event")
            clock.advance(Duration.ofMinutes(1).plusNanos(1))

            (store.claim(key("ttl-event"), LEASE_DURATION) is AwsModulithClaimResult.Acquired).shouldBeTrue()
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
        retention: Duration = Duration.ofHours(1),
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

    private companion object {
        val LEASE_DURATION: Duration = Duration.ofMinutes(2)

        fun key(eventId: String): AwsModulithEventKey = AwsModulithEventKey("order.placed", eventId)
    }
}

private fun AwsModulithClaimResult.acquiredToken(): AwsModulithClaimToken {
    require(this is AwsModulithClaimResult.Acquired)
    return token
}

private class MutableClock(
    private var current: Instant = Instant.parse("2026-08-26T00:00:00Z"),
    private val zone: ZoneId = ZoneOffset.UTC,
) : Clock() {
    override fun getZone(): ZoneId = zone

    override fun withZone(zone: ZoneId): Clock = MutableClock(current, zone)

    override fun instant(): Instant = current

    fun advance(duration: Duration) {
        current = current.plus(duration)
    }
}
