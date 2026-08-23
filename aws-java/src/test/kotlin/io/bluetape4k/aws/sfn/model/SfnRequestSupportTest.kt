package io.bluetape4k.aws.sfn.model

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import org.junit.jupiter.api.Test
import software.amazon.awssdk.services.sfn.model.ExecutionRedriveFilter
import software.amazon.awssdk.services.sfn.model.ExecutionStatus

class SfnRequestSupportTest {

    private companion object {
        const val STATE_MACHINE_ARN = "arn:aws:states:ap-northeast-2:123456789012:stateMachine:orders"
        const val OTHER_STATE_MACHINE_ARN = "arn:aws:states:ap-northeast-2:123456789012:stateMachine:payments"
        const val EXECUTION_ARN = "arn:aws:states:ap-northeast-2:123456789012:execution:orders:order-1"
        const val MAP_RUN_ARN = "arn:aws:states:ap-northeast-2:123456789012:mapRun:orders:map-1"
    }

    @Test
    fun `null input is normalized to empty JSON`() {
        startExecutionRequestOf(STATE_MACHINE_ARN).input() shouldBeEqualTo "{}"
    }

    @Test
    fun `blank input is rejected`() {
        assertFailsWith<IllegalArgumentException> { startExecutionRequestOf(STATE_MACHINE_ARN, input = "") }
        assertFailsWith<IllegalArgumentException> { startExecutionRequestOf(STATE_MACHINE_ARN, input = "   ") }
    }

    @Test
    fun `callback null input is normalized to empty JSON`() {
        val request = startExecutionRequestOf(STATE_MACHINE_ARN, input = "{\"id\":1}") {
            input(null)
        }

        request.input() shouldBeEqualTo "{}"
    }

    @Test
    fun `callback blank input is rejected after callback`() {
        assertFailsWith<IllegalArgumentException> {
            startExecutionRequestOf(STATE_MACHINE_ARN) { input(" ") }
        }
    }

    @Test
    fun `required ARNs are validated`() {
        assertFailsWith<IllegalArgumentException> { startExecutionRequestOf("") }
        assertFailsWith<IllegalArgumentException> { stopExecutionRequestOf("") }
        assertFailsWith<IllegalArgumentException> { describeExecutionRequestOf("") }
    }

    @Test
    fun `name accepts one through eighty characters and rejects eighty one`() {
        startExecutionRequestOf(STATE_MACHINE_ARN, name = "a").name() shouldBeEqualTo "a"
        startExecutionRequestOf(STATE_MACHINE_ARN, name = "a".repeat(80)).name().length shouldBeEqualTo 80
        assertFailsWith<IllegalArgumentException> {
            startExecutionRequestOf(STATE_MACHINE_ARN, name = "a".repeat(81))
        }
    }

    @Test
    fun `valid callback overrides explicit values`() {
        val request = startExecutionRequestOf(
            stateMachineArn = STATE_MACHINE_ARN,
            name = "initial",
            input = "{\"initial\":true}",
            traceHeader = "initial-trace",
        ) {
            stateMachineArn(OTHER_STATE_MACHINE_ARN)
            name("callback")
            input("{\"callback\":true}")
            traceHeader("callback-trace")
        }

        request.stateMachineArn() shouldBeEqualTo OTHER_STATE_MACHINE_ARN
        request.name() shouldBeEqualTo "callback"
        request.input() shouldBeEqualTo "{\"callback\":true}"
        request.traceHeader() shouldBeEqualTo "callback-trace"
    }

    @Test
    fun `raw input is preserved`() {
        val input = " {\"id\":1,\"items\":[1,2]} "

        startExecutionRequestOf(STATE_MACHINE_ARN, input = input).input() shouldBeEqualTo input
    }

    @Test
    fun `stop request omits nullable KMS fields`() {
        val request = stopExecutionRequestOf(EXECUTION_ARN)

        request.error() shouldBeEqualTo null
        request.cause() shouldBeEqualTo null
    }

    @Test
    fun `list request requires exactly one source`() {
        assertFailsWith<IllegalArgumentException> { listExecutionsRequestOf() }
        assertFailsWith<IllegalArgumentException> {
            listExecutionsRequestOf(stateMachineArn = STATE_MACHINE_ARN, mapRunArn = MAP_RUN_ARN)
        }
    }

    @Test
    fun `state machine list rejects pending redrive and redrive filter`() {
        assertFailsWith<IllegalArgumentException> {
            listExecutionsRequestOf(
                stateMachineArn = STATE_MACHINE_ARN,
                statusFilter = ExecutionStatus.PENDING_REDRIVE,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            listExecutionsRequestOf(
                stateMachineArn = STATE_MACHINE_ARN,
                redriveFilter = ExecutionRedriveFilter.REDRIVEN,
            )
        }
    }

    @Test
    fun `map run list accepts redrive filter and pending redrive`() {
        val request = listExecutionsRequestOf(
            mapRunArn = MAP_RUN_ARN,
            statusFilter = ExecutionStatus.PENDING_REDRIVE,
            redriveFilter = ExecutionRedriveFilter.NOT_REDRIVEN,
        )

        request.mapRunArn() shouldBeEqualTo MAP_RUN_ARN
        request.statusFilter() shouldBeEqualTo ExecutionStatus.PENDING_REDRIVE
        request.redriveFilter() shouldBeEqualTo ExecutionRedriveFilter.NOT_REDRIVEN
    }

    @Test
    fun `list page size and next token are validated`() {
        assertFailsWith<IllegalArgumentException> {
            listExecutionsRequestOf(stateMachineArn = STATE_MACHINE_ARN, maxResults = -1)
        }
        assertFailsWith<IllegalArgumentException> {
            listExecutionsRequestOf(stateMachineArn = STATE_MACHINE_ARN, maxResults = 1001)
        }
        assertFailsWith<IllegalArgumentException> {
            listExecutionsRequestOf(stateMachineArn = STATE_MACHINE_ARN, nextToken = " ")
        }
    }

    @Test
    fun `next page keeps source and filter while changing only token`() {
        val request = listExecutionsRequestOf(
            stateMachineArn = STATE_MACHINE_ARN,
            statusFilter = ExecutionStatus.RUNNING,
            maxResults = 100,
            nextToken = "next-page",
        )

        request.stateMachineArn() shouldBeEqualTo STATE_MACHINE_ARN
        request.mapRunArn() shouldBeEqualTo null
        request.statusFilter() shouldBeEqualTo ExecutionStatus.RUNNING
        request.maxResults() shouldBeEqualTo 100
        request.nextToken() shouldBeEqualTo "next-page"
    }
}
