package io.bluetape4k.aws.spring.kms

import software.amazon.awssdk.services.kms.model.DataKeySpec
import java.io.Serializable
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.LinkedHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * KMS 데이터 키의 캐시 키입니다.
 *
 * AWS KMS는 생성 및 복호화한 데이터 키에 암호화 컨텍스트를 암호학적으로 결합하므로
 * 암호화 컨텍스트가 키에 포함됩니다.
 */
data class KmsDataKeyCacheKey(
    val keyId: String,
    val keySpec: DataKeySpec?,
    val numberOfBytes: Int?,
    val encryptionContext: Map<String, String>,
): Serializable {
    init {
        require(keyId.isNotBlank()) { "keyId must not be blank." }
        require((keySpec == null) xor (numberOfBytes == null)) {
            "Exactly one of keySpec or numberOfBytes must be configured."
        }
        numberOfBytes?.let { require(it > 0) { "numberOfBytes must be greater than 0." } }
    }

    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

/**
 * 크기가 제한된 평문 데이터 키 캐시입니다.
 *
 * 구현은 값을 민감한 인메모리 키 자료로 취급하고 무제한 보관을 피해야 합니다.
 */
interface DataKeyCache {

    fun get(key: KmsDataKeyCacheKey): KmsDataKey?

    fun put(key: KmsDataKeyCacheKey, value: KmsDataKey)

    fun evict(key: KmsDataKeyCacheKey)

    fun clear()
}

/**
 * 값을 저장하지 않는 데이터 키 캐시입니다.
 */
object NoopDataKeyCache: DataKeyCache {
    override fun get(key: KmsDataKeyCacheKey): KmsDataKey? = null
    override fun put(key: KmsDataKeyCacheKey, value: KmsDataKey) = Unit
    override fun evict(key: KmsDataKeyCacheKey) = Unit
    override fun clear() = Unit
}

/**
 * TTL과 최대 크기 축출을 적용하는 인메모리 [DataKeyCache]입니다.
 */
class InMemoryDataKeyCache(
    private val maxSize: Int,
    private val ttl: Duration,
    private val clock: Clock = Clock.systemUTC(),
): DataKeyCache {

    private data class Entry(
        val value: KmsDataKey,
        val expiresAt: Instant,
    ): Serializable {
        companion object {
            private const val serialVersionUID: Long = 1L
        }
    }

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
