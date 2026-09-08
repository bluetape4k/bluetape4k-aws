package io.bluetape4k.aws.spring.kms

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldNotBeNull
import org.junit.jupiter.api.Test
import software.amazon.awssdk.services.kms.model.DataKeySpec
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

class InMemoryDataKeyCacheTest {

    @Test
    fun `returns cached value before ttl expires`() {
        val clock = MutableClock(Instant.parse("2026-05-13T00:00:00Z"))
        val cache = InMemoryDataKeyCache(maxSize = 2, ttl = Duration.ofMinutes(5), clock = clock)
        val key = cacheKey("key-1")
        val dataKey = dataKey("key-1")

        cache.put(key, dataKey)

        val first = cache.get(key).shouldNotBeNull()
        val second = cache.get(key).shouldNotBeNull()
        try {
            (first === dataKey).shouldBeFalse()
            (first === second).shouldBeFalse()
            first.plaintext.toList() shouldBeEqualTo dataKey.plaintext.toList()
            first.encryptedDataKey.toList() shouldBeEqualTo dataKey.encryptedDataKey.toList()
        } finally {
            first.close()
            second.close()
            dataKey.close()
            cache.clear()
        }
    }

    @Test
    fun `removes cached value after ttl expires`() {
        val clock = MutableClock(Instant.parse("2026-05-13T00:00:00Z"))
        val cache = InMemoryDataKeyCache(maxSize = 2, ttl = Duration.ofMinutes(5), clock = clock)
        val key = cacheKey("key-1")

        val dataKey = dataKey("key-1")
        cache.put(key, dataKey)
        val retained = retainedPlaintext(cache, key)
        clock.currentInstant = clock.currentInstant.plus(Duration.ofMinutes(6))

        try {
            cache.get(key).shouldBeNull()
            retained.toList() shouldBeEqualTo List(3) { 0.toByte() }
        } finally {
            dataKey.close()
            cache.clear()
        }
    }

    @Test
    fun `clock failure does not publish a new snapshot`() {
        val cache = InMemoryDataKeyCache(
            maxSize = 1,
            ttl = Duration.ofMinutes(5),
            clock = FailingClock,
        )
        val key = cacheKey("key-1")
        val source = dataKey("key-1")

        try {
            assertFailsWith<IllegalStateException> { cache.put(key, source) }
            cache.get(key).shouldBeNull()
            source.plaintext.toList() shouldBeEqualTo listOf<Byte>(1, 2, 3)
        } finally {
            source.close()
            cache.clear()
        }
    }

    @Test
    fun `eviction clears cache-owned plaintext but preserves issued snapshot`() {
        val cache = InMemoryDataKeyCache(maxSize = 1, ttl = Duration.ofMinutes(5))
        val key = cacheKey("key-1")
        val dataKey = dataKey("key-1")
        cache.put(key, dataKey)
        val issued = cache.get(key).shouldNotBeNull()
        val retained = retainedPlaintext(cache, key)

        try {
            cache.evict(key)

            retained.toList() shouldBeEqualTo List(3) { 0.toByte() }
            issued.plaintext.toList() shouldBeEqualTo listOf<Byte>(1, 2, 3)
        } finally {
            issued.close()
            dataKey.close()
            cache.clear()
        }
    }

    @Test
    fun `replacement and clear erase retired cache-owned plaintext`() {
        val cache = InMemoryDataKeyCache(maxSize = 2, ttl = Duration.ofMinutes(5))
        val key = cacheKey("key-1")
        val first = dataKey("key-1")
        val second = KmsDataKey("key-1", byteArrayOf(9, 8, 7), byteArrayOf(6, 5, 4))
        cache.put(key, first)
        val firstRetained = retainedPlaintext(cache, key)

        cache.put(key, second)
        val secondRetained = retainedPlaintext(cache, key)
        firstRetained.toList() shouldBeEqualTo List(3) { 0.toByte() }

        try {
            cache.clear()
            secondRetained.toList() shouldBeEqualTo List(3) { 0.toByte() }
        } finally {
            first.close()
            second.close()
            cache.clear()
        }
    }

    @Test
    fun `evicts eldest value when max size is exceeded`() {
        val clock = MutableClock(Instant.parse("2026-05-13T00:00:00Z"))
        val cache = InMemoryDataKeyCache(maxSize = 1, ttl = Duration.ofMinutes(5), clock = clock)
        val first = cacheKey("key-1")
        val second = cacheKey("key-2")

        val firstDataKey = dataKey("key-1")
        val secondDataKey = dataKey("key-2")
        cache.put(first, firstDataKey)
        cache.put(second, secondDataKey)

        try {
            cache.get(first).shouldBeNull()
            cache.get(second).shouldNotBeNull().close()
        } finally {
            firstDataKey.close()
            secondDataKey.close()
            cache.clear()
        }
    }

    @Test
    fun `concurrent cache access keeps plaintext intact`() {
        val cache = InMemoryDataKeyCache(maxSize = 1, ttl = Duration.ofMinutes(5))
        val executor = Executors.newFixedThreadPool(4)
        val fixture = CacheStressFixture(
            cache = cache,
            firstKey = cacheKey("key-1"),
            secondKey = cacheKey("key-2"),
            expectedPlaintexts = listOf(
                byteArrayOf(1, 2, 3),
                byteArrayOf(9, 8, 7),
            ),
            start = CountDownLatch(1),
            failures = ConcurrentLinkedQueue(),
        )
        val futures = buildList<Future<*>> {
            repeat(3) {
                add(submitReader(executor, fixture))
            }
            add(submitWriter(executor, fixture))
        }

        try {
            fixture.start.countDown()
            futures.forEach { it.get(5, TimeUnit.SECONDS) }
            fixture.failures.peek()?.let { throw AssertionError("concurrent cache operation failed", it) }
        } finally {
            executor.shutdownNow()
            cache.clear()
        }
    }

    private fun submitReader(executor: ExecutorService, fixture: CacheStressFixture): Future<*> =
        executor.submit {
            try {
                fixture.start.await()
                repeat(300) { iteration ->
                    val key = if (iteration and 1 == 0) fixture.firstKey else fixture.secondKey
                    fixture.cache.get(key)?.let { snapshot ->
                        try {
                            val plaintext = snapshot.plaintext
                            require(fixture.expectedPlaintexts.any { plaintext.contentEquals(it) }) {
                                "partially cleared plaintext: ${plaintext.toList()}"
                            }
                        } finally {
                            snapshot.close()
                        }
                    }
                }
            } catch (cause: Throwable) {
                fixture.failures.add(cause)
            }
        }

    private fun submitWriter(executor: ExecutorService, fixture: CacheStressFixture): Future<*> =
        executor.submit {
            try {
                fixture.start.await()
                repeat(300) { iteration ->
                    val key = if (iteration and 1 == 0) fixture.firstKey else fixture.secondKey
                    val plaintext = fixture.expectedPlaintexts[iteration and 1]
                    val source = KmsDataKey(
                        keyId = key.keyId,
                        plaintext = plaintext,
                        encryptedDataKey = byteArrayOf(4, 5, 6),
                    )
                    try {
                        fixture.cache.put(key, source)
                    } finally {
                        source.close()
                    }
                    if (iteration % 3 == 0) fixture.cache.evict(fixture.firstKey)
                    if (iteration % 5 == 0) fixture.cache.clear()
                }
            } catch (cause: Throwable) {
                fixture.failures.add(cause)
            }
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

    private fun retainedPlaintext(cache: InMemoryDataKeyCache, key: KmsDataKeyCacheKey): ByteArray {
        val entries = cache.javaClass.getDeclaredField("entries").apply {
            isAccessible = true
        }.get(cache) as Map<*, *>
        val entry = requireNotNull(entries[key])
        val value = entry.javaClass.getDeclaredField("value").apply {
            isAccessible = true
        }.get(entry) as KmsDataKey
        return KmsDataKey::class.java.getDeclaredField("plaintextBytes").apply {
            isAccessible = true
        }.get(value) as ByteArray
    }

    private data class CacheStressFixture(
        val cache: InMemoryDataKeyCache,
        val firstKey: KmsDataKeyCacheKey,
        val secondKey: KmsDataKeyCacheKey,
        val expectedPlaintexts: List<ByteArray>,
        val start: CountDownLatch,
        val failures: ConcurrentLinkedQueue<Throwable>,
    )

    private class MutableClock(
        var currentInstant: Instant,
    ): Clock() {
        override fun getZone(): ZoneId = ZoneId.of("UTC")
        override fun withZone(zone: ZoneId): Clock = this
        override fun instant(): Instant = currentInstant
    }

    private object FailingClock: Clock() {
        override fun getZone(): ZoneId = ZoneId.of("UTC")
        override fun withZone(zone: ZoneId): Clock = this
        override fun instant(): Instant = throw IllegalStateException("synthetic clock failure")
    }
}
