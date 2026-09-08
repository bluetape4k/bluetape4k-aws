package io.bluetape4k.aws.spring.kms

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import org.junit.jupiter.api.Test
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class KmsDataKeyTest {

    @Test
    fun `close clears owned plaintext and preserves caller arrays`() {
        val original = byteArrayOf(1, 2, 3)
        val key = KmsDataKey("key", original, byteArrayOf(4, 5))
        val owned = KmsDataKey::class.java.getDeclaredField("plaintextBytes").apply {
            isAccessible = true
        }.get(key) as ByteArray
        val issued = key.plaintext

        (key as Any as AutoCloseable).close()
        (key as Any as AutoCloseable).close()

        owned.toList() shouldBeEqualTo listOf<Byte>(0, 0, 0)
        original.toList() shouldBeEqualTo listOf<Byte>(1, 2, 3)
        issued.toList() shouldBeEqualTo listOf<Byte>(1, 2, 3)
        key.encryptedDataKey.toList() shouldBeEqualTo listOf<Byte>(4, 5)
        assertFailsWith<IllegalStateException> { key.plaintext }
        issued.fill(0)
    }

    @Test
    fun `plaintext and internal copy are atomic with close`() {
        val expected = byteArrayOf(1, 2, 3, 4)
        val key = KmsDataKey("key", expected, byteArrayOf(5, 6))
        val start = CountDownLatch(1)
        val failures = ConcurrentLinkedQueue<Throwable>()
        val executor = Executors.newFixedThreadPool(2)
        val reader = executor.submit {
            try {
                start.await()
                repeat(300) { iteration ->
                    try {
                        val snapshot = if (iteration and 1 == 0) key else key.copy()
                        try {
                            if (!snapshot.plaintext.contentEquals(expected)) {
                                failures.add(AssertionError("partially cleared plaintext"))
                            }
                        } finally {
                            if (snapshot !== key) snapshot.close()
                        }
                    } catch (cause: IllegalStateException) {
                        if (cause.message != "KmsDataKey is closed.") failures.add(cause)
                    }
                }
            } catch (cause: Throwable) {
                failures.add(cause)
            }
        }
        val closer = executor.submit {
            start.await()
            repeat(300) { key.close() }
        }

        try {
            start.countDown()
            reader.get(5, TimeUnit.SECONDS)
            closer.get(5, TimeUnit.SECONDS)
            failures.peek()?.let { throw AssertionError("key copy/close race failed", it) }
        } finally {
            key.close()
            executor.shutdownNow()
        }
    }
}
