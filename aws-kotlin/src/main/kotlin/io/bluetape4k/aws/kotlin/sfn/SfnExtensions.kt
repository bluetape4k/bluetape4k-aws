package io.bluetape4k.aws.kotlin.sfn

import aws.sdk.kotlin.services.sfn.SfnClient
import aws.sdk.kotlin.services.sfn.model.DescribeExecutionRequest
import aws.sdk.kotlin.services.sfn.model.DescribeExecutionResponse
import aws.sdk.kotlin.services.sfn.model.ExecutionRedriveFilter
import aws.sdk.kotlin.services.sfn.model.ExecutionStatus
import aws.sdk.kotlin.services.sfn.model.ListExecutionsRequest
import aws.sdk.kotlin.services.sfn.model.ListExecutionsResponse
import aws.sdk.kotlin.services.sfn.model.StartExecutionResponse
import aws.sdk.kotlin.services.sfn.model.StopExecutionResponse
import io.bluetape4k.aws.kotlin.sfn.model.describeExecutionRequestOf
import io.bluetape4k.aws.kotlin.sfn.model.listExecutionsRequestOf
import io.bluetape4k.aws.kotlin.sfn.model.startExecutionRequestOf
import io.bluetape4k.aws.kotlin.sfn.model.stopExecutionRequestOf

/** 실행을 시작하고 AWS SDK raw response를 반환합니다. */
suspend fun SfnClient.startExecution(
    stateMachineArn: String,
    name: String? = null,
    input: String? = null,
    traceHeader: String? = null,
    builder: aws.sdk.kotlin.services.sfn.model.StartExecutionRequest.Builder.() -> Unit = {},
): StartExecutionResponse = startExecution(
    startExecutionRequestOf(stateMachineArn, name, input, traceHeader, builder),
)

/** 실행을 중지하고 AWS SDK raw response를 반환합니다. */
suspend fun SfnClient.stopExecution(
    executionArn: String,
    error: String? = null,
    cause: String? = null,
    builder: aws.sdk.kotlin.services.sfn.model.StopExecutionRequest.Builder.() -> Unit = {},
): StopExecutionResponse = stopExecution(
    stopExecutionRequestOf(executionArn, error, cause, builder),
)

/** 실행 상태를 조회하고 AWS SDK raw response를 반환합니다. */
suspend fun SfnClient.describeExecution(
    executionArn: String,
    builder: DescribeExecutionRequest.Builder.() -> Unit = {},
): DescribeExecutionResponse = describeExecution(
    describeExecutionRequestOf(executionArn, builder),
)

/** 상태 머신에 속한 실행을 한 페이지 조회합니다. */
suspend fun SfnClient.listExecutionsByStateMachine(
    stateMachineArn: String,
    statusFilter: ExecutionStatus? = null,
    maxResults: Int? = null,
    nextToken: String? = null,
    builder: ListExecutionsRequest.Builder.() -> Unit = {},
): ListExecutionsResponse {
    val request = listExecutionsRequestOf(
        stateMachineArn = stateMachineArn,
        statusFilter = statusFilter,
        maxResults = maxResults,
        nextToken = nextToken,
        builder = builder,
    )
    require(request.stateMachineArn == stateMachineArn && request.mapRunArn == null) {
        "listExecutionsByStateMachine must retain stateMachineArn=$stateMachineArn and must not set mapRunArn; " +
            "actual stateMachineArn=${request.stateMachineArn}, mapRunArn=${request.mapRunArn}"
    }
    require(request.statusFilter != ExecutionStatus.PendingRedrive && request.redriveFilter == null) {
        "listExecutionsByStateMachine does not support PENDING_REDRIVE or redriveFilter; " +
            "actual statusFilter=${request.statusFilter}, redriveFilter=${request.redriveFilter}"
    }
    return listExecutions(request)
}

/** Map Run에 속한 실행을 한 페이지 조회합니다. */
suspend fun SfnClient.listExecutionsByMapRun(
    mapRunArn: String,
    statusFilter: ExecutionStatus? = null,
    redriveFilter: ExecutionRedriveFilter? = null,
    maxResults: Int? = null,
    nextToken: String? = null,
    builder: ListExecutionsRequest.Builder.() -> Unit = {},
): ListExecutionsResponse {
    val request = listExecutionsRequestOf(
        mapRunArn = mapRunArn,
        statusFilter = statusFilter,
        redriveFilter = redriveFilter,
        maxResults = maxResults,
        nextToken = nextToken,
        builder = builder,
    )
    require(request.mapRunArn == mapRunArn && request.stateMachineArn == null) {
        "listExecutionsByMapRun must retain mapRunArn=$mapRunArn and must not set stateMachineArn; " +
            "actual mapRunArn=${request.mapRunArn}, stateMachineArn=${request.stateMachineArn}, " +
            "statusFilter=${request.statusFilter}, redriveFilter=${request.redriveFilter}"
    }
    return listExecutions(request)
}
