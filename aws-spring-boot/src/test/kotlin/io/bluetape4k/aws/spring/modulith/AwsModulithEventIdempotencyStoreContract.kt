package io.bluetape4k.aws.spring.modulith

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeSameInstanceAs
import io.bluetape4k.assertions.shouldBeTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicInteger

/** 모든 Modulith idempotency store 구현이 공유하는 lease/fencing 계약입니다. */
abstract class AwsModulithEventIdempotencyStoreContract {

    abstract fun createStore(): AwsModulithEventIdempotencyStore

    @Test
    fun `64 concurrent claims have exactly one winner`() = runTest {
        withStore { store ->
            val ready = AtomicInteger()
            val start = CompletableDeferred<Unit>()
            val outcomes = (1..64).map {
                async(Dispatchers.Default) {
                    ready.incrementAndGet()
                    start.await()
                    store.claim(EVENT_KEY, LEASE_DURATION)
                }
            }
            while (ready.get() != 64) {
                yield()
            }
            start.complete(Unit)

            val results = outcomes.awaitAll()

            results.count { it is AwsModulithClaimResult.Acquired } shouldBeEqualTo 1
            results.count { it is AwsModulithClaimResult.InProgress } shouldBeEqualTo 63
        }
    }

    @Test
    fun `completed claims become completed duplicates`() = runTest {
        withStore { store ->
            val token = store.claim(EVENT_KEY, LEASE_DURATION).acquiredToken()

            store.complete(token) shouldBeEqualTo AwsModulithStoreMutation.APPLIED
            store.claim(EVENT_KEY, LEASE_DURATION) shouldBeSameInstanceAs AwsModulithClaimResult.Completed
        }
    }

    @Test
    fun `in progress result exposes the active lease deadline`() = runTest {
        withStore { store ->
            val token = store.claim(EVENT_KEY, LEASE_DURATION).acquiredToken()

            val duplicate = store.claim(EVENT_KEY, LEASE_DURATION)

            (duplicate is AwsModulithClaimResult.InProgress).shouldBeTrue()
            (duplicate as AwsModulithClaimResult.InProgress).leaseUntil shouldBeEqualTo token.leaseUntil
        }
    }

    @Test
    fun `complete and release converge on repeat calls`() = runTest {
        withStore { store ->
            val completed = store.claim(EVENT_KEY, LEASE_DURATION).acquiredToken()
            store.complete(completed) shouldBeEqualTo AwsModulithStoreMutation.APPLIED
            store.complete(completed) shouldBeEqualTo AwsModulithStoreMutation.ALREADY_APPLIED

            val released = store.claim(OTHER_KEY, LEASE_DURATION).acquiredToken()
            store.release(released) shouldBeEqualTo AwsModulithStoreMutation.APPLIED
            store.release(released) shouldBeEqualTo AwsModulithStoreMutation.NOT_FOUND
            val reacquired = store.claim(OTHER_KEY, LEASE_DURATION).acquiredToken()
            (reacquired.generation > released.generation).shouldBeTrue()
        }
    }

    @Test
    fun `lease takeover increments generation and fences stale mutations`() = runTest {
        withStore { store ->
            val stale = store.claim(EVENT_KEY, LEASE_DURATION).acquiredToken()
            store.recoverExpired(stale.leaseUntil.plusNanos(1)) shouldBeEqualTo 1

            val current = store.claim(EVENT_KEY, LEASE_DURATION).acquiredToken()

            current.generation shouldBeEqualTo stale.generation + 1
            assertFailsWith<AwsModulithStaleClaimException> {
                store.renew(stale, LEASE_DURATION)
            }
            store.complete(stale) shouldBeEqualTo AwsModulithStoreMutation.STALE
            store.release(stale) shouldBeEqualTo AwsModulithStoreMutation.STALE
        }
    }

    @Test
    fun `renew keeps generation and returns a later deadline`() = runTest {
        withStore { store ->
            val token = store.claim(EVENT_KEY, LEASE_DURATION).acquiredToken()

            val renewed = store.renew(token, LEASE_DURATION.plusSeconds(1))

            renewed.key shouldBeEqualTo token.key
            renewed.ownerId shouldBeEqualTo token.ownerId
            renewed.generation shouldBeEqualTo token.generation
            renewed.leaseUntil.isAfter(token.leaseUntil).shouldBeTrue()

            assertFailsWith<AwsModulithStaleClaimException> {
                store.renew(token, LEASE_DURATION)
            }
            store.complete(token) shouldBeEqualTo AwsModulithStoreMutation.STALE
            store.release(token) shouldBeEqualTo AwsModulithStoreMutation.STALE
            store.complete(renewed) shouldBeEqualTo AwsModulithStoreMutation.APPLIED
        }
    }

    @Test
    fun `recover expired preserves active and completed entries`() = runTest {
        withStore { store ->
            val active = store.claim(EVENT_KEY, LEASE_DURATION).acquiredToken()
            val completed = store.claim(OTHER_KEY, LEASE_DURATION).acquiredToken()
            store.complete(completed)

            store.recoverExpired(active.leaseUntil.minusNanos(1)) shouldBeEqualTo 0
            store.recoverExpired(active.leaseUntil) shouldBeEqualTo 0
            (store.claim(EVENT_KEY, LEASE_DURATION) is AwsModulithClaimResult.InProgress).shouldBeTrue()
            store.claim(OTHER_KEY, LEASE_DURATION) shouldBeSameInstanceAs AwsModulithClaimResult.Completed
        }
    }

    @Test
    fun `pre-cancelled calls propagate cancellation without mutation`() = runTest {
        withStore { store ->
            val cancellation = CancellationException("cancelled-before-claim")
            val cancelledJob = Job(currentCoroutineContext()[Job])
            cancelledJob.cancel(cancellation)

            assertFailsWith<CancellationException> {
                withContext(cancelledJob) {
                    store.claim(EVENT_KEY, LEASE_DURATION)
                }
            }
            (store.claim(EVENT_KEY, LEASE_DURATION) is AwsModulithClaimResult.Acquired).shouldBeTrue()
        }
    }

    private suspend fun withStore(block: suspend (AwsModulithEventIdempotencyStore) -> Unit) {
        val store = createStore()
        try {
            block(store)
        } finally {
            (store as? AutoCloseable)?.close()
        }
    }

    private companion object {
        val EVENT_KEY = AwsModulithEventKey("order.placed", "evt-1")
        val OTHER_KEY = AwsModulithEventKey("order.placed", "evt-2")
        val LEASE_DURATION: Duration = Duration.ofMinutes(2)
    }
}

private fun AwsModulithClaimResult.acquiredToken(): AwsModulithClaimToken {
    require(this is AwsModulithClaimResult.Acquired)
    return token
}
