package io.bluetape4k.aws.kotlin.sfn

import aws.sdk.kotlin.services.sfn.SfnClient
import aws.sdk.kotlin.services.sfn.model.DescribeExecutionRequest
import aws.sdk.kotlin.services.sfn.model.DescribeExecutionResponse
import aws.sdk.kotlin.services.sfn.model.ExecutionStatus
import aws.sdk.kotlin.services.sfn.model.StopExecutionRequest
import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeSameInstanceAs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import aws.smithy.kotlin.runtime.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class SfnExecutionFlowTest {

    private companion object {
        const val EXECUTION_ARN = "arn:aws:states:ap-northeast-2:123456789012:execution:orders:run-1"

        val INSTANT: Instant = Instant.fromEpochSeconds(0)
    }

    @Test
    fun `polling interval must be finite and at least one second`() {
        assertFailsWith<IllegalArgumentException> {
            SfnExecutionPollingOptions(999.milliseconds)
        }
        SfnExecutionPollingOptions(1.seconds)
        assertFailsWith<IllegalArgumentException> {
            SfnExecutionPollingOptions(Duration.INFINITE)
        }
        assertFailsWith<IllegalArgumentException> {
            SfnExecutionPollingOptions(-Duration.INFINITE)
        }
    }

    @Test
    fun `running response is emitted before terminal response`() = runTest {
        val running = response(ExecutionStatus.Running)
        val succeeded = response(ExecutionStatus.Succeeded)
        val client = mockk<SfnClient>()
        coEvery { client.describeExecution(any<DescribeExecutionRequest>()) } returnsMany listOf(running, succeeded)

        val emissions = client.describeExecutionFlow(EXECUTION_ARN).toList()

        emissions shouldBeEqualTo listOf(running, succeeded)
        coVerify(exactly = 2) { client.describeExecution(any<DescribeExecutionRequest>()) }
    }

    @Test
    fun `pending redrive is emitted as terminal raw response`() = runTest {
        val pendingRedrive = response(ExecutionStatus.PendingRedrive)
        val client = mockk<SfnClient>()
        coEvery { client.describeExecution(any<DescribeExecutionRequest>()) } returns pendingRedrive

        val result = client.describeExecutionFlow(EXECUTION_ARN).toList()

        result.single() shouldBeSameInstanceAs pendingRedrive
        coVerify(exactly = 1) { client.describeExecution(any<DescribeExecutionRequest>()) }
    }

    @Test
    fun `sdk unknown does not emit response or auto stop`() = runTest {
        val unknown = response(ExecutionStatus.SdkUnknown("FUTURE_STATUS"))
        val client = mockk<SfnClient>()
        coEvery { client.describeExecution(any<DescribeExecutionRequest>()) } returns unknown
        val emissions = mutableListOf<DescribeExecutionResponse>()

        val error = assertFailsWith<IllegalStateException> {
            client.describeExecutionFlow(EXECUTION_ARN)
                .onEach(emissions::add)
                .collect()
        }

        error.message shouldBeEqualTo "Unsupported Step Functions execution status: FUTURE_STATUS"
        emissions shouldBeEqualTo emptyList()
        coVerify(exactly = 1) { client.describeExecution(any<DescribeExecutionRequest>()) }
        coVerify(exactly = 0) { client.stopExecution(any<StopExecutionRequest>()) }
    }

    @Test
    fun `take one cancels a running flow without a second request`() = runTest {
        val running = response(ExecutionStatus.Running)
        val client = mockk<SfnClient>()
        coEvery { client.describeExecution(any<DescribeExecutionRequest>()) } returns running

        val result = client.describeExecutionFlow(EXECUTION_ARN).take(1).toList()

        result.single() shouldBeSameInstanceAs running
        coVerify(exactly = 1) { client.describeExecution(any<DescribeExecutionRequest>()) }
        coVerify(exactly = 0) { client.stopExecution(any<StopExecutionRequest>()) }
    }

    @Test
    fun `cancellation propagates and does not call stop execution`() = runTest {
        val running = response(ExecutionStatus.Running)
        val client = mockk<SfnClient>()
        coEvery { client.describeExecution(any<DescribeExecutionRequest>()) } returns running
        val job = launch {
            client.describeExecutionFlow(EXECUTION_ARN).collect()
        }
        runCurrent()

        job.cancelAndJoin()

        coVerify(atLeast = 1) { client.describeExecution(any<DescribeExecutionRequest>()) }
        coVerify(exactly = 0) { client.stopExecution(any<StopExecutionRequest>()) }
    }

    @Test
    fun `flow is cold and each collection starts a new request`() = runTest {
        val first = response(ExecutionStatus.Succeeded)
        val second = response(ExecutionStatus.Succeeded)
        val client = mockk<SfnClient>()
        coEvery { client.describeExecution(any<DescribeExecutionRequest>()) } returnsMany listOf(first, second)

        client.describeExecutionFlow(EXECUTION_ARN).toList()
        client.describeExecutionFlow(EXECUTION_ARN).toList()

        coVerify(exactly = 2) { client.describeExecution(any<DescribeExecutionRequest>()) }
    }

    @Test
    fun `request overload preserves caller request`() = runTest {
        val expectedRequest = DescribeExecutionRequest {
            executionArn = EXECUTION_ARN
        }
        val response = response(ExecutionStatus.Succeeded)
        val client = mockk<SfnClient>()
        coEvery { client.describeExecution(expectedRequest) } returns response

        val result = client.describeExecutionFlow(expectedRequest).toList()

        result.single() shouldBeSameInstanceAs response
        coVerify(exactly = 1) { client.describeExecution(expectedRequest) }
    }

    private fun response(status: ExecutionStatus): DescribeExecutionResponse = DescribeExecutionResponse {
        this.status = status
        startDate = INSTANT
        executionArn = EXECUTION_ARN
        stateMachineArn = "arn:aws:states:ap-northeast-2:123456789012:stateMachine:orders"
    }
}
