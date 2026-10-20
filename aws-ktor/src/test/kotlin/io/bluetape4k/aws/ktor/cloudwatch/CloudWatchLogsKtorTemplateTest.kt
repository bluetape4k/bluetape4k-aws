package io.bluetape4k.aws.ktor.cloudwatch

import aws.sdk.kotlin.services.cloudwatchlogs.CloudWatchLogsClient
import aws.sdk.kotlin.services.cloudwatchlogs.model.CreateLogGroupRequest
import aws.sdk.kotlin.services.cloudwatchlogs.model.CreateLogGroupResponse
import aws.sdk.kotlin.services.cloudwatchlogs.model.CreateLogStreamRequest
import aws.sdk.kotlin.services.cloudwatchlogs.model.CreateLogStreamResponse
import aws.sdk.kotlin.services.cloudwatchlogs.model.DescribeLogGroupsRequest
import aws.sdk.kotlin.services.cloudwatchlogs.model.DescribeLogGroupsResponse
import aws.sdk.kotlin.services.cloudwatchlogs.model.DescribeLogStreamsRequest
import aws.sdk.kotlin.services.cloudwatchlogs.model.DescribeLogStreamsResponse
import aws.sdk.kotlin.services.cloudwatchlogs.model.InputLogEvent
import aws.sdk.kotlin.services.cloudwatchlogs.model.PutLogEventsRequest
import aws.sdk.kotlin.services.cloudwatchlogs.model.PutLogEventsResponse
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
import kotlinx.coroutines.CancellationException

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CloudWatchLogsKtorTemplateTest {

    private val client = mockk<CloudWatchLogsClient>()
    private val putRequests = mutableListOf<PutLogEventsRequest>()

    @BeforeEach
    fun resetMocks() {
        clearMocks(client)
        putRequests.clear()
    }

    @Test
    fun `putLogEvents batches by configured size`() = runSuspendIO {
        coEvery { client.putLogEvents(any<PutLogEventsRequest>()) } coAnswers {
            putRequests += firstArg<PutLogEventsRequest>()
            PutLogEventsResponse {}
        }
        val operations = CloudWatchLogsKtorTemplate(client, logStream(), batchSize = 2)

        val responses = operations.putLogEvents(listOf(event(3), event(1), event(2)))

        responses.size shouldBeEqualTo 2
        putRequests.map { it.logEvents?.size } shouldBeEqualTo listOf(2, 1)
        putRequests.map { it.logGroupName }.distinct() shouldBeEqualTo listOf("/app/test")
        putRequests.map { it.logEvents?.map(InputLogEvent::message) } shouldBeEqualTo
                listOf(listOf("message-3", "message-1"), listOf("message-2"))
    }

    @Test
    fun `empty log events do not call AWS`() = runSuspendIO {
        val operations = CloudWatchLogsKtorTemplate(client, logStream())

        operations.putLogEvents(emptyList()) shouldBeEqualTo emptyList()

        coVerify(exactly = 0) { client.putLogEvents(any<PutLogEventsRequest>()) }
    }

    @Test
    fun `default log stream is required only for default methods`() = runSuspendIO {
        coEvery { client.putLogEvents(any<PutLogEventsRequest>()) } returns PutLogEventsResponse {}
        val operations = CloudWatchLogsKtorTemplate(client)

        assertFailsWith<IllegalArgumentException> {
            operations.putLogEvents(listOf(event(1)))
        }
        operations.putLogEvents(logStream(), listOf(event(1))).size shouldBeEqualTo 1
    }

    @Test
    fun `create and describe operations delegate to AWS client`() = runSuspendIO {
        coEvery { client.createLogGroup(any<CreateLogGroupRequest>()) } returns CreateLogGroupResponse {}
        coEvery { client.createLogStream(any<CreateLogStreamRequest>()) } returns CreateLogStreamResponse {}
        coEvery { client.describeLogGroups(any<DescribeLogGroupsRequest>()) } returns DescribeLogGroupsResponse {}
        coEvery { client.describeLogStreams(any<DescribeLogStreamsRequest>()) } returns DescribeLogStreamsResponse {}
        val operations = CloudWatchLogsKtorTemplate(client)

        operations.createLogGroup("/app/test")
        operations.createLogStream(logStream())
        operations.describeLogGroups("/app")
        operations.describeLogStreams("/app/test", "stream")

        coVerify(exactly = 1) { client.createLogGroup(any<CreateLogGroupRequest>()) }
        coVerify(exactly = 1) { client.createLogStream(any<CreateLogStreamRequest>()) }
        coVerify(exactly = 1) { client.describeLogGroups(any<DescribeLogGroupsRequest>()) }
        coVerify(exactly = 1) { client.describeLogStreams(any<DescribeLogStreamsRequest>()) }
    }

    @Test
    fun `describe log groups preserves nullable prefix and next token`() = runSuspendIO {
        val response = DescribeLogGroupsResponse { nextToken = "next-page" }
        val requests = mutableListOf<DescribeLogGroupsRequest>()
        coEvery { client.describeLogGroups(any<DescribeLogGroupsRequest>()) } coAnswers {
            requests += firstArg<DescribeLogGroupsRequest>()
            response
        }
        val operations = CloudWatchLogsKtorTemplate(client)

        val actual = operations.describeLogGroups()

        requests.single().logGroupNamePrefix.shouldBeNull()
        actual shouldBeSameInstanceAs response
        actual.nextToken shouldBeEqualTo "next-page"
    }

    @Test
    fun `cancelled log operations propagate cancellation`() = runSuspendIO {
        coEvery { client.createLogGroup(any<CreateLogGroupRequest>()) } throws CancellationException("cancelled")
        coEvery { client.createLogStream(any<CreateLogStreamRequest>()) } throws CancellationException("cancelled")
        coEvery { client.putLogEvents(any<PutLogEventsRequest>()) } throws CancellationException("cancelled")
        coEvery { client.describeLogGroups(any<DescribeLogGroupsRequest>()) } throws CancellationException("cancelled")
        coEvery { client.describeLogStreams(any<DescribeLogStreamsRequest>()) } throws CancellationException("cancelled")
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
        InputLogEvent {
            this.timestamp = timestamp
            message = "message-$timestamp"
        }
}
