@file:Suppress("MatchingDeclarationName")

package io.bluetape4k.aws.kotlin.sfn

import aws.sdk.kotlin.services.sfn.SfnClient
import aws.sdk.kotlin.services.sfn.model.DescribeExecutionRequest
import aws.sdk.kotlin.services.sfn.model.DescribeExecutionResponse
import aws.sdk.kotlin.services.sfn.model.ExecutionStatus
import io.bluetape4k.aws.kotlin.sfn.model.describeExecutionRequestOf
import io.bluetape4k.coroutines.flow.extensions.repeat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.transformWhile
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Step Functions 실행 상태 polling 설정입니다. */
data class SfnExecutionPollingOptions(
    val pollInterval: Duration = 1.seconds,
) {
    init {
        require(pollInterval.isFinite() && pollInterval >= 1.seconds) {
            "pollInterval must be finite and at least 1s"
        }
    }
}

/**
 * 주어진 [request]로 실행 상태를 polling하는 cold Flow를 반환합니다.
 *
 * 첫 조회는 즉시 실행하고, `Running` 응답 뒤에는 [SfnExecutionPollingOptions.pollInterval]만큼 기다립니다.
 * terminal 응답은 마지막으로 방출하며, `SdkUnknown`이나 SDK 조회 실패는 그대로 예외로 전파합니다.
 * Flow 수집을 취소해도 execution을 중단하거나 client를 닫지 않습니다.
 *
 * ```kotlin
 * client.describeExecutionFlow(request)
 *     .collect { response -> println(response.status) }
 * ```
 */
fun SfnClient.describeExecutionFlow(
    request: DescribeExecutionRequest,
    options: SfnExecutionPollingOptions = SfnExecutionPollingOptions(),
): Flow<DescribeExecutionResponse> = flow {
    val response = describeExecution(request)
    when (val status = response.status) {
        ExecutionStatus.Running,
        ExecutionStatus.Succeeded,
        ExecutionStatus.Failed,
        ExecutionStatus.TimedOut,
        ExecutionStatus.Aborted,
        ExecutionStatus.PendingRedrive -> emit(response)
        is ExecutionStatus.SdkUnknown -> error(
            "Unsupported Step Functions execution status: ${status.value}",
        )
    }
}.repeat(options.pollInterval).transformWhile { response ->
    emit(response)
    response.status == ExecutionStatus.Running
}

/**
 * [executionArn]으로 실행 상태를 polling하는 cold Flow를 반환합니다.
 *
 * request 기반 overload와 같은 polling, terminal, 취소, 예외 계약을 따릅니다.
 */
fun SfnClient.describeExecutionFlow(
    executionArn: String,
    options: SfnExecutionPollingOptions = SfnExecutionPollingOptions(),
): Flow<DescribeExecutionResponse> = describeExecutionFlow(
    describeExecutionRequestOf(executionArn),
    options,
)
