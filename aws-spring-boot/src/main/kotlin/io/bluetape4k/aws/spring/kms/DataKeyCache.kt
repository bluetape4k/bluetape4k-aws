package io.bluetape4k.aws.spring.kms

import software.amazon.awssdk.services.kms.model.DataKeySpec
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.LinkedHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Cache key for KMS data keys.
 *
 * The encryption context is part of the key because AWS KMS cryptographically binds
 * the context to generated and decrypted data keys.
 */
data class KmsDataKeyCacheKey(
    val keyId: String,
    val keySpec: DataKeySpec?,
    val numberOfBytes: Int?,
    val encryptionContext: Map<String, String>,
) {
    init {
        require(keyId.isNotBlank()) { "keyId must not be blank." }
        require((keySpec == null) xor (numberOfBytes == null)) {
            "Exactly one of keySpec or numberOfBytes must be configured."
        }
        numberOfBytes?.let { require(it > 0) { "numberOfBytes must be greater than 0." } }
    }
}

/**
 * Bounded plaintext data key cache.
 *
 * Implementations must treat values as sensitive in-memory key material and avoid
 * unbounded retention.
 */
interface DataKeyCache {

    fun get(key: KmsDataKeyCacheKey): KmsDataKey?

    fun put(key: KmsDataKeyCacheKey, value: KmsDataKey)

    fun evict(key: KmsDataKeyCacheKey)

    fun clear()
}

/**
 * Data key cache that never stores values.
 */
object NoopDataKeyCache: DataKeyCache {
    override fun get(key: KmsDataKeyCacheKey): KmsDataKey? = null
    override fun put(key: KmsDataKeyCacheKey, value: KmsDataKey) = Unit
    override fun evict(key: KmsDataKeyCacheKey) = Unit
    override fun clear() = Unit
}

/**
 * In-memory [DataKeyCache] with TTL and maximum-size eviction.
 */
class InMemoryDataKeyCache(
    private val maxSize: Int,
    private val ttl: Duration,
    private val clock: Clock = Clock.systemUTC(),
): DataKeyCache {

    private data class Entry(
        val value: KmsDataKey,
        val expiresAt: Instant,
    )

    private val lock = ReentrantLock()

    private val entries = object: LinkedHashMap<KmsDataKeyCacheKey, Entry>(maxSize, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<KmsDataKeyCacheKey, Entry>?): Boolean =
            size > maxSize
    }

    init {
        require(maxSize > 0) { "maxSize must be greater than 0." }
        require(!ttl.isNegative && !ttl.isZero) { "ttl must be greater than zero." }
    }

    override fun get(key: KmsDataKeyCacheKey): KmsDataKey? =
        lock.withLock {
            val entry = entries[key] ?: return null
            if (entry.expiresAt.isAfter(clock.instant())) {
                entry.value
            } else {
                entries.remove(key)
                null
            }
        }

    override fun put(key: KmsDataKeyCacheKey, value: KmsDataKey) {
        lock.withLock {
            entries[key] = Entry(value, clock.instant().plus(ttl))
        }
    }

    override fun evict(key: KmsDataKeyCacheKey) {
        lock.withLock {
            entries.remove(key)
        }
    }

    override fun clear() {
        lock.withLock {
            entries.clear()
        }
    }
}
