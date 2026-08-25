package io.bluetape4k.aws.spring.sns

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldHaveSize
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Test
import software.amazon.awssdk.services.sns.SnsAsyncClient
import software.amazon.awssdk.services.sns.model.ListTopicsResponse
import software.amazon.awssdk.services.sns.model.ListTopicsRequest
import software.amazon.awssdk.services.sns.model.Topic
import java.net.URI
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.CompletableFuture
import java.util.function.Consumer

class SnsTopicArnResolverTest {

    @Test
    fun `in memory cache applies ttl negative entries and lru size`() {
        val clock = MutableClock(Instant.parse("2026-05-13T00:00:00Z"))
        val cache = InMemorySnsTopicArnCache(maxSize = 2, ttl = Duration.ofMinutes(5), clock = clock)
        val first = key("first")
        val second = key("second")
        val third = key("third")

        cache.put(first, SnsTopicArnCacheEntry.Resolved("arn:aws:sns:us-east-1:123:first"))
        cache.put(second, SnsTopicArnCacheEntry.NotFound)
        cache.get(second) shouldBeEqualTo SnsTopicArnCacheEntry.NotFound

        cache.put(third, SnsTopicArnCacheEntry.Resolved("arn:aws:sns:us-east-1:123:third"))
        cache.get(first).shouldBeNull()

        clock.currentInstant = clock.currentInstant.plus(Duration.ofMinutes(6))
        cache.get(second).shouldBeNull()
        cache.get(third).shouldBeNull()
    }

    @Test
    fun `invalidate and clear remove resolved and negative entries`() {
        val cache = InMemorySnsTopicArnCache(maxSize = 2, ttl = Duration.ofMinutes(5))
        val first = key("first")
        val second = key("second")
        cache.put(first, SnsTopicArnCacheEntry.Resolved("arn:aws:sns:us-east-1:123:first"))
        cache.put(second, SnsTopicArnCacheEntry.NotFound)

        cache.invalidate(first)
        cache.get(first).shouldBeNull()
        cache.clear()
        cache.get(second).shouldBeNull()
    }

    @Test
    fun `resolves all list topics pages and preserves fifo suffix`() = runTest {
        val client = mockk<SnsAsyncClient>()
        var calls = 0
        every { client.listTopics(any<Consumer<ListTopicsRequest.Builder>>()) } answers {
            calls += 1
            when (calls) {
                1 -> completedList(
                    topicArn = "arn:aws:sns:us-east-1:123:other",
                    nextToken = "page-2",
                )
                else -> completedList(topicArn = "arn:aws:sns:us-east-1:123:orders.fifo")
            }
        }
        val resolver = resolver(client)

        resolver.resolve(" orders.fifo ") shouldBeEqualTo "arn:aws:sns:us-east-1:123:orders.fifo"
        calls shouldBeEqualTo 2
    }

    @Test
    fun `explicit arn bypasses cache and sdk lookup`() = runTest {
        val client = mockk<SnsAsyncClient>()
        val resolver = resolver(
            client,
            scope = SnsTopicArnResolverScope(region = "us-east-1", accountId = "123456789012"),
        )
        val arn = "arn:aws:sns:us-east-1:123456789012:orders.fifo"

        resolver.resolve(" $arn ") shouldBeEqualTo arn

        verify(exactly = 0) { client.listTopics(any<Consumer<ListTopicsRequest.Builder>>()) }
    }

    @Test
    fun `explicit arn validates sns shape region and cross account policy`() = runTest {
        val client = mockk<SnsAsyncClient>()
        val resolver = resolver(client)

        assertFailsWith<IllegalArgumentException> {
            resolver.resolve("arn:aws:s3:us-east-1:123456789012:orders")
        }
        assertFailsWith<IllegalArgumentException> {
            resolver.resolve("arn:aws:sns:eu-west-1:123456789012:orders")
        }
        assertFailsWith<IllegalArgumentException> {
            resolver.resolve("arn:aws:sns:us-east-1:456456456456:orders")
        }
        assertFailsWith<IllegalArgumentException> {
            resolver.resolve("arn:aws:sns:us-east-1:123456789012:orders*")
        }

        val crossAccount = SnsTopicArnResolver(
            snsAsyncClient = client,
            cache = NoopSnsTopicArnCache,
            scope = SnsTopicArnResolverScope(region = "us-east-1", accountId = "123456789012"),
            allowCrossAccountTopicArn = true,
        )
        crossAccount.resolve("arn:aws:sns:us-east-1:456456456456:orders") shouldBeEqualTo
            "arn:aws:sns:us-east-1:456456456456:orders"
    }

    @Test
    fun `scope rejects credential bearing endpoint and topic wildcard`() = runTest {
        assertFailsWith<IllegalArgumentException> {
            SnsTopicArnResolverScope(endpointOverride = URI("http://user:secret@localhost:4566"))
        }
        assertFailsWith<IllegalArgumentException> {
            SnsTopicArnResolverScope(endpointOverride = URI("http://localhost:4566?token=secret"))
        }
        assertFailsWith<IllegalArgumentException> {
            SnsTopicArnResolverScope(endpointOverride = URI("http://localhost:4566/#secret"))
        }
        val resolver = resolver(mockk())
        assertFailsWith<IllegalArgumentException> { resolver.resolve("orders*") }
    }

    @Test
    fun `cache rejects non positive and overly long ttl`() {
        assertFailsWith<IllegalArgumentException> {
            InMemorySnsTopicArnCache(maxSize = 0)
        }
        assertFailsWith<IllegalArgumentException> {
            InMemorySnsTopicArnCache(ttl = Duration.ofHours(25))
        }
        assertFailsWith<IllegalArgumentException> {
            SnsProperties.TopicArnCache(ttl = Duration.ofHours(25))
        }
    }

    @Test
    fun `negative lookup is cached until invalidated`() = runTest {
        val client = mockk<SnsAsyncClient>()
        var calls = 0
        every { client.listTopics(any<Consumer<ListTopicsRequest.Builder>>()) } answers {
            calls += 1
            CompletableFuture.completedFuture(ListTopicsResponse.builder().build())
        }
        val resolver = resolver(client)

        resolver.resolve("missing")
        resolver.resolve("missing").shouldBeNull()
        calls shouldBeEqualTo 1

        resolver.invalidate("missing")
        resolver.resolve("missing").shouldBeNull()
        calls shouldBeEqualTo 2
    }

    @Test
    fun `positive cache hit lasts until ttl expiry`() = runTest {
        val client = mockk<SnsAsyncClient>()
        var calls = 0
        every { client.listTopics(any<Consumer<ListTopicsRequest.Builder>>()) } answers {
            calls += 1
            completedList(topicArn = "arn:aws:sns:us-east-1:123:orders")
        }
        val clock = MutableClock(Instant.parse("2026-05-13T00:00:00Z"))
        val cache = InMemorySnsTopicArnCache(maxSize = 4, ttl = Duration.ofMinutes(5), clock = clock)
        val resolver = resolver(client, cache)

        resolver.resolve("orders") shouldBeEqualTo "arn:aws:sns:us-east-1:123:orders"
        resolver.resolve("orders") shouldBeEqualTo "arn:aws:sns:us-east-1:123:orders"
        calls shouldBeEqualTo 1

        clock.currentInstant = clock.currentInstant.plus(Duration.ofMinutes(6))
        resolver.resolve("orders") shouldBeEqualTo "arn:aws:sns:us-east-1:123:orders"
        calls shouldBeEqualTo 2
    }

    @Test
    fun `sdk failure is not cached`() = runTest {
        val client = mockk<SnsAsyncClient>()
        var calls = 0
        every { client.listTopics(any<Consumer<ListTopicsRequest.Builder>>()) } answers {
            calls += 1
            if (calls == 1) {
                CompletableFuture.failedFuture(IllegalStateException("list failed"))
            } else {
                completedList(topicArn = "arn:aws:sns:us-east-1:123:orders")
            }
        }
        val resolver = resolver(client)

        assertFailsWith<IllegalStateException> { resolver.resolve("orders") }
        resolver.resolve("orders") shouldBeEqualTo "arn:aws:sns:us-east-1:123:orders"
        calls shouldBeEqualTo 2
    }

    @Test
    fun `same topic concurrent lookups use one sdk request`() = runTest {
        val client = mockk<SnsAsyncClient>()
        val pending = CompletableFuture<ListTopicsResponse>()
        var calls = 0
        every { client.listTopics(any<Consumer<ListTopicsRequest.Builder>>()) } answers {
            calls += 1
            pending
        }
        val resolver = resolver(client)
        val first = async { resolver.resolve("orders") }
        val second = async { resolver.resolve("orders") }
        yield()

        calls shouldBeEqualTo 1
        pending.complete(completedList(topicArn = "arn:aws:sns:us-east-1:123:orders").join())
        listOf(first.await(), second.await()).shouldHaveSize(2)
    }

    @Test
    fun `noop cache still shares concurrent success outcome`() = runTest {
        val client = mockk<SnsAsyncClient>()
        val pending = CompletableFuture<ListTopicsResponse>()
        var calls = 0
        every { client.listTopics(any<Consumer<ListTopicsRequest.Builder>>()) } answers {
            calls += 1
            pending
        }
        val resolver = resolver(client, NoopSnsTopicArnCache)
        val first = async { resolver.resolve("orders") }
        val second = async { resolver.resolve("orders") }
        yield()

        calls shouldBeEqualTo 1
        pending.complete(completedList(topicArn = "arn:aws:sns:us-east-1:123:orders").join())
        first.await() shouldBeEqualTo "arn:aws:sns:us-east-1:123:orders"
        second.await() shouldBeEqualTo "arn:aws:sns:us-east-1:123:orders"
        calls shouldBeEqualTo 1
    }

    @Test
    fun `noop cache shares concurrent failure outcome`() = runTest {
        val client = mockk<SnsAsyncClient>()
        val pending = CompletableFuture<ListTopicsResponse>()
        var calls = 0
        every { client.listTopics(any<Consumer<ListTopicsRequest.Builder>>()) } answers {
            calls += 1
            pending
        }
        val resolver = resolver(client, NoopSnsTopicArnCache)
        supervisorScope {
            val first = async { resolver.resolve("orders") }
            val second = async { resolver.resolve("orders") }
            yield()

            calls shouldBeEqualTo 1
            pending.completeExceptionally(IllegalStateException("shared failure"))
            val firstFailure = try {
                first.await()
                error("first lookup should fail")
            } catch (cause: IllegalStateException) {
                cause
            }
            val secondFailure = try {
                second.await()
                error("second lookup should fail")
            } catch (cause: IllegalStateException) {
                cause
            }
            secondFailure.message shouldBeEqualTo firstFailure.message
            calls shouldBeEqualTo 1
        }
    }

    @Test
    fun `evicted cache entry still shares an overlapping flight`() = runTest {
        val client = mockk<SnsAsyncClient>()
        val pending = CompletableFuture<ListTopicsResponse>()
        var calls = 0
        every { client.listTopics(any<Consumer<ListTopicsRequest.Builder>>()) } answers {
            calls += 1
            when (calls) {
                1 -> completedList(topicArn = "arn:aws:sns:us-east-1:123:orders")
                2 -> completedList(topicArn = "arn:aws:sns:us-east-1:123:payments")
                else -> pending
            }
        }
        val resolver = resolver(
            client,
            InMemorySnsTopicArnCache(maxSize = 1, ttl = Duration.ofMinutes(5)),
        )
        resolver.resolve("orders")
        resolver.resolve("payments")

        val first = async { resolver.resolve("orders") }
        val second = async { resolver.resolve("orders") }
        yield()
        calls shouldBeEqualTo 3

        pending.complete(completedList(topicArn = "arn:aws:sns:us-east-1:123:orders").join())
        first.await() shouldBeEqualTo "arn:aws:sns:us-east-1:123:orders"
        second.await() shouldBeEqualTo "arn:aws:sns:us-east-1:123:orders"
        calls shouldBeEqualTo 3
    }

    @Test
    fun `different topic flights overlap without a global lookup lock`() = runTest {
        val client = mockk<SnsAsyncClient>()
        val orders = CompletableFuture<ListTopicsResponse>()
        val payments = CompletableFuture<ListTopicsResponse>()
        var calls = 0
        every { client.listTopics(any<Consumer<ListTopicsRequest.Builder>>()) } answers {
            calls += 1
            if (calls == 1) orders else payments
        }
        val resolver = resolver(client, NoopSnsTopicArnCache)
        val first = async { resolver.resolve("orders") }
        val second = async { resolver.resolve("payments") }
        yield()

        calls shouldBeEqualTo 2
        orders.complete(completedList(topicArn = "arn:aws:sns:us-east-1:123:orders").join())
        payments.complete(completedList(topicArn = "arn:aws:sns:us-east-1:123:payments").join())
        first.await() shouldBeEqualTo "arn:aws:sns:us-east-1:123:orders"
        second.await() shouldBeEqualTo "arn:aws:sns:us-east-1:123:payments"
    }

    @Test
    fun `invalidate detaches a pending flight and prevents stale late write`() = runTest {
        val client = mockk<SnsAsyncClient>()
        val pending = CompletableFuture<ListTopicsResponse>()
        var calls = 0
        every { client.listTopics(any<Consumer<ListTopicsRequest.Builder>>()) } answers {
            calls += 1
            if (calls == 1) pending else completedList(topicArn = "arn:aws:sns:us-east-1:123:orders")
        }
        val resolver = resolver(client)
        val inFlight = async { resolver.resolve("orders") }
        yield()
        calls shouldBeEqualTo 1

        resolver.invalidate("orders")
        pending.complete(ListTopicsResponse.builder().build())
        inFlight.await().shouldBeNull()

        resolver.resolve("orders") shouldBeEqualTo "arn:aws:sns:us-east-1:123:orders"
        calls shouldBeEqualTo 2
    }

    @Test
    fun `clear detaches pending flights and prevents stale late write`() = runTest {
        val client = mockk<SnsAsyncClient>()
        val pending = CompletableFuture<ListTopicsResponse>()
        var calls = 0
        every { client.listTopics(any<Consumer<ListTopicsRequest.Builder>>()) } answers {
            calls += 1
            if (calls == 1) pending else completedList(topicArn = "arn:aws:sns:us-east-1:123:orders")
        }
        val resolver = resolver(client)
        val inFlight = async { resolver.resolve("orders") }
        yield()
        resolver.clear()
        pending.complete(ListTopicsResponse.builder().build())
        inFlight.await().shouldBeNull()

        resolver.resolve("orders") shouldBeEqualTo "arn:aws:sns:us-east-1:123:orders"
        calls shouldBeEqualTo 2
    }

    @Test
    fun `failed flight is removed so next lookup retries`() = runTest {
        val client = mockk<SnsAsyncClient>()
        var calls = 0
        every { client.listTopics(any<Consumer<ListTopicsRequest.Builder>>()) } answers {
            calls += 1
            if (calls == 1) {
                CompletableFuture.failedFuture(IllegalStateException("temporary"))
            } else {
                completedList(topicArn = "arn:aws:sns:us-east-1:123:orders")
            }
        }
        val resolver = resolver(client)

        assertFailsWith<IllegalStateException> { resolver.resolve("orders") }
        resolver.resolve("orders") shouldBeEqualTo "arn:aws:sns:us-east-1:123:orders"
        calls shouldBeEqualTo 2
    }

    @Test
    fun `caller cancellation releases flight for the next lookup`() = runTest {
        val client = mockk<SnsAsyncClient>()
        val pending = CompletableFuture<ListTopicsResponse>()
        var calls = 0
        every { client.listTopics(any<Consumer<ListTopicsRequest.Builder>>()) } answers {
            calls += 1
            if (calls == 1) pending else completedList(topicArn = "arn:aws:sns:us-east-1:123:orders")
        }
        val resolver = resolver(client)
        val cancelled = async { resolver.resolve("orders") }
        yield()
        cancelled.cancelAndJoin()

        resolver.resolve("orders") shouldBeEqualTo "arn:aws:sns:us-east-1:123:orders"
        calls shouldBeEqualTo 2
    }

    @Test
    fun `cache scope separates endpoint region and account`() = runTest {
        val client = mockk<SnsAsyncClient>()
        var calls = 0
        every { client.listTopics(any<Consumer<ListTopicsRequest.Builder>>()) } answers {
            calls += 1
            completedList(topicArn = "arn:aws:sns:us-east-1:123:orders")
        }
        val cache = InMemorySnsTopicArnCache(maxSize = 4, ttl = Duration.ofMinutes(5))
        val first = resolver(client, cache, SnsTopicArnResolverScope(URI("http://one"), "us-east-1", "123"))
        val second = resolver(client, cache, SnsTopicArnResolverScope(URI("http://two"), "us-east-1", "456"))

        first.resolve("orders") shouldBeEqualTo "arn:aws:sns:us-east-1:123:orders"
        second.resolve("orders") shouldBeEqualTo "arn:aws:sns:us-east-1:123:orders"
        calls shouldBeEqualTo 2
    }

    @Test
    fun `resolver namespace separates same visible scope`() = runTest {
        val client = mockk<SnsAsyncClient>()
        var calls = 0
        every { client.listTopics(any<Consumer<ListTopicsRequest.Builder>>()) } answers {
            calls += 1
            completedList(topicArn = "arn:aws:sns:us-east-1:123:orders")
        }
        val cache = InMemorySnsTopicArnCache(maxSize = 4, ttl = Duration.ofMinutes(5))
        val first = resolver(client, cache, SnsTopicArnResolverScope(URI("http://same"), "us-east-1", "123"))
        val second = resolver(client, cache, SnsTopicArnResolverScope(URI("http://same"), "us-east-1", "123"))

        first.resolve("orders")
        second.resolve("orders")
        calls shouldBeEqualTo 2
    }

    private fun resolver(
        client: SnsAsyncClient,
        cache: SnsTopicArnCache = InMemorySnsTopicArnCache(maxSize = 16, ttl = Duration.ofMinutes(5)),
        scope: SnsTopicArnResolverScope = SnsTopicArnResolverScope(region = "us-east-1", accountId = "123"),
    ): SnsTopicArnResolver = SnsTopicArnResolver(client, cache, scope)

    private fun key(topicName: String): SnsTopicArnCacheKey =
        SnsTopicArnCacheKey(
            scope = SnsTopicArnResolverScope(region = "us-east-1", accountId = "123"),
            topicName = topicName,
        )

    private fun completedList(
        topicArn: String,
        nextToken: String? = null,
    ): CompletableFuture<ListTopicsResponse> =
        CompletableFuture.completedFuture(
            ListTopicsResponse.builder()
                .topics(Topic.builder().topicArn(topicArn).build())
                .nextToken(nextToken)
                .build(),
        )

    private class MutableClock(
        var currentInstant: Instant,
    ): Clock() {
        override fun getZone(): ZoneId = ZoneId.of("UTC")
        override fun withZone(zone: ZoneId): Clock = this
        override fun instant(): Instant = currentInstant
    }
}
