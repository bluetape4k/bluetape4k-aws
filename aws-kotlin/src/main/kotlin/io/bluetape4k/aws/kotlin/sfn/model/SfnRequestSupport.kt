package io.bluetape4k.aws.kotlin.sfn.model

import aws.sdk.kotlin.services.sfn.model.DescribeExecutionRequest
import aws.sdk.kotlin.services.sfn.model.ExecutionRedriveFilter
import aws.sdk.kotlin.services.sfn.model.ExecutionStatus
import aws.sdk.kotlin.services.sfn.model.ListExecutionsRequest
import aws.sdk.kotlin.services.sfn.model.StartExecutionRequest
import aws.sdk.kotlin.services.sfn.model.StopExecutionRequest
import io.bluetape4k.support.requireInRange
import io.bluetape4k.support.requireNotBlank

@PublishedApi
internal const val MAX_EXECUTION_NAME_LENGTH = 80

@PublishedApi
internal const val MIN_LIST_EXECUTIONS_RESULTS = 0

@PublishedApi
internal const val MAX_LIST_EXECUTIONS_RESULTS = 1_000

/** Step Functions 실행 시작 요청을 구성합니다. */
inline fun startExecutionRequestOf(
    stateMachineArn: String,
    name: String? = null,
    input: String? = null,
    traceHeader: String? = null,
    crossinline builder: StartExecutionRequest.Builder.() -> Unit = {},
): StartExecutionRequest = StartExecutionRequest {
    this.stateMachineArn = stateMachineArn
    this.name = name
    this.input = input
    this.traceHeader = traceHeader
    builder()

    this.stateMachineArn?.requireNotBlank("stateMachineArn")
        ?: throw IllegalArgumentException("stateMachineArn is required")
    this.name?.let {
        require(it.isNotBlank() && it.length <= MAX_EXECUTION_NAME_LENGTH) {
            "name must contain 1..$MAX_EXECUTION_NAME_LENGTH non-blank characters"
        }
    }
    this.input = this.input?.also { it.requireNotBlank("input") } ?: "{}"
}

/** Step Functions 실행 중지 요청을 구성합니다. */
inline fun stopExecutionRequestOf(
    executionArn: String,
    error: String? = null,
    cause: String? = null,
    crossinline builder: StopExecutionRequest.Builder.() -> Unit = {},
): StopExecutionRequest = StopExecutionRequest {
    this.executionArn = executionArn
    this.error = error
    this.cause = cause
    builder()

    this.executionArn?.requireNotBlank("executionArn")
        ?: throw IllegalArgumentException("executionArn is required")
}

/** Step Functions 실행 조회 요청을 구성합니다. */
inline fun describeExecutionRequestOf(
    executionArn: String,
    crossinline builder: DescribeExecutionRequest.Builder.() -> Unit = {},
): DescribeExecutionRequest = DescribeExecutionRequest {
    this.executionArn = executionArn
    builder()

    this.executionArn?.requireNotBlank("executionArn")
        ?: throw IllegalArgumentException("executionArn is required")
}

/** 상태 머신 또는 Map Run 기준의 Step Functions 실행 목록 요청을 구성합니다. */
inline fun listExecutionsRequestOf(
    stateMachineArn: String? = null,
    mapRunArn: String? = null,
    statusFilter: ExecutionStatus? = null,
    redriveFilter: ExecutionRedriveFilter? = null,
    maxResults: Int? = null,
    nextToken: String? = null,
    crossinline builder: ListExecutionsRequest.Builder.() -> Unit = {},
): ListExecutionsRequest = ListExecutionsRequest {
    this.stateMachineArn = stateMachineArn
    this.mapRunArn = mapRunArn
    this.statusFilter = statusFilter
    this.redriveFilter = redriveFilter
    this.maxResults = maxResults
    this.nextToken = nextToken
    builder()

    require((this.stateMachineArn != null) xor (this.mapRunArn != null)) {
        "Exactly one of stateMachineArn or mapRunArn is required"
    }
    this.stateMachineArn?.requireNotBlank("stateMachineArn")
    this.mapRunArn?.requireNotBlank("mapRunArn")
    this.nextToken?.requireNotBlank("nextToken")
    this.maxResults?.requireInRange(MIN_LIST_EXECUTIONS_RESULTS, MAX_LIST_EXECUTIONS_RESULTS, "maxResults")
    require(this.stateMachineArn == null || this.statusFilter != ExecutionStatus.PendingRedrive) {
        "PENDING_REDRIVE requires mapRunArn"
    }
    require(this.stateMachineArn == null || this.redriveFilter == null) {
        "redriveFilter requires mapRunArn"
    }
}
