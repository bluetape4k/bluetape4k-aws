package io.bluetape4k.aws.kinesis

import java.time.Clock
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 단위 테스트와 Floci contract 검증용 process-local lease 저장소입니다.
 *
 * production durable store를 대신하지 않습니다. lease 상태의 조건부 연산만 원자화하며,
 * checkpoint adapter는 별도로 같은 consistency domain을 구현해야 합니다.
 */
class InMemoryKinesisLeaseStore(
    private val clock: Clock = Clock.systemUTC(),
) : KinesisLeaseStore {

    private data class Entry(val lease: KinesisLease, val expiresAt: Instant)

    private val entries = ConcurrentHashMap<KinesisShardKey, Entry>()
    private val counters = ConcurrentHashMap<KinesisShardKey, Long>()
    private val mutex = Mutex()

    override suspend fun acquire(key: KinesisShardKey, ownerId: String, leaseDuration: Duration): KinesisLease? =
        mutex.withLock {
            ownerId.requireKinesisIdentifier("ownerId")
            require(leaseDuration.isPositive()) { "leaseDuration must be positive" }
            val now = clock.instant()
            val current = entries[key]
            if (current != null && current.expiresAt.isAfter(now)) {
                if (current.lease.ownerId != ownerId) return@withLock null
                entries[key] = current.copy(expiresAt = now.plusNanos(leaseDuration.inWholeNanoseconds))
                return@withLock current.lease
            }

            val previousCounter = counters[key] ?: current?.lease?.leaseCounter ?: 0L
            require(previousCounter < Long.MAX_VALUE) {
                "leaseCounter overflow for key=${key.canonicalValue}"
            }
            val counter = previousCounter + 1L
            val lease = KinesisLease(key, ownerId, counter)
            entries[key] = Entry(lease, now.plusNanos(leaseDuration.inWholeNanoseconds))
            counters[key] = counter
            lease
        }

    override suspend fun renew(lease: KinesisLease, leaseDuration: Duration): KinesisLease? =
        mutex.withLock {
            require(leaseDuration.isPositive()) { "leaseDuration must be positive" }
            val current = entries[lease.key]
            val now = clock.instant()
            if (current == null || current.lease != lease || !current.expiresAt.isAfter(now)) {
                return@withLock null
            }
            entries[lease.key] = Entry(lease, now.plusNanos(leaseDuration.inWholeNanoseconds))
            lease
        }

    override suspend fun release(lease: KinesisLease) {
        mutex.withLock {
            if (entries[lease.key]?.lease == lease) entries.remove(lease.key)
        }
    }
}
