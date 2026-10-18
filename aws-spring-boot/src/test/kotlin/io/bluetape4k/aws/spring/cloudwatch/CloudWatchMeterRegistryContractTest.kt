package io.bluetape4k.aws.spring.cloudwatch

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.assertions.shouldHaveSize
import io.bluetape4k.assertions.shouldNotContain
import io.bluetape4k.concurrent.completableFutureOf
import io.bluetape4k.concurrent.failedCompletableFutureOf
import io.bluetape4k.javatimes.minutes
import io.bluetape4k.javatimes.seconds
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.micrometer.cloudwatch2.CloudWatchMeterRegistry
import io.micrometer.core.instrument.Clock
import io.micrometer.core.instrument.MockClock
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import software.amazon.awssdk.services.cloudwatch.CloudWatchAsyncClient
import software.amazon.awssdk.services.cloudwatch.model.PutMetricDataRequest
import software.amazon.awssdk.services.cloudwatch.model.PutMetricDataResponse
import java.util.*
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentLinkedQueue

class CloudWatchMeterRegistryContractTest {

    companion object: KLogging()

    private val client = mockk<CloudWatchAsyncClient>(relaxed = true)

    @BeforeEach
    fun beforeEach() {
        clearMocks(client)
    }

    @Test
    fun `registry uses configured namespace and common tags`() {
        val requests = ConcurrentLinkedQueue<PutMetricDataRequest>()
        val client = clientReturning(requests)
        val clock = MockClock()
        val registry = createRegistry(
            client = client,
            clock = clock,
            registry = CloudWatchProperties.Micrometer.Registry(
                commonTags = mapOf("application" to "orders"),
            ),
        )

        try {
            registry.counter("orders.requests").increment()
            clock.addSeconds(60)
            publish(registry)

            log.debug { "requests=$requests" }
            requests shouldHaveSize 1
            requests.single().namespace() shouldBeEqualTo "Test/App"
            requests.single().metricData().single().dimensions().single { it.name() == "application" }
                .value() shouldBeEqualTo "orders"
        } finally {
            registry.close()
        }
    }

    @Test
    fun `include and exclude filters apply only to native registry`() {
        val requests = ConcurrentLinkedQueue<PutMetricDataRequest>()
        val registry = createRegistry(
            client = clientReturning(requests),
            clock = MockClock(),
            registry = CloudWatchProperties.Micrometer.Registry(
                filters = CloudWatchProperties.Micrometer.Filters(
                    includes = listOf("orders."),
                    excludes = listOf("orders.secret"),
                ),
            ),
        )

        try {
            registry.counter("orders.accepted").increment()
            registry.counter("orders.secret.token").increment()
            registry.counter("jvm.memory.used").increment()

            val names = registry.meters.map { it.id.name }

            log.debug { "names=$names" }
            names shouldContain "orders.accepted"
            names shouldNotContain "orders.secret.token"
            names shouldNotContain "jvm.memory.used"
        } finally {
            registry.close()
        }
    }

    @Test
    fun `step below one minute uses high resolution storage`() {
        val requests = ConcurrentLinkedQueue<PutMetricDataRequest>()
        val clock = MockClock()
        val registry = createRegistry(
            client = clientReturning(requests),
            clock = clock,
            registry = CloudWatchProperties.Micrometer.Registry(step = 59.seconds()),
        )

        try {
            registry.counter("orders.requests").increment()
            clock.addSeconds(59)
            publish(registry)

            log.debug { "requests=$requests" }
            requests.single().metricData().single().storageResolution() shouldBeEqualTo 1
        } finally {
            registry.close()
        }
    }

    @Test
    fun `one minute step uses standard resolution`() {
        val requests = ConcurrentLinkedQueue<PutMetricDataRequest>()
        val clock = MockClock()
        val registry = createRegistry(
            client = clientReturning(requests),
            clock = clock,
            registry = CloudWatchProperties.Micrometer.Registry(step = 1.minutes()),
        )

        try {
            registry.counter("orders.requests").increment()
            clock.addSeconds(60)
            publish(registry)

            log.debug { "requests=$requests" }
            requests.single().metricData().single().storageResolution() shouldBeEqualTo 60
        } finally {
            registry.close()
        }
    }

    @Test
    fun `metric data is split by configured batch size`() {
        val requests = ConcurrentLinkedQueue<PutMetricDataRequest>()
        val clock = MockClock()
        val registry = createRegistry(
            client = clientReturning(requests),
            clock = clock,
            registry = CloudWatchProperties.Micrometer.Registry(batchSize = 2),
        )

        try {
            repeat(3) { index -> registry.counter("orders.request.$index").increment() }
            clock.addSeconds(60)
            publish(registry)

            log.debug { "requests=$requests" }
            requests shouldHaveSize 2
            requests.sumOf { it.metricData().size } shouldBeEqualTo 3
            requests.all { it.metricData().size <= 2 }.shouldBeTrue()
        } finally {
            registry.close()
        }
    }

    @ParameterizedTest
    @ValueSource(ints = [40, 1000])
    fun `metric datum and batch call count remain bounded`(metricCount: Int) {
        val requests = ConcurrentLinkedQueue<PutMetricDataRequest>()
        val clock = MockClock()
        val batchSize = 20
        val registry = createRegistry(
            client = clientReturning(requests),
            clock = clock,
            registry = CloudWatchProperties.Micrometer.Registry(batchSize = batchSize),
        )

        try {
            repeat(metricCount) { index -> registry.counter("orders.request.$index").increment() }
            clock.addSeconds(60)
            publish(registry)

            log.debug { "requests size=${requests.size}" }
            requests.size shouldBeEqualTo (metricCount + batchSize - 1) / batchSize
            requests.sumOf { it.metricData().size } shouldBeEqualTo metricCount
            requests.all { it.metricData().size <= batchSize }.shouldBeTrue()
        } finally {
            registry.close()
        }
    }

    @Test
    fun `failed cloudwatch future does not stop a later publish`() {
        val requests = ConcurrentLinkedQueue<PutMetricDataRequest>()
        val failure = failedCompletableFutureOf<PutMetricDataResponse>(IllegalStateException("AccessDenied"))

        every {
            client.putMetricData(any<PutMetricDataRequest>())
        } answers {
            requests += firstArg<PutMetricDataRequest>()
            failure
        }
        val clock = MockClock()
        val registry = createRegistry(client, clock)

        try {
            registry.counter("orders.requests").increment()
            clock.addSeconds(60)
            publish(registry)

            registry.counter("orders.requests").increment()
            clock.addSeconds(60)
            publish(registry)

            log.debug { "requests=$requests" }
            requests shouldHaveSize 2

            verify(exactly = 2) { client.putMetricData(any<PutMetricDataRequest>()) }
        } finally {
            registry.close()
        }
    }

    @Test
    fun `timed out future remains owned by cloudwatch client and is not cancelled`() {
        val requests = ConcurrentLinkedQueue<PutMetricDataRequest>()
        val pending = CompletableFuture<PutMetricDataResponse>()

        every {
            client.putMetricData(any<PutMetricDataRequest>())
        } answers {
            requests += firstArg<PutMetricDataRequest>()
            pending
        }
        val clock = MockClock()
        val registry = createRegistry(
            client = client,
            clock = clock,
            registry = CloudWatchProperties.Micrometer.Registry(readTimeout = 1.seconds()),
        )

        try {
            registry.counter("orders.requests").increment()
            clock.addSeconds(60)
            publish(registry)

            log.debug { "requests=$requests" }
            requests shouldHaveSize 1
            pending.isCancelled.shouldBeFalse()
        } finally {
            pending.complete(PutMetricDataResponse.builder().build())
            registry.close()
        }
    }

    @Test
    fun `close flushes multiple batches, is idempotent, and does not close shared client`() {
        val requests = ConcurrentLinkedQueue<PutMetricDataRequest>()
        val client = clientReturning(requests)
        val clock = MockClock()
        val registry = createRegistry(
            client = client,
            clock = clock,
            registry = CloudWatchProperties.Micrometer.Registry(batchSize = 2),
        )

        registry.counter("orders.request.1").increment()
        registry.counter("orders.request.2").increment()
        registry.counter("orders.request.3").increment()
        clock.addSeconds(60)

        registry.close()
        registry.close()

        requests shouldHaveSize 2
        verify(exactly = 0) { client.close() }
    }

    private fun createRegistry(
        client: CloudWatchAsyncClient,
        clock: Clock,
        registry: CloudWatchProperties.Micrometer.Registry = CloudWatchProperties.Micrometer.Registry(),
    ): CloudWatchMeterRegistry =
        CloudWatchMeterRegistryConfiguration.create(
            cloudWatchAsyncClient = client,
            properties = CloudWatchProperties(
                namespace = "Test/App",
                micrometer = CloudWatchProperties.Micrometer(registry = registry),
            ),
            clock = clock,
        )

    private fun clientReturning(requests: Queue<PutMetricDataRequest>): CloudWatchAsyncClient =
        mockk<CloudWatchAsyncClient>(relaxed = true).also { client ->
            every {
                client.putMetricData(any<PutMetricDataRequest>())
            } answers {
                requests += firstArg<PutMetricDataRequest>()
                completableFutureOf(PutMetricDataResponse.builder().build())
            }
        }

    private fun publish(registry: CloudWatchMeterRegistry) {
        CloudWatchMeterRegistry::class.java.getDeclaredMethod("publish").apply {
            isAccessible = true
            invoke(registry)
        }
    }
}
