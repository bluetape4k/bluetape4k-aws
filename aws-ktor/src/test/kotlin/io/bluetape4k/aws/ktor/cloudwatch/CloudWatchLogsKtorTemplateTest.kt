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
import software.amazon.awssdk.services.cloudwatchlogs.CloudWatchLogsAsyncClient
import software.amazon.awssdk.services.cloudwatchlogs.model.CreateLogGroupRequest
import software.amazon.awssdk.services.cloudwatchlogs.model.CreateLogGroupResponse
import software.amazon.awssdk.services.cloudwatchlogs.model.CreateLogStreamRequest
import software.amazon.awssdk.services.cloudwatchlogs.model.CreateLogStreamResponse
import software.amazon.awssdk.services.cloudwatchlogs.model.DescribeLogGroupsRequest
import software.amazon.awssdk.services.cloudwatchlogs.model.DescribeLogGroupsResponse
import software.amazon.awssdk.services.cloudwatchlogs.model.DescribeLogStreamsRequest
import software.amazon.awssdk.services.cloudwatchlogs.model.DescribeLogStreamsResponse
import software.amazon.awssdk.services.cloudwatchlogs.model.InputLogEvent
import software.amazon.awssdk.services.cloudwatchlogs.model.PutLogEventsRequest
import software.amazon.awssdk.services.cloudwatchlogs.model.PutLogEventsResponse
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.function.Consumer

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CloudWatchLogsKtorTemplateTest {

    private val client = mockk<CloudWatchLogsAsyncClient>()
    private val putRequests = mutableListOf<PutLogEventsRequest>()

    @BeforeEach
    fun resetMocks() {
        clearMocks(client)
        putRequests.clear()
    }

    @Test
    fun `putLogEvents batches by configured size`() = runSuspendIO {
        every { client.putLogEvents(any<Consumer<PutLogEventsRequest.Builder>>()) } answers {
            val builder = PutLogEventsRequest.builder()
            firstArg<Consumer<PutLogEventsRequest.Builder>>().accept(builder)
            putRequests += builder.build()
            CompletableFuture.completedFuture(PutLogEventsResponse.builder().build())
        }
        val operations = CloudWatchLogsKtorTemplate(client, logStream(), batchSize = 2)

        val responses = operations.putLogEvents(listOf(event(3), event(1), event(2)))

        responses.size shouldBeEqualTo 2
        putRequests.map { it.logEvents().size } shouldBeEqualTo listOf(2, 1)
        putRequests.map { it.logGroupName() }.distinct() shouldBeEqualTo listOf("/app/test")
    }

    @Test
    fun `empty log events do not call AWS`() = runSuspendIO {
        val operations = CloudWatchLogsKtorTemplate(client, logStream())

        operations.putLogEvents(emptyList()) shouldBeEqualTo emptyList()

        verify(exactly = 0) { client.putLogEvents(any<Consumer<PutLogEventsRequest.Builder>>()) }
    }

    @Test
    fun `default log stream is required only for default methods`() = runSuspendIO {
        every { client.putLogEvents(any<Consumer<PutLogEventsRequest.Builder>>()) } returns
            CompletableFuture.completedFuture(PutLogEventsResponse.builder().build())
        val operations = CloudWatchLogsKtorTemplate(client)

        assertFailsWith<IllegalArgumentException> {
            operations.putLogEvents(listOf(event(1)))
        }
        operations.putLogEvents(logStream(), listOf(event(1))).size shouldBeEqualTo 1
    }

    @Test
    fun `create and describe operations delegate to AWS client`() = runSuspendIO {
        every { client.createLogGroup(any<Consumer<CreateLogGroupRequest.Builder>>()) } returns
            CompletableFuture.completedFuture(CreateLogGroupResponse.builder().build())
        every { client.createLogStream(any<Consumer<CreateLogStreamRequest.Builder>>()) } returns
            CompletableFuture.completedFuture(CreateLogStreamResponse.builder().build())
        every { client.describeLogGroups(any<Consumer<DescribeLogGroupsRequest.Builder>>()) } returns
            CompletableFuture.completedFuture(DescribeLogGroupsResponse.builder().build())
        every { client.describeLogStreams(any<Consumer<DescribeLogStreamsRequest.Builder>>()) } returns
            CompletableFuture.completedFuture(DescribeLogStreamsResponse.builder().build())
        val operations = CloudWatchLogsKtorTemplate(client)

        operations.createLogGroup("/app/test")
        operations.createLogStream(logStream())
        operations.describeLogGroups("/app")
        operations.describeLogStreams("/app/test", "stream")

        verify(exactly = 1) { client.createLogGroup(any<Consumer<CreateLogGroupRequest.Builder>>()) }
        verify(exactly = 1) { client.createLogStream(any<Consumer<CreateLogStreamRequest.Builder>>()) }
        verify(exactly = 1) { client.describeLogGroups(any<Consumer<DescribeLogGroupsRequest.Builder>>()) }
        verify(exactly = 1) { client.describeLogStreams(any<Consumer<DescribeLogStreamsRequest.Builder>>()) }
    }

    @Test
    fun `cancelled log operations propagate cancellation`() = runSuspendIO {
        every { client.createLogGroup(any<Consumer<CreateLogGroupRequest.Builder>>()) } returns cancelled()
        every { client.createLogStream(any<Consumer<CreateLogStreamRequest.Builder>>()) } returns cancelled()
        every { client.putLogEvents(any<Consumer<PutLogEventsRequest.Builder>>()) } returns cancelled()
        every { client.describeLogGroups(any<Consumer<DescribeLogGroupsRequest.Builder>>()) } returns cancelled()
        every { client.describeLogStreams(any<Consumer<DescribeLogStreamsRequest.Builder>>()) } returns cancelled()
        val operations = CloudWatchLogsKtorTemplate(client, logStream())

        assertFailsWith<CancellationException> { operations.createLogGroup("/app/test") }
        assertFailsWith<CancellationException> { operations.createLogStream(logStream()) }
        assertFailsWith<CancellationException> { operations.putLogEvents(listOf(event(1))) }
        assertFailsWith<CancellationException> { operations.describeLogGroups("/app") }
        assertFailsWith<CancellationException> { operations.describeLogStreams("/app/test") }
    }

    private fun logStream(): CloudWatchLogStream =
        CloudWatchLogStream("/app/test", "stream-1")

    private fun event(timestamp: Long): InputLogEvent =
        InputLogEvent.builder()
            .timestamp(timestamp)
            .message("message-$timestamp")
            .build()

    private fun <T> cancelled(): CompletableFuture<T> =
        CompletableFuture<T>().apply { cancel(true) }
}
