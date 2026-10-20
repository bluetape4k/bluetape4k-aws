package io.bluetape4k.aws.ktor.cloudwatch

import aws.sdk.kotlin.services.cloudwatch.CloudWatchClient
import aws.sdk.kotlin.services.cloudwatch.model.Dimension
import aws.sdk.kotlin.services.cloudwatch.model.DimensionFilter
import aws.sdk.kotlin.services.cloudwatch.model.ListMetricsRequest
import aws.sdk.kotlin.services.cloudwatch.model.ListMetricsResponse
import aws.sdk.kotlin.services.cloudwatch.model.MetricDatum
import aws.sdk.kotlin.services.cloudwatch.model.PutMetricDataRequest
import aws.sdk.kotlin.services.cloudwatch.model.PutMetricDataResponse
import aws.sdk.kotlin.services.cloudwatch.model.StandardUnit
import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldBeSameInstanceAs
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.util.concurrent.CancellationException

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CloudWatchKtorTemplateTest {

    private val client = mockk<CloudWatchClient>()
    private val putRequests = mutableListOf<PutMetricDataRequest>()
    private val listRequests = mutableListOf<ListMetricsRequest>()

    @BeforeEach
    fun resetMocks() {
        clearMocks(client)
        putRequests.clear()
        listRequests.clear()
    }

    @Test
    fun `putMetricData batches by configured size`() = runSuspendIO {
        coEvery { client.putMetricData(any<PutMetricDataRequest>()) } coAnswers {
            putRequests += firstArg<PutMetricDataRequest>()
            PutMetricDataResponse {}
        }
        val operations = CloudWatchKtorTemplate(client, namespace = "App/Test", batchSize = 2)
        val metrics = listOf(metric("a"), metric("b"), metric("c"))

        val responses = operations.putMetricData(metrics)

        responses.size shouldBeEqualTo 2
        putRequests.map { it.metricData?.size } shouldBeEqualTo listOf(2, 1)
        putRequests.map { it.namespace }.distinct() shouldBeEqualTo listOf("App/Test")
    }

    @Test
    fun `empty metric data does not call AWS`() = runSuspendIO {
        val operations = CloudWatchKtorTemplate(client, namespace = "App/Test")

        operations.putMetricData(emptyList()) shouldBeEqualTo emptyList()

        coVerify(exactly = 0) { client.putMetricData(any<PutMetricDataRequest>()) }
    }

    @Test
    fun `default namespace is required only for default namespace methods`() = runSuspendIO {
        coEvery { client.putMetricData(any<PutMetricDataRequest>()) } returns PutMetricDataResponse {}
        val operations = CloudWatchKtorTemplate(client)

        assertFailsWith<IllegalArgumentException> {
            operations.putMetricDatum(metric("a"))
        }
        operations.putMetricDatum("App/Test", metric("a")).size shouldBeEqualTo 1
    }

    @Test
    fun `listMetrics delegates to AWS client`() = runSuspendIO {
        coEvery { client.listMetrics(any<ListMetricsRequest>()) } coAnswers {
            listRequests += firstArg<ListMetricsRequest>()
            ListMetricsResponse {}
        }
        val operations = CloudWatchKtorTemplate(client)

        operations.listMetrics(namespace = "App/Test", metricName = "Latency")

        listRequests.single().namespace shouldBeEqualTo "App/Test"
        listRequests.single().metricName shouldBeEqualTo "Latency"
    }

    @Test
    fun `listMetrics preserves nullable filters dimensions and next token`() = runSuspendIO {
        val response = ListMetricsResponse { nextToken = "next-page" }
        coEvery { client.listMetrics(any<ListMetricsRequest>()) } coAnswers {
            listRequests += firstArg<ListMetricsRequest>()
            response
        }
        val dimensions = listOf(
            DimensionFilter {
                name = "queue"
                value = "orders"
            },
        )
        val operations = CloudWatchKtorTemplate(client)

        val actual = operations.listMetrics(dimensions = dimensions)

        listRequests.single().namespace.shouldBeNull()
        listRequests.single().metricName.shouldBeNull()
        listRequests.single().dimensions shouldBeEqualTo dimensions
        actual shouldBeSameInstanceAs response
        actual.nextToken shouldBeEqualTo "next-page"
    }

    @Test
    fun `putMetricData preserves optional datum fields`() = runSuspendIO {
        coEvery { client.putMetricData(any<PutMetricDataRequest>()) } coAnswers {
            putRequests += firstArg<PutMetricDataRequest>()
            PutMetricDataResponse {}
        }
        val datum = MetricDatum {
            metricName = "orders.created"
            dimensions = listOf(
                Dimension {
                    name = "queue"
                    value = "orders"
                },
            )
            timestamp = aws.smithy.kotlin.runtime.time.Instant.fromEpochSeconds(
                java.time.Instant.parse("2026-10-20T00:00:00Z").epochSecond,
            )
            unit = StandardUnit.Milliseconds
            value = 12.5
            storageResolution = 1
        }
        val operations = CloudWatchKtorTemplate(client)

        operations.putMetricData("App/Test", listOf(datum))

        putRequests.single().metricData?.single() shouldBeEqualTo datum
    }

    @Test
    fun `cancelled metric publish propagates cancellation`() = runSuspendIO {
        coEvery { client.putMetricData(any<PutMetricDataRequest>()) } throws CancellationException("cancelled")
        val operations = CloudWatchKtorTemplate(client, namespace = "App/Test")

        assertFailsWith<CancellationException> {
            operations.putMetricDatum(metric("cancelled"))
        }
    }

    @Test
    fun `cancelled metric list propagates cancellation`() = runSuspendIO {
        coEvery { client.listMetrics(any<ListMetricsRequest>()) } throws CancellationException("cancelled")
        val operations = CloudWatchKtorTemplate(client)

        assertFailsWith<CancellationException> {
            operations.listMetrics(namespace = "App/Test")
        }
    }

    private fun metric(name: String): MetricDatum =
        MetricDatum {
            metricName = name
            value = 1.0
        }
}
