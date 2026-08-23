package io.bluetape4k.aws.spring.s3

import io.bluetape4k.assertions.shouldBeEqualTo
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.ObjectProvider
import org.springframework.context.support.GenericApplicationContext
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response
import software.amazon.awssdk.services.s3.model.S3Object
import software.amazon.awssdk.services.s3.paginators.ListObjectsV2Iterable
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class S3ResourcePatternResolverPerformanceTest {

    @Test
    @Suppress("LongMethod")
    fun `all pages are consumed independently by concurrent callers`() {
        val client = mockk<S3Client>(relaxed = true)
        val provider = mockk<ObjectProvider<S3Client>>()
        val pages = syntheticPages()
        val paginatorCalls = AtomicInteger()
        val iteratorStarted = CountDownLatch(CALLERS)
        val releaseIterators = CountDownLatch(1)
        every { provider.getObject() } returns client
        every { client.listObjectsV2Paginator(any<ListObjectsV2Request>()) } answers {
            paginatorCalls.incrementAndGet()
            mockk<ListObjectsV2Iterable> {
                every { iterator() } answers {
                    iteratorStarted.countDown()
                    check(iteratorStarted.await(30, TimeUnit.SECONDS)) { "paginator iterator start stalled" }
                    check(releaseIterators.await(120, TimeUnit.SECONDS)) { "paginator release stalled" }
                    pages.toMutableList().iterator()
                }
            }
        }

        val context = GenericApplicationContext().apply { refresh() }
        val executor = Executors.newFixedThreadPool(CALLERS)
        var primaryFailure: Throwable? = null
        var cleanupFailure: Throwable? = null
        try {
            val resolver = S3ResourcePatternResolver(context, provider)
            val futures = (0 until CALLERS).map {
                executor.submit(Callable {
                    resolver.getResources("s3://bucket/config/**/*.json")
                })
            }

            check(iteratorStarted.await(30, TimeUnit.SECONDS)) { "concurrent paginator calls did not overlap" }
            releaseIterators.countDown()
            val results = futures.map { it.get(120, TimeUnit.SECONDS) }
            results.size shouldBeEqualTo CALLERS
            results.forEach { resources ->
                resources.size shouldBeEqualTo PAGE_COUNT * KEYS_PER_PAGE
                (resources.first() as S3Resource).location.key shouldBeEqualTo "config/key-000000.json"
                (resources.last() as S3Resource).location.key shouldBeEqualTo "config/key-049999.json"
            }
            paginatorCalls.get() shouldBeEqualTo CALLERS
            println("issue-463-test-max-memory=${Runtime.getRuntime().maxMemory()}")
            if (System.getProperty("issue463.enforceHeap", "false").toBoolean()) {
                check(Runtime.getRuntime().maxMemory() <= 320L * 1024L * 1024L) {
                    "test worker heap exceeds 320 MiB"
                }
            }
        } catch (failure: Throwable) {
            primaryFailure = failure
            throw failure
        } finally {
            releaseIterators.countDown()
            executor.shutdownNow()
            val terminated = executor.awaitTermination(10, TimeUnit.SECONDS)
            context.close()
            if (!terminated) {
                cleanupFailure = IllegalStateException("performance test executor did not terminate")
            }
        }
        cleanupFailure?.let { failure ->
            primaryFailure?.addSuppressed(failure) ?: throw failure
        }
    }

    private fun syntheticPages(): List<ListObjectsV2Response> =
        (0 until PAGE_COUNT).map { pageIndex ->
            ListObjectsV2Response.builder()
                .contents(
                    (0 until KEYS_PER_PAGE).map { keyIndex ->
                        val index = pageIndex * KEYS_PER_PAGE + keyIndex
                        S3Object.builder().key("config/key-${index.toString().padStart(6, '0')}.json").build()
                    },
                )
                .build()
        }

    private companion object {
        const val CALLERS = 4
        const val PAGE_COUNT = 50
        const val KEYS_PER_PAGE = 1_000
    }
}
