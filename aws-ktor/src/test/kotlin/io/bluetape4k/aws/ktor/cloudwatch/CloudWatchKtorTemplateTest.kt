package io.bluetape4k.aws.ktor.cloudwatch

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import software.amazon.awssdk.services.cloudwatch.CloudWatchAsyncClient
import software.amazon.awssdk.services.cloudwatch.model.ListMetricsRequest
import software.amazon.awssdk.services.cloudwatch.model.ListMetricsResponse
import software.amazon.awssdk.services.cloudwatch.model.MetricDatum
import software.amazon.awssdk.services.cloudwatch.model.PutMetricDataRequest
import software.amazon.awssdk.services.cloudwatch.model.PutMetricDataResponse
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.function.Consumer

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CloudWatchKtorTemplateTest {

    private val client = mockk<CloudWatchAsyncClient>()
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
        every { client.putMetricData(any<Consumer<PutMetricDataRequest.Builder>>()) } answers {
            val builder = PutMetricDataRequest.builder()
            firstArg<Consumer<PutMetricDataRequest.Builder>>().accept(builder)
            putRequests += builder.build()
            CompletableFuture.completedFuture(PutMetricDataResponse.builder().build())
        }
        val operations = CloudWatchKtorTemplate(client, namespace = "App/Test", batchSize = 2)
        val metrics = listOf(metric("a"), metric("b"), metric("c"))

        val responses = operations.putMetricData(metrics)

        responses.size shouldBeEqualTo 2
        putRequests.map { it.metricData().size } shouldBeEqualTo listOf(2, 1)
        putRequests.map { it.namespace() }.distinct() shouldBeEqualTo listOf("App/Test")
    }

    @Test
    fun `empty metric data does not call AWS`() = runSuspendIO {
        val operations = CloudWatchKtorTemplate(client, namespace = "App/Test")

        operations.putMetricData(emptyList()) shouldBeEqualTo emptyList()

        verify(exactly = 0) { client.putMetricData(any<Consumer<PutMetricDataRequest.Builder>>()) }
    }

    @Test
    fun `default namespace is required only for default namespace methods`() = runSuspendIO {
        every { client.putMetricData(any<Consumer<PutMetricDataRequest.Builder>>()) } returns
            CompletableFuture.completedFuture(PutMetricDataResponse.builder().build())
        val operations = CloudWatchKtorTemplate(client)

        assertFailsWith<IllegalArgumentException> {
            operations.putMetricDatum(metric("a"))
        }
        operations.putMetricDatum("App/Test", metric("a")).size shouldBeEqualTo 1
    }

    @Test
    fun `listMetrics delegates to AWS client`() = runSuspendIO {
        every { client.listMetrics(any<Consumer<ListMetricsRequest.Builder>>()) } answers {
            val builder = ListMetricsRequest.builder()
            firstArg<Consumer<ListMetricsRequest.Builder>>().accept(builder)
            listRequests += builder.build()
            CompletableFuture.completedFuture(ListMetricsResponse.builder().build())
        }
        val operations = CloudWatchKtorTemplate(client)

        operations.listMetrics(namespace = "App/Test", metricName = "Latency")

        listRequests.single().namespace() shouldBeEqualTo "App/Test"
        listRequests.single().metricName() shouldBeEqualTo "Latency"
    }

    @Test
    fun `cancelled metric publish propagates cancellation`() = runSuspendIO {
        val future = CompletableFuture<PutMetricDataResponse>()
        future.cancel(true)
        every { client.putMetricData(any<Consumer<PutMetricDataRequest.Builder>>()) } returns future
        val operations = CloudWatchKtorTemplate(client, namespace = "App/Test")

        assertFailsWith<CancellationException> {
            operations.putMetricDatum(metric("cancelled"))
        }
    }

    @Test
    fun `cancelled metric list propagates cancellation`() = runSuspendIO {
        val future = CompletableFuture<ListMetricsResponse>()
        future.cancel(true)
        every { client.listMetrics(any<Consumer<ListMetricsRequest.Builder>>()) } returns future
        val operations = CloudWatchKtorTemplate(client)

        assertFailsWith<CancellationException> {
            operations.listMetrics(namespace = "App/Test")
        }
    }

    private fun metric(name: String): MetricDatum =
        MetricDatum.builder()
            .metricName(name)
            .value(1.0)
            .build()
}
