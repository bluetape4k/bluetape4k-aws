package io.bluetape4k.aws.kotlin.sfn

import aws.sdk.kotlin.services.sfn.SfnClient
import aws.sdk.kotlin.services.sfn.model.DescribeExecutionRequest
import aws.sdk.kotlin.services.sfn.model.DescribeExecutionResponse
import aws.sdk.kotlin.services.sfn.model.ExecutionRedriveFilter
import aws.sdk.kotlin.services.sfn.model.ExecutionStatus
import aws.sdk.kotlin.services.sfn.model.IncludedData
import aws.sdk.kotlin.services.sfn.model.ListExecutionsRequest
import aws.sdk.kotlin.services.sfn.model.ListExecutionsResponse
import aws.sdk.kotlin.services.sfn.model.StartExecutionRequest
import aws.sdk.kotlin.services.sfn.model.StartExecutionResponse
import aws.sdk.kotlin.services.sfn.model.StopExecutionRequest
import aws.sdk.kotlin.services.sfn.model.StopExecutionResponse
import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeSameInstanceAs
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import aws.smithy.kotlin.runtime.time.Instant

class SfnExtensionsTest {

    private companion object {
        const val STATE_MACHINE_ARN = "arn:aws:states:ap-northeast-2:123456789012:stateMachine:orders"
        const val EXECUTION_ARN = "arn:aws:states:ap-northeast-2:123456789012:execution:orders:run-1"
        const val MAP_RUN_ARN = "arn:aws:states:ap-northeast-2:123456789012:mapRun:orders/map-1"

        val INSTANT: Instant = Instant.fromEpochSeconds(0)
    }

    private val client = mockk<SfnClient>()

    @BeforeEach
    fun resetMocks() {
        clearMocks(client)
    }

    @Test
    fun `startExecution delegates once and preserves raw response`() = kotlinx.coroutines.test.runTest {
        val expected = StartExecutionResponse {
            executionArn = EXECUTION_ARN
            startDate = INSTANT
        }
        coEvery { client.startExecution(any<StartExecutionRequest>()) } returns expected

        val result = client.startExecution(
            stateMachineArn = STATE_MACHINE_ARN,
            name = "run-1",
            input = "{\"orderId\":1}",
        )

        result shouldBeSameInstanceAs expected
        result.executionArn shouldBeEqualTo EXECUTION_ARN
        coVerify(exactly = 1) { client.startExecution(any<StartExecutionRequest>()) }
    }

    @Test
    fun `stopExecution and describeExecution preserve callback fields`() = kotlinx.coroutines.test.runTest {
        val stop = StopExecutionResponse { stopDate = INSTANT }
        val describe = DescribeExecutionResponse {
            status = ExecutionStatus.Succeeded
            startDate = INSTANT
            executionArn = EXECUTION_ARN
            stateMachineArn = STATE_MACHINE_ARN
        }
        coEvery { client.stopExecution(any<StopExecutionRequest>()) } returns stop
        coEvery { client.describeExecution(any<DescribeExecutionRequest>()) } returns describe

        val stopResult = client.stopExecution(EXECUTION_ARN, error = "error", cause = "cause") {
            error = "callback-error"
        }
        val describeResult = client.describeExecution(EXECUTION_ARN) {
            includedData = IncludedData.MetadataOnly
        }

        stopResult shouldBeSameInstanceAs stop
        describeResult shouldBeSameInstanceAs describe
        coVerify(exactly = 1) { client.stopExecution(any<StopExecutionRequest>()) }
        coVerify(exactly = 1) { client.describeExecution(any<DescribeExecutionRequest>()) }
    }

    @Test
    fun `state machine list helper forwards filters and returns raw response`() = kotlinx.coroutines.test.runTest {
        val expected = ListExecutionsResponse {
            executions = emptyList()
            nextToken = "next-page"
        }
        coEvery { client.listExecutions(any<ListExecutionsRequest>()) } returns expected

        val result = client.listExecutionsByStateMachine(
            stateMachineArn = STATE_MACHINE_ARN,
            statusFilter = ExecutionStatus.Failed,
            maxResults = 100,
            nextToken = "page-1",
        )

        result shouldBeSameInstanceAs expected
        result.nextToken shouldBeEqualTo "next-page"
        coVerify(exactly = 1) { client.listExecutions(any<ListExecutionsRequest>()) }
    }

    @Test
    fun `map run list helper forwards redrive filter`() = kotlinx.coroutines.test.runTest {
        val expected = ListExecutionsResponse { executions = emptyList() }
        coEvery { client.listExecutions(any<ListExecutionsRequest>()) } returns expected

        val result = client.listExecutionsByMapRun(
            mapRunArn = MAP_RUN_ARN,
            statusFilter = ExecutionStatus.Failed,
            redriveFilter = ExecutionRedriveFilter.Redriven,
        )

        result shouldBeSameInstanceAs expected
        coVerify(exactly = 1) { client.listExecutions(any<ListExecutionsRequest>()) }
    }

    @Test
    fun `state machine list helper reports callback source switch`() = kotlinx.coroutines.test.runTest {
        val error = assertFailsWith<IllegalArgumentException> {
            client.listExecutionsByStateMachine(STATE_MACHINE_ARN) {
                stateMachineArn = null
                mapRunArn = MAP_RUN_ARN
            }
        }

        error.message shouldBeEqualTo
            "listExecutionsByStateMachine must retain stateMachineArn=$STATE_MACHINE_ARN and must not set mapRunArn; " +
            "actual stateMachineArn=null, mapRunArn=$MAP_RUN_ARN"
        coVerify(exactly = 0) { client.listExecutions(any<ListExecutionsRequest>()) }
    }

    @Test
    fun `map run list helper reports callback source switch`() = kotlinx.coroutines.test.runTest {
        val error = assertFailsWith<IllegalArgumentException> {
            client.listExecutionsByMapRun(MAP_RUN_ARN) {
                mapRunArn = null
                stateMachineArn = STATE_MACHINE_ARN
            }
        }

        error.message shouldBeEqualTo
            "listExecutionsByMapRun must retain mapRunArn=$MAP_RUN_ARN and must not set stateMachineArn; " +
            "actual mapRunArn=null, stateMachineArn=$STATE_MACHINE_ARN, " +
            "statusFilter=null, redriveFilter=null"
        coVerify(exactly = 0) { client.listExecutions(any<ListExecutionsRequest>()) }
    }
}
