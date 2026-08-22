package io.bluetape4k.aws.kotlin.sfn.model

import aws.sdk.kotlin.services.sfn.model.ExecutionRedriveFilter
import aws.sdk.kotlin.services.sfn.model.ExecutionStatus
import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import org.junit.jupiter.api.Test

class SfnRequestSupportTest {

    private companion object {
        const val STATE_MACHINE_ARN = "arn:aws:states:ap-northeast-2:123456789012:stateMachine:orders"
        const val OTHER_STATE_MACHINE_ARN = "arn:aws:states:ap-northeast-2:123456789012:stateMachine:payments"
        const val EXECUTION_ARN = "arn:aws:states:ap-northeast-2:123456789012:execution:orders:run-1"
        const val OTHER_EXECUTION_ARN = "arn:aws:states:ap-northeast-2:123456789012:execution:orders:run-2"
        const val MAP_RUN_ARN = "arn:aws:states:ap-northeast-2:123456789012:mapRun:orders/map-1"
    }

    @Test
    fun `null input is normalized to an empty JSON object`() {
        startExecutionRequestOf(STATE_MACHINE_ARN).input shouldBeEqualTo "{}"
    }

    @Test
    fun `blank input is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            startExecutionRequestOf(STATE_MACHINE_ARN, input = "")
        }
        assertFailsWith<IllegalArgumentException> {
            startExecutionRequestOf(STATE_MACHINE_ARN, input = "   ")
        }
    }

    @Test
    fun `callback null input is normalized after callback`() {
        val request = startExecutionRequestOf(STATE_MACHINE_ARN, input = "{\"id\":1}") {
            input = null
        }

        request.input shouldBeEqualTo "{}"
    }

    @Test
    fun `callback blank input is rejected after callback`() {
        assertFailsWith<IllegalArgumentException> {
            startExecutionRequestOf(STATE_MACHINE_ARN, input = "{\"id\":1}") {
                input = " "
            }
        }
    }

    @Test
    fun `required arns reject blank values`() {
        assertFailsWith<IllegalArgumentException> {
            startExecutionRequestOf(" ")
        }
        assertFailsWith<IllegalArgumentException> {
            stopExecutionRequestOf(" ")
        }
        assertFailsWith<IllegalArgumentException> {
            describeExecutionRequestOf(" ")
        }
    }

    @Test
    fun `execution name accepts one through eighty characters`() {
        startExecutionRequestOf(STATE_MACHINE_ARN, name = "a").name shouldBeEqualTo "a"
        startExecutionRequestOf(STATE_MACHINE_ARN, name = "a".repeat(80)).name shouldBeEqualTo "a".repeat(80)
        assertFailsWith<IllegalArgumentException> {
            startExecutionRequestOf(STATE_MACHINE_ARN, name = "a".repeat(81))
        }
    }

    @Test
    fun `valid callback values override explicit values`() {
        val request = startExecutionRequestOf(
            stateMachineArn = STATE_MACHINE_ARN,
            name = "initial",
            input = "{\"initial\":true}",
            traceHeader = "initial-trace",
        ) {
            stateMachineArn = OTHER_STATE_MACHINE_ARN
            name = "override"
            input = "{\"override\":true}"
            traceHeader = "override-trace"
        }

        request.stateMachineArn shouldBeEqualTo OTHER_STATE_MACHINE_ARN
        request.name shouldBeEqualTo "override"
        request.input shouldBeEqualTo "{\"override\":true}"
        request.traceHeader shouldBeEqualTo "override-trace"
    }

    @Test
    fun `raw JSON input is preserved without reserialization`() {
        val input = " { \"id\": 1, \"nested\": [true, null] } "

        startExecutionRequestOf(STATE_MACHINE_ARN, input = input).input shouldBeEqualTo input
    }

    @Test
    fun `stop request leaves optional error and cause unset`() {
        val request = stopExecutionRequestOf(EXECUTION_ARN)

        request.error shouldBeEqualTo null
        request.cause shouldBeEqualTo null
    }

    @Test
    fun `list executions requires exactly one source`() {
        assertFailsWith<IllegalArgumentException> {
            listExecutionsRequestOf()
        }
        assertFailsWith<IllegalArgumentException> {
            listExecutionsRequestOf(stateMachineArn = STATE_MACHINE_ARN, mapRunArn = MAP_RUN_ARN)
        }
    }

    @Test
    fun `state machine list rejects pending redrive`() {
        assertFailsWith<IllegalArgumentException> {
            listExecutionsRequestOf(
                stateMachineArn = STATE_MACHINE_ARN,
                statusFilter = ExecutionStatus.PendingRedrive,
            )
        }
    }

    @Test
    fun `state machine list rejects redrive filter`() {
        assertFailsWith<IllegalArgumentException> {
            listExecutionsRequestOf(
                stateMachineArn = STATE_MACHINE_ARN,
                redriveFilter = ExecutionRedriveFilter.Redriven,
            )
        }
    }

    @Test
    fun `max results accepts zero through one thousand`() {
        listExecutionsRequestOf(stateMachineArn = STATE_MACHINE_ARN, maxResults = 0).maxResults shouldBeEqualTo 0
        listExecutionsRequestOf(
            stateMachineArn = STATE_MACHINE_ARN,
            maxResults = 1_000,
        ).maxResults shouldBeEqualTo 1_000
        assertFailsWith<IllegalArgumentException> {
            listExecutionsRequestOf(stateMachineArn = STATE_MACHINE_ARN, maxResults = -1)
        }
        assertFailsWith<IllegalArgumentException> {
            listExecutionsRequestOf(stateMachineArn = STATE_MACHINE_ARN, maxResults = 1_001)
        }
    }

    @Test
    fun `blank next token is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            listExecutionsRequestOf(stateMachineArn = STATE_MACHINE_ARN, nextToken = " ")
        }
    }

    @Test
    fun `next page retains source and filters while changing only token`() {
        val request = listExecutionsRequestOf(
            mapRunArn = MAP_RUN_ARN,
            statusFilter = ExecutionStatus.Failed,
            redriveFilter = ExecutionRedriveFilter.NotRedriven,
            maxResults = 100,
            nextToken = "next-page",
        )

        request.stateMachineArn shouldBeEqualTo null
        request.mapRunArn shouldBeEqualTo MAP_RUN_ARN
        request.statusFilter shouldBeEqualTo ExecutionStatus.Failed
        request.redriveFilter shouldBeEqualTo ExecutionRedriveFilter.NotRedriven
        request.maxResults shouldBeEqualTo 100
        request.nextToken shouldBeEqualTo "next-page"
    }

    @Test
    fun `callback can override execution and describe fields`() {
        val stop = stopExecutionRequestOf(EXECUTION_ARN, error = "initial", cause = "initial-cause") {
            executionArn = OTHER_EXECUTION_ARN
            error = "override"
            cause = "override-cause"
        }
        val describe = describeExecutionRequestOf(EXECUTION_ARN) {
            executionArn = OTHER_EXECUTION_ARN
        }

        stop.executionArn shouldBeEqualTo OTHER_EXECUTION_ARN
        stop.error shouldBeEqualTo "override"
        stop.cause shouldBeEqualTo "override-cause"
        describe.executionArn shouldBeEqualTo OTHER_EXECUTION_ARN
    }
}
