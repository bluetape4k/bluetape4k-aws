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

    /**
     * 캐시가 보유한 키와 독립적인 호출자 소유 snapshot을 반환합니다.
     * 반환된 값은 호출자가 사용 후 [KmsDataKey.close]해야 합니다.
     */
    fun get(key: KmsDataKeyCacheKey): KmsDataKey?

    /**
     * 입력 키의 소유권을 이전하지 않고 캐시가 독립 snapshot을 보유합니다.
     * 구현은 삽입 실패 시 해당 snapshot을 소거해야 합니다.
     */
    fun put(key: KmsDataKeyCacheKey, value: KmsDataKey)

    /** 캐시가 보유한 키를 제거하고 평문 자료를 소거합니다. */
    fun evict(key: KmsDataKeyCacheKey)

    /** 캐시가 보유한 모든 키를 제거하고 평문 자료를 소거합니다. */
    fun clear()
}

/**
 * 값을 저장하지 않는 데이터 키 캐시입니다.
 * 입력 키를 보유하거나 닫지 않으므로 반환된 키의 수명은 호출자가 관리합니다.
 */
object NoopDataKeyCache: DataKeyCache {
    override fun get(key: KmsDataKeyCacheKey): KmsDataKey? = null
    override fun put(key: KmsDataKeyCacheKey, value: KmsDataKey) = Unit
    override fun evict(key: KmsDataKeyCacheKey) = Unit
    override fun clear() = Unit
}

/**
 * TTL과 최대 크기 축출을 적용하는 인메모리 [DataKeyCache]입니다.
 * TTL은 접근 시 확인하는 lazy 정책이며 별도 백그라운드 소거 작업을 시작하지 않습니다.
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

    private val entries = LinkedHashMap<KmsDataKeyCacheKey, Entry>(maxSize, 0.75f, true)

    init {
        require(maxSize > 0) { "maxSize must be greater than 0." }
        require(!ttl.isNegative && !ttl.isZero) { "ttl must be greater than zero." }
    }

    override fun get(key: KmsDataKeyCacheKey): KmsDataKey? {
        var expired: KmsDataKey? = null
        val result = lock.withLock {
            val entry = entries[key] ?: return@withLock null
            if (entry.expiresAt.isAfter(clock.instant())) {
                entry.value.copy()
            } else {
                entries.remove(key)
                expired = entry.value
                null
            }
        }
        expired?.close()
        return result
    }

    override fun put(key: KmsDataKeyCacheKey, value: KmsDataKey) {
        val snapshot = value.copy()
        var published = false
        try {
            val retired = lock.withLock {
                // 만료 시각 계산을 snapshot publish보다 먼저 수행해 clock 실패 시 새 entry를 남기지 않습니다.
                val expiresAt = clock.instant().plus(ttl)
                val replaced = entries.put(key, Entry(snapshot, expiresAt))?.value
                // 이 지점부터 snapshot은 cache 소유이므로 이후 예외가 발생해도 닫지 않습니다.
                published = true
                val evicted = if (entries.size > maxSize) {
                    val iterator = entries.entries.iterator()
                    val eldest = iterator.next().value.value
                    iterator.remove()
                    eldest
                } else {
                    null
                }
                listOfNotNull(replaced, evicted)
            }
            retired.forEach(KmsDataKey::close)
        } finally {
            if (!published) {
                snapshot.close()
            }
        }
    }

    override fun evict(key: KmsDataKeyCacheKey) {
        val retired = lock.withLock { entries.remove(key)?.value }
        retired?.close()
    }

    override fun clear() {
        val retired = lock.withLock {
            val values = entries.values.map(Entry::value)
            entries.clear()
            values
        }
        retired.forEach(KmsDataKey::close)
    }
}
