package io.bluetape4k.aws.sfn

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeSameInstanceAs
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import software.amazon.awssdk.services.sfn.SfnAsyncClient
import software.amazon.awssdk.services.sfn.SfnClient
import software.amazon.awssdk.services.sfn.model.DescribeExecutionRequest
import software.amazon.awssdk.services.sfn.model.DescribeExecutionResponse
import software.amazon.awssdk.services.sfn.model.ExecutionStatus
import software.amazon.awssdk.services.sfn.model.ListExecutionsRequest
import software.amazon.awssdk.services.sfn.model.ListExecutionsResponse
import software.amazon.awssdk.services.sfn.model.StartExecutionRequest
import software.amazon.awssdk.services.sfn.model.StartExecutionResponse
import java.util.concurrent.CompletableFuture

class SfnExtensionsTest {

    private companion object {
        const val STATE_MACHINE_ARN = "arn:aws:states:ap-northeast-2:123456789012:stateMachine:orders"
        const val OTHER_STATE_MACHINE_ARN = "arn:aws:states:ap-northeast-2:123456789012:stateMachine:payments"
        const val EXECUTION_ARN = "arn:aws:states:ap-northeast-2:123456789012:execution:orders:order-1"
        const val MAP_RUN_ARN = "arn:aws:states:ap-northeast-2:123456789012:mapRun:orders:map-1"
    }

    @Test
    fun `sync start preserves raw response and exact request`() {
        val client = mockk<SfnClient>()
        val expected = StartExecutionResponse.builder().executionArn(EXECUTION_ARN).build()
        every { client.startExecution(any<StartExecutionRequest>()) } returns expected

        val result = client.startExecution(STATE_MACHINE_ARN, input = "{\"id\":1}") {
            name("order-1")
        }

        result shouldBeSameInstanceAs expected
        verify(exactly = 1) {
            client.startExecution(match<StartExecutionRequest> { request ->
                request.stateMachineArn() == STATE_MACHINE_ARN &&
                    request.name() == "order-1" &&
                    request.input() == "{\"id\":1}"
            })
        }
    }

    @Test
    fun `sync state machine list pins source before SDK call`() {
        val client = mockk<SfnClient>()
        val expected = ListExecutionsResponse.builder().build()
        every { client.listExecutions(any<ListExecutionsRequest>()) } returns expected

        val result = client.listExecutionsByStateMachine(
            stateMachineArn = STATE_MACHINE_ARN,
            statusFilter = ExecutionStatus.RUNNING,
            maxResults = 100,
            nextToken = "next-page",
        )

        result shouldBeSameInstanceAs expected
        verify(exactly = 1) {
            client.listExecutions(match<ListExecutionsRequest> { request ->
                request.stateMachineArn() == STATE_MACHINE_ARN &&
                    request.mapRunArn() == null &&
                    request.statusFilter() == ExecutionStatus.RUNNING &&
                    request.maxResults() == 100 &&
                    request.nextToken() == "next-page"
            })
        }
    }

    @Test
    fun `state machine list callback source switch fails before SDK call`() {
        val client = mockk<SfnClient>()

        val error = assertFailsWith<IllegalArgumentException> {
            client.listExecutionsByStateMachine(STATE_MACHINE_ARN) {
                stateMachineArn(null)
                mapRunArn(MAP_RUN_ARN)
            }
        }

        error.message shouldBeEqualTo
            "listExecutionsByStateMachine must retain stateMachineArn=$STATE_MACHINE_ARN and must not set mapRunArn; " +
            "actual stateMachineArn=null, mapRunArn=$MAP_RUN_ARN"
        verify(exactly = 0) { client.listExecutions(any<ListExecutionsRequest>()) }
    }

    @Test
    fun `state machine list reports source pin when callback adds map run`() {
        val client = mockk<SfnClient>()

        val error = assertFailsWith<IllegalArgumentException> {
            client.listExecutionsByStateMachine(STATE_MACHINE_ARN) {
                mapRunArn(MAP_RUN_ARN)
            }
        }

        error.message shouldBeEqualTo
            "listExecutionsByStateMachine must retain stateMachineArn=$STATE_MACHINE_ARN and must not set mapRunArn; " +
            "actual stateMachineArn=$STATE_MACHINE_ARN, mapRunArn=$MAP_RUN_ARN"
        verify(exactly = 0) { client.listExecutions(any<ListExecutionsRequest>()) }
    }

    @Test
    fun `state machine list reports dedicated filter validation`() {
        val client = mockk<SfnClient>()

        val error = assertFailsWith<IllegalArgumentException> {
            client.listExecutionsByStateMachine(
                stateMachineArn = STATE_MACHINE_ARN,
                statusFilter = ExecutionStatus.PENDING_REDRIVE,
            )
        }

        error.message shouldBeEqualTo
            "listExecutionsByStateMachine does not support PENDING_REDRIVE or redriveFilter; " +
            "actual statusFilter=PENDING_REDRIVE, redriveFilter=null"
        verify(exactly = 0) { client.listExecutions(any<ListExecutionsRequest>()) }
    }

    @Test
    fun `map run list callback source switch reports actual filters`() {
        val client = mockk<SfnClient>()

        val error = assertFailsWith<IllegalArgumentException> {
            client.listExecutionsByMapRun(MAP_RUN_ARN) {
                mapRunArn(null)
                stateMachineArn(STATE_MACHINE_ARN)
            }
        }

        error.message shouldBeEqualTo
            "listExecutionsByMapRun must retain mapRunArn=$MAP_RUN_ARN and must not set stateMachineArn; " +
            "actual mapRunArn=null, stateMachineArn=$STATE_MACHINE_ARN, " +
            "statusFilter=null, redriveFilter=null"
        verify(exactly = 0) { client.listExecutions(any<ListExecutionsRequest>()) }
    }

    @Test
    fun `map run list reports source pin when callback adds state machine`() {
        val client = mockk<SfnClient>()

        val error = assertFailsWith<IllegalArgumentException> {
            client.listExecutionsByMapRun(MAP_RUN_ARN) {
                stateMachineArn(STATE_MACHINE_ARN)
            }
        }

        error.message shouldBeEqualTo
            "listExecutionsByMapRun must retain mapRunArn=$MAP_RUN_ARN and must not set stateMachineArn; " +
            "actual mapRunArn=$MAP_RUN_ARN, stateMachineArn=$STATE_MACHINE_ARN, " +
            "statusFilter=null, redriveFilter=null"
        verify(exactly = 0) { client.listExecutions(any<ListExecutionsRequest>()) }
    }

    @Test
    fun `async future preserves raw response`() {
        val client = mockk<SfnAsyncClient>()
        val expected = StartExecutionResponse.builder().executionArn(EXECUTION_ARN).build()
        every { client.startExecution(any<StartExecutionRequest>()) } returns
            CompletableFuture.completedFuture(expected)

        val result = client.startExecutionAsync(STATE_MACHINE_ARN)

        result.get() shouldBeSameInstanceAs expected
        verify(exactly = 1) { client.startExecution(any<StartExecutionRequest>()) }
    }

    @Test
    fun `async coroutine awaits response and propagates cancellation`() = runTest {
        val client = mockk<SfnAsyncClient>()
        val expected = DescribeExecutionResponse.builder().status(ExecutionStatus.RUNNING).build()
        every { client.describeExecution(any<DescribeExecutionRequest>()) } returns
            CompletableFuture.completedFuture(expected)

        client.describeExecution(EXECUTION_ARN) shouldBeSameInstanceAs expected

        val cancelled = CompletableFuture<StartExecutionResponse>()
        cancelled.completeExceptionally(CancellationException("cancelled"))
        every { client.startExecution(any<StartExecutionRequest>()) } returns cancelled

        assertFailsWith<CancellationException> { client.startExecution(STATE_MACHINE_ARN) }
        verify(exactly = 1) { client.describeExecution(any<DescribeExecutionRequest>()) }
        verify(exactly = 1) { client.startExecution(any<StartExecutionRequest>()) }
    }
}
