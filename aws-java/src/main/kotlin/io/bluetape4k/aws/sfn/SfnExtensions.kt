@file:Suppress("TooManyFunctions")

package io.bluetape4k.aws.sfn

import io.bluetape4k.aws.sfn.model.describeExecutionRequestOf
import io.bluetape4k.aws.sfn.model.buildListExecutionsRequest
import io.bluetape4k.aws.sfn.model.startExecutionRequestOf
import io.bluetape4k.aws.sfn.model.stopExecutionRequestOf
import io.bluetape4k.aws.sfn.model.validateCommon
import kotlinx.coroutines.future.await
import software.amazon.awssdk.services.sfn.SfnAsyncClient
import software.amazon.awssdk.services.sfn.SfnClient
import software.amazon.awssdk.services.sfn.model.DescribeExecutionRequest
import software.amazon.awssdk.services.sfn.model.DescribeExecutionResponse
import software.amazon.awssdk.services.sfn.model.ExecutionRedriveFilter
import software.amazon.awssdk.services.sfn.model.ExecutionStatus
import software.amazon.awssdk.services.sfn.model.ListExecutionsRequest
import software.amazon.awssdk.services.sfn.model.ListExecutionsResponse
import software.amazon.awssdk.services.sfn.model.StartExecutionRequest
import software.amazon.awssdk.services.sfn.model.StartExecutionResponse
import software.amazon.awssdk.services.sfn.model.StopExecutionRequest
import software.amazon.awssdk.services.sfn.model.StopExecutionResponse
import java.util.concurrent.CompletableFuture

/** [SfnClient]로 실행을 시작하고 AWS 원본 응답을 반환합니다. */
fun SfnClient.startExecution(
    stateMachineArn: String,
    name: String? = null,
    input: String? = null,
    traceHeader: String? = null,
    builder: StartExecutionRequest.Builder.() -> Unit = {},
): StartExecutionResponse = startExecution(
    startExecutionRequestOf(stateMachineArn, name, input, traceHeader, builder),
)

/** [SfnClient]로 실행을 중지하고 AWS 원본 응답을 반환합니다. */
fun SfnClient.stopExecution(
    executionArn: String,
    error: String? = null,
    cause: String? = null,
    builder: StopExecutionRequest.Builder.() -> Unit = {},
): StopExecutionResponse = stopExecution(stopExecutionRequestOf(executionArn, error, cause, builder))

/** [SfnClient]로 실행 상세를 조회하고 AWS 원본 응답을 반환합니다. */
fun SfnClient.describeExecution(
    executionArn: String,
    builder: DescribeExecutionRequest.Builder.() -> Unit = {},
): DescribeExecutionResponse = describeExecution(describeExecutionRequestOf(executionArn, builder))

/** state machine ARN을 기준으로 실행 목록을 조회합니다. */
fun SfnClient.listExecutionsByStateMachine(
    stateMachineArn: String,
    statusFilter: ExecutionStatus? = null,
    maxResults: Int? = null,
    nextToken: String? = null,
    builder: ListExecutionsRequest.Builder.() -> Unit = {},
): ListExecutionsResponse {
    val request = buildListExecutionsRequest(
        stateMachineArn = stateMachineArn,
        mapRunArn = null,
        statusFilter = statusFilter,
        redriveFilter = null,
        maxResults = maxResults,
        nextToken = nextToken,
        builder = builder,
    )
    request.requireStateMachineSource(stateMachineArn)
    require(request.statusFilter() != ExecutionStatus.PENDING_REDRIVE && request.redriveFilter() == null) {
        "listExecutionsByStateMachine does not support PENDING_REDRIVE or redriveFilter; " +
            "actual statusFilter=${request.statusFilter()}, redriveFilter=${request.redriveFilter()}"
    }
    request.validateCommon()
    return listExecutions(request)
}

/** Map Run ARN을 기준으로 실행 목록을 조회합니다. */
fun SfnClient.listExecutionsByMapRun(
    mapRunArn: String,
    statusFilter: ExecutionStatus? = null,
    redriveFilter: ExecutionRedriveFilter? = null,
    maxResults: Int? = null,
    nextToken: String? = null,
    builder: ListExecutionsRequest.Builder.() -> Unit = {},
): ListExecutionsResponse {
    val request = buildListExecutionsRequest(
        stateMachineArn = null,
        mapRunArn = mapRunArn,
        statusFilter = statusFilter,
        redriveFilter = redriveFilter,
        maxResults = maxResults,
        nextToken = nextToken,
        builder = builder,
    )
    request.requireMapRunSource(mapRunArn)
    request.validateCommon()
    return listExecutions(request)
}

/** [SfnAsyncClient]의 실행 시작 요청을 [CompletableFuture]로 반환합니다. */
fun SfnAsyncClient.startExecutionAsync(
    stateMachineArn: String,
    name: String? = null,
    input: String? = null,
    traceHeader: String? = null,
    builder: StartExecutionRequest.Builder.() -> Unit = {},
): CompletableFuture<StartExecutionResponse> = startExecution(
    startExecutionRequestOf(stateMachineArn, name, input, traceHeader, builder),
)

/** [SfnAsyncClient]의 실행 중지 요청을 [CompletableFuture]로 반환합니다. */
fun SfnAsyncClient.stopExecutionAsync(
    executionArn: String,
    error: String? = null,
    cause: String? = null,
    builder: StopExecutionRequest.Builder.() -> Unit = {},
): CompletableFuture<StopExecutionResponse> = stopExecution(stopExecutionRequestOf(executionArn, error, cause, builder))

/** [SfnAsyncClient]의 실행 상세 조회를 [CompletableFuture]로 반환합니다. */
fun SfnAsyncClient.describeExecutionAsync(
    executionArn: String,
    builder: DescribeExecutionRequest.Builder.() -> Unit = {},
): CompletableFuture<DescribeExecutionResponse> = describeExecution(describeExecutionRequestOf(executionArn, builder))

/** state machine ARN 기준 실행 목록 조회를 [CompletableFuture]로 반환합니다. */
fun SfnAsyncClient.listExecutionsByStateMachineAsync(
    stateMachineArn: String,
    statusFilter: ExecutionStatus? = null,
    maxResults: Int? = null,
    nextToken: String? = null,
    builder: ListExecutionsRequest.Builder.() -> Unit = {},
): CompletableFuture<ListExecutionsResponse> {
    val request = buildListExecutionsRequest(
        stateMachineArn = stateMachineArn,
        mapRunArn = null,
        statusFilter = statusFilter,
        redriveFilter = null,
        maxResults = maxResults,
        nextToken = nextToken,
        builder = builder,
    )
    request.requireStateMachineSource(stateMachineArn)
    require(request.statusFilter() != ExecutionStatus.PENDING_REDRIVE && request.redriveFilter() == null) {
        "listExecutionsByStateMachine does not support PENDING_REDRIVE or redriveFilter; " +
            "actual statusFilter=${request.statusFilter()}, redriveFilter=${request.redriveFilter()}"
    }
    request.validateCommon()
    return listExecutions(request)
}

/** Map Run ARN 기준 실행 목록 조회를 [CompletableFuture]로 반환합니다. */
fun SfnAsyncClient.listExecutionsByMapRunAsync(
    mapRunArn: String,
    statusFilter: ExecutionStatus? = null,
    redriveFilter: ExecutionRedriveFilter? = null,
    maxResults: Int? = null,
    nextToken: String? = null,
    builder: ListExecutionsRequest.Builder.() -> Unit = {},
): CompletableFuture<ListExecutionsResponse> {
    val request = buildListExecutionsRequest(
        stateMachineArn = null,
        mapRunArn = mapRunArn,
        statusFilter = statusFilter,
        redriveFilter = redriveFilter,
        maxResults = maxResults,
        nextToken = nextToken,
        builder = builder,
    )
    request.requireMapRunSource(mapRunArn)
    request.validateCommon()
    return listExecutions(request)
}

/** [startExecutionAsync]를 기다리는 coroutine helper입니다. */
suspend fun SfnAsyncClient.startExecution(
    stateMachineArn: String,
    name: String? = null,
    input: String? = null,
    traceHeader: String? = null,
    builder: StartExecutionRequest.Builder.() -> Unit = {},
): StartExecutionResponse = startExecutionAsync(stateMachineArn, name, input, traceHeader, builder).await()

/** [stopExecutionAsync]를 기다리는 coroutine helper입니다. */
suspend fun SfnAsyncClient.stopExecution(
    executionArn: String,
    error: String? = null,
    cause: String? = null,
    builder: StopExecutionRequest.Builder.() -> Unit = {},
): StopExecutionResponse = stopExecutionAsync(executionArn, error, cause, builder).await()

/** [describeExecutionAsync]를 기다리는 coroutine helper입니다. */
suspend fun SfnAsyncClient.describeExecution(
    executionArn: String,
    builder: DescribeExecutionRequest.Builder.() -> Unit = {},
): DescribeExecutionResponse = describeExecutionAsync(executionArn, builder).await()

/** state machine ARN 기준 목록 조회를 기다리는 coroutine helper입니다. */
suspend fun SfnAsyncClient.listExecutionsByStateMachine(
    stateMachineArn: String,
    statusFilter: ExecutionStatus? = null,
    maxResults: Int? = null,
    nextToken: String? = null,
    builder: ListExecutionsRequest.Builder.() -> Unit = {},
): ListExecutionsResponse = listExecutionsByStateMachineAsync(
    stateMachineArn,
    statusFilter,
    maxResults,
    nextToken,
    builder,
).await()

/** Map Run ARN 기준 목록 조회를 기다리는 coroutine helper입니다. */
suspend fun SfnAsyncClient.listExecutionsByMapRun(
    mapRunArn: String,
    statusFilter: ExecutionStatus? = null,
    redriveFilter: ExecutionRedriveFilter? = null,
    maxResults: Int? = null,
    nextToken: String? = null,
    builder: ListExecutionsRequest.Builder.() -> Unit = {},
): ListExecutionsResponse = listExecutionsByMapRunAsync(
    mapRunArn,
    statusFilter,
    redriveFilter,
    maxResults,
    nextToken,
    builder,
).await()

private fun ListExecutionsRequest.requireStateMachineSource(expectedArn: String) {
    require(stateMachineArn() == expectedArn && mapRunArn() == null) {
        "listExecutionsByStateMachine must retain stateMachineArn=$expectedArn and must not set mapRunArn; " +
            "actual stateMachineArn=${stateMachineArn()}, mapRunArn=${mapRunArn()}"
    }
}

private fun ListExecutionsRequest.requireMapRunSource(expectedArn: String) {
    require(mapRunArn() == expectedArn && stateMachineArn() == null) {
        "listExecutionsByMapRun must retain mapRunArn=$expectedArn and must not set stateMachineArn; " +
            "actual mapRunArn=${mapRunArn()}, stateMachineArn=${stateMachineArn()}, " +
            "statusFilter=${statusFilter()}, redriveFilter=${redriveFilter()}"
    }
}
