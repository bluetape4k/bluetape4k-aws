package io.bluetape4k.aws.kotlin.kinesis

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration

/**
 * 단위 테스트와 Floci 계약 검증용 thread-safe lease store입니다.
 *
 * 시간은 process monotonic clock을 사용합니다. 프로세스 재시작 후 상태를 보존하지
 * 않으므로 durable lease store 대체품이 아닙니다.
 */
class InMemoryKinesisLeaseStore : KinesisLeaseStore {

    private data class Entry(val lease: KinesisLease, val expiresAtNanos: Long)

    private val mutex = Mutex()
    private val leases = ConcurrentHashMap<KinesisShardKey, Entry>()
    private val counters = ConcurrentHashMap<KinesisShardKey, Long>()

    override suspend fun acquire(
        key: KinesisShardKey,
        ownerId: String,
        leaseDuration: Duration,
    ): KinesisLease? = mutex.withLock {
        ownerId.validateIdentifier("ownerId", KinesisShardKey.MAX_IDENTIFIER_LENGTH)
        require(leaseDuration.isPositive()) { "leaseDuration must be positive, but was $leaseDuration" }
        val now = System.nanoTime()
        val current = leases[key]
        when {
            current == null || current.expiresAtNanos <= now -> {
                val nextCounter = (counters[key] ?: current?.lease?.leaseCounter)?.let { counter ->
                    require(counter < Long.MAX_VALUE) { "leaseCounter overflow for key=${key.canonicalValue}" }
                    counter + 1
                } ?: 1L
                val lease = KinesisLease(key, ownerId, nextCounter)
                leases[key] = Entry(lease, expiry(now, leaseDuration))
                counters[key] = nextCounter
                lease
            }

            current.lease.ownerId == ownerId -> {
                leases[key] = current.copy(expiresAtNanos = expiry(now, leaseDuration))
                current.lease
            }

            else -> null
        }
    }

    override suspend fun renew(lease: KinesisLease, leaseDuration: Duration): KinesisLease? = mutex.withLock {
        require(leaseDuration.isPositive()) { "leaseDuration must be positive, but was $leaseDuration" }
        val current = leases[lease.key]
        val now = System.nanoTime()
        if (current == null || current.expiresAtNanos <= now || current.lease != lease) {
            null
        } else {
            leases[lease.key] = current.copy(expiresAtNanos = expiry(now, leaseDuration))
            lease
        }
    }

    override suspend fun release(lease: KinesisLease) = mutex.withLock {
        if (leases[lease.key]?.lease == lease) {
            leases.remove(lease.key)
        }
    }

    private fun expiry(now: Long, duration: Duration): Long {
        val nanos = duration.inWholeNanoseconds.coerceAtMost(Long.MAX_VALUE / 2)
        return if (Long.MAX_VALUE - now < nanos) Long.MAX_VALUE else now + nanos
    }
}
