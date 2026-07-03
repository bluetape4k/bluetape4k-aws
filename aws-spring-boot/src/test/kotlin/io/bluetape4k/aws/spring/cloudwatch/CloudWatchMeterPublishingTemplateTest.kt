package io.bluetape4k.aws.spring.cloudwatch

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEmpty
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import org.junit.jupiter.api.Test
import software.amazon.awssdk.services.cloudwatch.model.MetricDatum
import software.amazon.awssdk.services.cloudwatch.model.PutMetricDataResponse
import software.amazon.awssdk.services.cloudwatch.model.StandardUnit

class CloudWatchMeterPublishingTemplateTest {

    @Test
    fun `publish selected Micrometer meter snapshots`() = runSuspendIO {
        val registry = SimpleMeterRegistry()
        Counter.builder("orders.processed")
            .tag("status", "ok")
            .register(registry)
            .increment(2.0)
        Counter.builder("orders.failed")
            .register(registry)
            .increment(1.0)
        val operations = mockk<CloudWatchOperations>()
        val metricDataSlot = slot<List<MetricDatum>>()
        coEvery { operations.putMetricData(capture(metricDataSlot)) } returns
            listOf(PutMetricDataResponse.builder().build())

        val publisher = CloudWatchMeterPublishingTemplate(registry, operations)

        val responses = publisher.publishMeter("orders.processed")

        responses.size shouldBeEqualTo 1
        val metricData = metricDataSlot.captured
        metricData.size shouldBeEqualTo 1
        metricData.single().metricName() shouldContain "orders.processed"
        metricData.single().unit() shouldBeEqualTo StandardUnit.COUNT
        metricData.single().dimensions().single().name() shouldBeEqualTo "status"
        metricData.single().dimensions().single().value() shouldBeEqualTo "ok"
        coVerify(exactly = 1) { operations.putMetricData(any<List<MetricDatum>>()) }
    }

    @Test
    fun `empty Micrometer selection does not publish to CloudWatch`() = runSuspendIO {
        val registry = SimpleMeterRegistry()
        val operations = mockk<CloudWatchOperations>()
        val publisher = CloudWatchMeterPublishingTemplate(registry, operations)

        val responses = publisher.publishMeter("orders.missing")

        responses.shouldBeEmpty()
        coVerify(exactly = 0) { operations.putMetricData(any<List<MetricDatum>>()) }
    }

    @Test
    fun `meter exceeding CloudWatch dimension limit fails before publishing`() = runSuspendIO {
        val registry = SimpleMeterRegistry()
        val counterBuilder = Counter.builder("orders.over-tagged")
        repeat(31) { index ->
            counterBuilder.tag("tag-$index", "value-$index")
        }
        counterBuilder
            .register(registry)
            .increment()
        val operations = mockk<CloudWatchOperations>()
        val publisher = CloudWatchMeterPublishingTemplate(registry, operations)

        val error = assertFailsWith<IllegalArgumentException> {
            publisher.publishMeter("orders.over-tagged")
        }

        error.message.orEmpty() shouldContain "CloudWatch metric dimensions[31]"
        coVerify(exactly = 0) { operations.putMetricData(any<List<MetricDatum>>()) }
    }
}
