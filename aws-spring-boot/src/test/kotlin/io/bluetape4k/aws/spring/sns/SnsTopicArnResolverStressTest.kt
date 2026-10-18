package io.bluetape4k.aws.spring.sns

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.concurrent.completableFutureOf
import io.bluetape4k.javatimes.minutes
import io.bluetape4k.junit5.coroutines.SuspendedJobTester
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import software.amazon.awssdk.services.sns.SnsAsyncClient
import software.amazon.awssdk.services.sns.model.ListTopicsRequest
import software.amazon.awssdk.services.sns.model.ListTopicsResponse
import software.amazon.awssdk.services.sns.model.Topic
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.Consumer
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class SnsTopicArnResolverStressTest {

    companion object: KLoggingChannel()

    val client = mockk<SnsAsyncClient>(relaxed = true)

    @BeforeEach
    fun beforeEach() {
        clearMocks(client)
    }

    @Test
    fun `repeated cross key flights stay parallel and single flight under stress`() = runTest {
        val pending = CompletableFuture<ListTopicsResponse>()
        val calls = AtomicInteger()
        every {
            client.listTopics(any<Consumer<ListTopicsRequest.Builder>>())
        } answers {
            calls.incrementAndGet()
            pending
        }
        val topicNames = listOf("orders", "payments", "shipping", "invoices")
        val topicArns = topicNames.associateWith { "arn:aws:sns:us-east-1:000000000123:$it" }
        val resolver = resolver(
            client,
            InMemorySnsTopicArnCache(maxSize = topicNames.size, ttl = 5.minutes()),
        )

        val stress = SuspendedJobTester()
            .workers(16)
            .rounds(4)
            .addAll(
                topicNames.map { topicName ->
                    suspend {
                        resolver.resolve(topicName) shouldBeEqualTo topicArns.getValue(topicName)
                    }
                },
            )
        val running = async {
            stress.run()
        }
        awaitCallCount(calls, topicNames.size)
        calls.get() shouldBeEqualTo topicNames.size

        pending.complete(completedList(topicArns.values.toList()).join())
        running.await()
        calls.get() shouldBeEqualTo topicNames.size
    }

    @Test
    fun `repeated waiter cancellation preserves owner outcome and cancellation identity`() = runTest {
        SuspendedJobTester()
            .workers(8)
            .rounds(8)
            .add {
                val client = mockk<SnsAsyncClient>()
                val pending = CompletableFuture<ListTopicsResponse>()
                val calls = AtomicInteger()
                every { client.listTopics(any<Consumer<ListTopicsRequest.Builder>>()) } answers {
                    calls.incrementAndGet()
                    pending
                }
                val resolver = resolver(client, NoopSnsTopicArnCache)

                supervisorScope {
                    val owner = async { resolver.resolve("orders") }
                    val waiter = async { resolver.resolve("orders") }
                    awaitCallCount(calls, 1)

                    waiter.cancel()
                    assertFailsWith<CancellationException> { waiter.await() }
                    pending.complete(completedList("arn:aws:sns:us-east-1:000000000123:orders").join())

                    owner.await() shouldBeEqualTo "arn:aws:sns:us-east-1:000000000123:orders"
                }
                calls.get() shouldBeEqualTo 1
            }
            .run()
    }

    @Test
    fun `repeated eviction keeps overlapping flight single under stress`() = runTest {
        SuspendedJobTester()
            .workers(8)
            .rounds(8)
            .add {
                val client = mockk<SnsAsyncClient>()
                val pending = CompletableFuture<ListTopicsResponse>()
                val calls = AtomicInteger()
                every { client.listTopics(any<Consumer<ListTopicsRequest.Builder>>()) } answers {
                    when (calls.incrementAndGet()) {
                        1 -> completedList("arn:aws:sns:us-east-1:000000000123:orders")
                        2 -> completedList("arn:aws:sns:us-east-1:000000000123:payments")
                        else -> pending
                    }
                }
                val resolver = resolver(
                    client,
                    InMemorySnsTopicArnCache(maxSize = 1, ttl = 5.minutes()),
                )
                resolver.resolve("orders")
                resolver.resolve("payments")

                val first = async { resolver.resolve("orders") }
                val second = async { resolver.resolve("orders") }
                awaitCallCount(calls, 3)
                pending.complete(completedList("arn:aws:sns:us-east-1:000000000123:orders").join())

                first.await() shouldBeEqualTo "arn:aws:sns:us-east-1:000000000123:orders"
                second.await() shouldBeEqualTo "arn:aws:sns:us-east-1:000000000123:orders"
                calls.get() shouldBeEqualTo 3
            }
            .run()
    }

    @Test
    fun `repeated invalidation and clear races prevent stale cache writes`() = runTest {
        val scenario = AtomicInteger()
        SuspendedJobTester()
            .workers(8)
            .rounds(8)
            .add {
                val client = mockk<SnsAsyncClient>()
                val firstPending = CompletableFuture<ListTopicsResponse>()
                val calls = AtomicInteger()
                every { client.listTopics(any<Consumer<ListTopicsRequest.Builder>>()) } answers {
                    if (calls.incrementAndGet() == 1) {
                        firstPending
                    } else {
                        completedList("arn:aws:sns:us-east-1:000000000123:orders")
                    }
                }
                val cache = InMemorySnsTopicArnCache(maxSize = 2, ttl = 5.minutes())
                val resolver = resolver(client, cache)
                val inFlight = async { resolver.resolve("orders") }
                awaitCallCount(calls, 1)

                if (scenario.getAndIncrement() % 2 == 0) {
                    resolver.invalidate("orders")
                } else {
                    resolver.clear()
                }
                firstPending.complete(ListTopicsResponse.builder().build())
                inFlight.await().shouldBeNull()

                resolver.resolve("orders") shouldBeEqualTo
                        "arn:aws:sns:us-east-1:000000000123:orders"
                calls.get() shouldBeEqualTo 2
                cache.get(SnsTopicArnCacheKey(resolver.scope, "orders")) shouldBeEqualTo
                        SnsTopicArnCacheEntry.Resolved("arn:aws:sns:us-east-1:000000000123:orders")
            }
            .run()
    }

    private fun resolver(
        client: SnsAsyncClient,
        cache: SnsTopicArnCache = InMemorySnsTopicArnCache(maxSize = 16, ttl = 5.minutes()),
        scope: SnsTopicArnResolverScope = SnsTopicArnResolverScope(region = "us-east-1", accountId = "000000000123"),
    ): SnsTopicArnResolver = SnsTopicArnResolver(client, cache, scope)

    private fun completedList(topicArn: String): CompletableFuture<ListTopicsResponse> =
        completableFutureOf(
            ListTopicsResponse.builder()
                .topics(Topic.builder().topicArn(topicArn).build())
                .build(),
        )

    private fun completedList(topicArns: List<String>): CompletableFuture<ListTopicsResponse> =
        completableFutureOf(
            ListTopicsResponse.builder()
                .topics(topicArns.map { Topic.builder().topicArn(it).build() })
                .build(),
        )

    private suspend fun awaitCallCount(calls: AtomicInteger, expected: Int) {
        withTimeout(5.seconds) {
            while (calls.get() < expected) {
                delay(1.milliseconds)
            }
        }
    }
}
