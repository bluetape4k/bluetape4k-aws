package io.bluetape4k.aws.spring.cloudwatch

import io.bluetape4k.aws.cloudwatch.model.metricDatumOf
import io.bluetape4k.aws.cloudwatch.model.cloudwatchlogs.inputLogEventOf
import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import software.amazon.awssdk.services.cloudwatch.CloudWatchAsyncClient
import software.amazon.awssdk.services.cloudwatch.model.PutMetricDataRequest
import software.amazon.awssdk.services.cloudwatch.model.PutMetricDataResponse
import software.amazon.awssdk.services.cloudwatch.model.StandardUnit
import software.amazon.awssdk.services.cloudwatchlogs.CloudWatchLogsAsyncClient
import software.amazon.awssdk.services.cloudwatchlogs.model.PutLogEventsRequest
import software.amazon.awssdk.services.cloudwatchlogs.model.PutLogEventsResponse
import java.util.concurrent.CompletableFuture
import java.util.function.Consumer

class CloudWatchCoroutinesTemplateTest {

    private val cloudWatchClient = mockk<CloudWatchAsyncClient>(relaxed = true)
    private val cloudWatchLogsClient = mockk<CloudWatchLogsAsyncClient>(relaxed = true)

    @BeforeEach
    fun resetMocks() {
        clearMocks(cloudWatchClient, cloudWatchLogsClient)
    }

    @Test
    fun `publish metric data with configured namespace and batching`() = runSuspendIO {
        every {
            cloudWatchClient.putMetricData(any<Consumer<PutMetricDataRequest.Builder>>())
        } returns CompletableFuture.completedFuture(PutMetricDataResponse.builder().build())

        val template = CloudWatchCoroutinesTemplate(
            cloudWatchClient,
            CloudWatchProperties(namespace = "Test/App", batchSize = 2),
        )
        val metrics = listOf(
            metricDatumOf("orders", 1.0, StandardUnit.COUNT),
            metricDatumOf("latency", 10.0, StandardUnit.MILLISECONDS),
            metricDatumOf("errors", 0.0, StandardUnit.COUNT),
        )

        val responses = template.putMetricData(metrics)

        responses.size shouldBeEqualTo 2
        verify(exactly = 2) {
            cloudWatchClient.putMetricData(any<Consumer<PutMetricDataRequest.Builder>>())
        }
    }

    @Test
    fun `default metric namespace is required`() {
        val template = CloudWatchCoroutinesTemplate(
            cloudWatchClient,
            CloudWatchProperties(),
        )

        val error = assertFailsWith<IllegalArgumentException> {
            runSuspendIO {
                template.putMetricData(listOf(metricDatumOf("orders", 1.0)))
            }
        }
        error.message.orEmpty() shouldContain "namespace is required"
    }

    @Test
    fun `publish log events with configured group stream and batching`() = runSuspendIO {
        every {
            cloudWatchLogsClient.putLogEvents(any<Consumer<PutLogEventsRequest.Builder>>())
        } returns CompletableFuture.completedFuture(PutLogEventsResponse.builder().build())

        val template = CloudWatchLogsCoroutinesTemplate(
            cloudWatchLogsClient,
            CloudWatchLogsProperties(
                logGroupName = "/app/test",
                logStreamName = "default",
                batchSize = 2,
            ),
        )
        val events = listOf(
            inputLogEventOf(1L, "one"),
            inputLogEventOf(2L, "two"),
            inputLogEventOf(3L, "three"),
        )

        val responses = template.putLogEvents(events)

        responses.size shouldBeEqualTo 2
        verify(exactly = 2) {
            cloudWatchLogsClient.putLogEvents(any<Consumer<PutLogEventsRequest.Builder>>())
        }
    }

    @Test
    fun `default log group is required`() {
        val template = CloudWatchLogsCoroutinesTemplate(
            cloudWatchLogsClient,
            CloudWatchLogsProperties(logStreamName = "default"),
        )

        val error = assertFailsWith<IllegalArgumentException> {
            runSuspendIO {
                template.putLogEvents(listOf(inputLogEventOf(1L, "one")))
            }
        }
        error.message.orEmpty() shouldContain "log-group-name is required"
    }

    @Test
    fun `explicit log group is required`() {
        val template = CloudWatchLogsCoroutinesTemplate(
            cloudWatchLogsClient,
            CloudWatchLogsProperties(),
        )

        val error = assertFailsWith<IllegalArgumentException> {
            runSuspendIO {
                template.createLogGroup(" ")
            }
        }
        error.message.orEmpty() shouldContain "logGroupName"
    }
}
