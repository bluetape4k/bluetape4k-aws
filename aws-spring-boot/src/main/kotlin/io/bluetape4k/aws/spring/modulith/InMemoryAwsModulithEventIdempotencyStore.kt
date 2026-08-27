package io.bluetape4k.aws.spring.modulith

import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.DateTimeException
import java.time.Duration
import java.time.Instant
import java.util.LinkedHashMap
import java.util.UUID
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * 단일 process에서 bounded duplicate suppression을 제공하는 기본 idempotency store입니다.
 *
 * active claim은 자동 축출하지 않으며 completed entry만 retention과 LRU 정책으로 제거합니다.
 * application 재시작이나 multi-instance 원자성이 필요하면 durable store bean을 제공해야 합니다.
 */
@Suppress("TooManyFunctions")
class InMemoryAwsModulithEventIdempotencyStore internal constructor(
    properties: AwsModulithEventsProperties.Idempotency,
    private val clock: Clock,
    private val ownerIdSupplier: () -> String,
) : AwsModulithEventIdempotencyStore, AutoCloseable {

    constructor(properties: AwsModulithEventsProperties.Idempotency = AwsModulithEventsProperties.Idempotency()) :
        this(properties, Clock.systemUTC(), { UUID.randomUUID().toString() })

    private val limits = properties.copy()
    private val lock = ReentrantLock()
    private val entries = LinkedHashMap<AwsModulithEventKey, StoreEntry>(16, 0.75F, true)
    private var inProgressCount: Int = 0
    private var retainedKeyBytes: Long = 0
    private var generationSequence: Long = 0
    private var closed: Boolean = false

    override suspend fun claim(
        key: AwsModulithEventKey,
        leaseDuration: Duration,
    ): AwsModulithClaimResult {
        currentCoroutineContext().ensureActive()
        requireLeaseDuration(leaseDuration)
        return lock.withLock {
            ensureOpen()
            val now = clock.instant()
            removeExpiredCompleted(now)
            when (val entry = entries[key]) {
                is StoreEntry.InProgress -> claimInProgress(entry, leaseDuration, now)
                is StoreEntry.Completed -> AwsModulithClaimResult.Completed
                null -> acquireNew(key, leaseDuration, now)
            }
        }
    }

    override suspend fun renew(
        token: AwsModulithClaimToken,
        leaseDuration: Duration,
    ): AwsModulithClaimToken {
        currentCoroutineContext().ensureActive()
        requireLeaseDuration(leaseDuration)
        return lock.withLock {
            ensureOpen()
            val now = clock.instant()
            val entry = entries[token.key]
            if (entry !is StoreEntry.InProgress || !entry.matches(token) || !entry.token.leaseUntil.isActiveAt(now)) {
                throw AwsModulithStaleClaimException()
            }
            val renewed = entry.token.copy(leaseUntil = leaseDeadline(now, leaseDuration))
            entries[token.key] = StoreEntry.InProgress(renewed)
            renewed
        }
    }

    override suspend fun complete(token: AwsModulithClaimToken): AwsModulithStoreMutation {
        currentCoroutineContext().ensureActive()
        return lock.withLock {
            ensureOpen()
            val now = clock.instant()
            when (val entry = entries[token.key]) {
                is StoreEntry.InProgress -> completeInProgress(entry, token, now)
                is StoreEntry.Completed -> if (entry.matches(token)) {
                    AwsModulithStoreMutation.ALREADY_APPLIED
                } else {
                    AwsModulithStoreMutation.STALE
                }

                null -> AwsModulithStoreMutation.NOT_FOUND
            }
        }
    }

    override suspend fun release(token: AwsModulithClaimToken): AwsModulithStoreMutation {
        currentCoroutineContext().ensureActive()
        return lock.withLock {
            ensureOpen()
            val now = clock.instant()
            when (val entry = entries[token.key]) {
                is StoreEntry.InProgress -> releaseInProgress(entry, token, now)
                is StoreEntry.Completed -> AwsModulithStoreMutation.STALE
                null -> AwsModulithStoreMutation.NOT_FOUND
            }
        }
    }

    override suspend fun recoverExpired(now: Instant): Int {
        currentCoroutineContext().ensureActive()
        return lock.withLock {
            ensureOpen()
            val recoveredKeys = entries.entries
                .filter { (_, entry) -> entry is StoreEntry.InProgress && entry.token.leaseUntil.isBefore(now) }
                .map { it.key }
            recoveredKeys.forEach(::removeEntry)
            inProgressCount -= recoveredKeys.size
            recoveredKeys.size
        }
    }

    internal fun metrics(): InMemoryAwsModulithEventIdempotencyStoreMetrics = lock.withLock {
        InMemoryAwsModulithEventIdempotencyStoreMetrics(
            entryCount = entries.size,
            inProgressCount = inProgressCount,
            retainedKeyBytes = retainedKeyBytes,
            closed = closed,
        )
    }

    override fun close() {
        lock.withLock {
            if (closed) return
            closed = true
            entries.clear()
            inProgressCount = 0
            retainedKeyBytes = 0
        }
    }

    private fun claimInProgress(
        entry: StoreEntry.InProgress,
        leaseDuration: Duration,
        now: Instant,
    ): AwsModulithClaimResult {
        if (entry.token.leaseUntil.isActiveAt(now)) {
            return AwsModulithClaimResult.InProgress(entry.token.leaseUntil)
        }
        val takeover = newToken(entry.token.key, entry.token.generation, leaseDuration, now)
        entries[entry.token.key] = StoreEntry.InProgress(takeover)
        return AwsModulithClaimResult.Acquired(takeover)
    }

    private fun acquireNew(
        key: AwsModulithEventKey,
        leaseDuration: Duration,
        now: Instant,
    ): AwsModulithClaimResult {
        ensureInProgressCapacity()
        val keyBytes = key.utf8Bytes()
        ensureEntryCapacity(keyBytes)
        val token = newToken(key, 0, leaseDuration, now)
        entries[key] = StoreEntry.InProgress(token)
        inProgressCount++
        retainedKeyBytes += keyBytes
        return AwsModulithClaimResult.Acquired(token)
    }

    private fun completeInProgress(
        entry: StoreEntry.InProgress,
        token: AwsModulithClaimToken,
        now: Instant,
    ): AwsModulithStoreMutation {
        if (!entry.matches(token) || !entry.token.leaseUntil.isActiveAt(now)) {
            return AwsModulithStoreMutation.STALE
        }
        entries[token.key] = StoreEntry.Completed(token, now)
        inProgressCount--
        return AwsModulithStoreMutation.APPLIED
    }

    private fun releaseInProgress(
        entry: StoreEntry.InProgress,
        token: AwsModulithClaimToken,
        now: Instant,
    ): AwsModulithStoreMutation {
        if (!entry.matches(token) || !entry.token.leaseUntil.isActiveAt(now)) {
            return AwsModulithStoreMutation.STALE
        }
        removeEntry(token.key)
        inProgressCount--
        return AwsModulithStoreMutation.APPLIED
    }

    private fun ensureEntryCapacity(newKeyBytes: Long) {
        if (newKeyBytes > limits.maxKeyBytes) {
            throw AwsModulithClaimCapacityException()
        }
        while (entries.size >= limits.maxEntries || retainedKeyBytes + newKeyBytes > limits.maxKeyBytes) {
            val completedKey = entries.entries.firstOrNull { it.value is StoreEntry.Completed }?.key
                ?: throw AwsModulithClaimCapacityException()
            removeEntry(completedKey)
        }
    }

    private fun ensureInProgressCapacity() {
        if (inProgressCount >= limits.maxInProgress) {
            throw AwsModulithClaimCapacityException()
        }
    }

    private fun removeExpiredCompleted(now: Instant) {
        val expired = entries.entries
            .filter { (_, entry) ->
                entry is StoreEntry.Completed && !entry.completedAt.plus(limits.retention).isAfter(now)
            }
            .map { it.key }
        expired.forEach(::removeEntry)
    }

    private fun removeEntry(key: AwsModulithEventKey) {
        if (entries.remove(key) != null) {
            retainedKeyBytes -= key.utf8Bytes()
        }
    }

    private fun newToken(
        key: AwsModulithEventKey,
        previousGeneration: Long,
        leaseDuration: Duration,
        now: Instant,
    ): AwsModulithClaimToken = AwsModulithClaimToken(
        key = key,
        ownerId = ownerIdSupplier(),
        generation = nextGeneration(previousGeneration),
        leaseUntil = leaseDeadline(now, leaseDuration),
    )

    private fun nextGeneration(previousGeneration: Long): Long {
        val latest = maxOf(generationSequence, previousGeneration)
        if (latest == Long.MAX_VALUE) {
            throw AwsModulithClaimMutationException()
        }
        return (latest + 1).also { generationSequence = it }
    }

    private fun leaseDeadline(now: Instant, leaseDuration: Duration): Instant = try {
        now.plus(leaseDuration)
    } catch (_: DateTimeException) {
        throw AwsModulithClaimMutationException()
    } catch (_: ArithmeticException) {
        throw AwsModulithClaimMutationException()
    }

    private fun requireLeaseDuration(leaseDuration: Duration) {
        if (leaseDuration.isZero || leaseDuration.isNegative) {
            throw AwsModulithClaimMutationException()
        }
    }

    private fun ensureOpen() {
        if (closed) {
            throw AwsModulithClaimMutationException()
        }
    }

    private sealed interface StoreEntry {
        data class InProgress(val token: AwsModulithClaimToken) : StoreEntry {
            fun matches(candidate: AwsModulithClaimToken): Boolean = token == candidate
        }

        data class Completed(
            val token: AwsModulithClaimToken,
            val completedAt: Instant,
        ) : StoreEntry {
            fun matches(candidate: AwsModulithClaimToken): Boolean = token == candidate
        }
    }
}

/** deterministic lifecycle tests에만 노출하는 bounded in-memory store 상태입니다. */
internal data class InMemoryAwsModulithEventIdempotencyStoreMetrics(
    val entryCount: Int,
    val inProgressCount: Int,
    val retainedKeyBytes: Long,
    val closed: Boolean,
)

private fun AwsModulithEventKey.utf8Bytes(): Long =
    type.toByteArray(StandardCharsets.UTF_8).size.toLong() +
        eventId.toByteArray(StandardCharsets.UTF_8).size.toLong()

private fun Instant.isActiveAt(now: Instant): Boolean = !isBefore(now)
