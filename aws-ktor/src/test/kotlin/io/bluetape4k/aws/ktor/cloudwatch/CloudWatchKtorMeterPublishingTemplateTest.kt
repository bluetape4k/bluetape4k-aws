package io.bluetape4k.aws.ktor.cloudwatch

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import software.amazon.awssdk.services.cloudwatch.model.MetricDatum
import software.amazon.awssdk.services.cloudwatch.model.PutMetricDataResponse
import java.util.concurrent.CancellationException

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CloudWatchKtorMeterPublishingTemplateTest {

    private val operations = mockk<CloudWatchKtorOperations>()

    @BeforeEach
    fun resetMocks() {
        clearMocks(operations)
    }

    @Test
    fun `empty registry does not publish`() = runSuspendIO {
        val publisher = CloudWatchKtorMeterPublishingTemplate(SimpleMeterRegistry(), operations)

        publisher.publishMeters() shouldBeEqualTo emptyList()

        coVerify(exactly = 0) { operations.putMetricData(any<List<MetricDatum>>()) }
    }

    @Test
    fun `selected meter measurements publish as CloudWatch metric data`() = runSuspendIO {
        val registry = SimpleMeterRegistry()
        registry.counter("orders.created", "queue", "orders").increment(3.0)
        coEvery { operations.putMetricData(any<List<MetricDatum>>()) } returns
            listOf(PutMetricDataResponse.builder().build())
        val publisher = CloudWatchKtorMeterPublishingTemplate(registry, operations)

        val responses = publisher.publishMeter("orders.created")

        responses.size shouldBeEqualTo 1
        coVerify(exactly = 1) {
            operations.putMetricData(match<List<MetricDatum>> { metricData ->
                metricData.single().metricName() == "orders.created.count" &&
                    metricData.single().dimensions().single().name() == "queue" &&
                    metricData.single().dimensions().single().value() == "orders"
            })
        }
    }

    @Test
    fun `publishMeter rejects blank name`() = runSuspendIO {
        val publisher = CloudWatchKtorMeterPublishingTemplate(SimpleMeterRegistry(), operations)

        assertFailsWith<IllegalArgumentException> {
            publisher.publishMeter(" ")
        }
    }

    @Test
    fun `publishMeters propagates cancellation`() = runSuspendIO {
        val registry = SimpleMeterRegistry()
        registry.counter("orders.created").increment()
        coEvery { operations.putMetricData(any<List<MetricDatum>>()) } throws CancellationException("cancelled")
        val publisher = CloudWatchKtorMeterPublishingTemplate(registry, operations)

        assertFailsWith<CancellationException> {
            publisher.publishMeters()
        }
    }
}
