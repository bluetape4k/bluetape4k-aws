package io.bluetape4k.aws.spring.kms

import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldBeSameInstanceAs
import io.bluetape4k.assertions.shouldNotBeNull
import org.junit.jupiter.api.Test
import software.amazon.awssdk.services.kms.model.DataKeySpec
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

class InMemoryDataKeyCacheTest {

    @Test
    fun `returns cached value before ttl expires`() {
        val clock = MutableClock(Instant.parse("2026-05-13T00:00:00Z"))
        val cache = InMemoryDataKeyCache(maxSize = 2, ttl = Duration.ofMinutes(5), clock = clock)
        val key = cacheKey("key-1")
        val dataKey = dataKey("key-1")

        cache.put(key, dataKey)

        cache.get(key) shouldBeSameInstanceAs dataKey
    }

    @Test
    fun `removes cached value after ttl expires`() {
        val clock = MutableClock(Instant.parse("2026-05-13T00:00:00Z"))
        val cache = InMemoryDataKeyCache(maxSize = 2, ttl = Duration.ofMinutes(5), clock = clock)
        val key = cacheKey("key-1")

        cache.put(key, dataKey("key-1"))
        clock.currentInstant = clock.currentInstant.plus(Duration.ofMinutes(6))

        cache.get(key).shouldBeNull()
    }

    @Test
    fun `evicts eldest value when max size is exceeded`() {
        val clock = MutableClock(Instant.parse("2026-05-13T00:00:00Z"))
        val cache = InMemoryDataKeyCache(maxSize = 1, ttl = Duration.ofMinutes(5), clock = clock)
        val first = cacheKey("key-1")
        val second = cacheKey("key-2")

        cache.put(first, dataKey("key-1"))
        cache.put(second, dataKey("key-2"))

        cache.get(first).shouldBeNull()
        cache.get(second).shouldNotBeNull()
    }

    private fun cacheKey(keyId: String): KmsDataKeyCacheKey =
        KmsDataKeyCacheKey(
            keyId = keyId,
            keySpec = DataKeySpec.AES_256,
            numberOfBytes = null,
            encryptionContext = emptyMap(),
        )

    private fun dataKey(keyId: String): KmsDataKey =
        KmsDataKey(
            keyId = keyId,
            plaintext = byteArrayOf(1, 2, 3),
            encryptedDataKey = byteArrayOf(4, 5, 6),
        )

    private class MutableClock(
        var currentInstant: Instant,
    ): Clock() {
        override fun getZone(): ZoneId = ZoneId.of("UTC")
        override fun withZone(zone: ZoneId): Clock = this
        override fun instant(): Instant = currentInstant
    }
}
