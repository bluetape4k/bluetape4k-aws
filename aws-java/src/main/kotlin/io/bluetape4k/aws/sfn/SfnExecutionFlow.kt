@file:Suppress("MatchingDeclarationName")

package io.bluetape4k.aws.sfn

import io.bluetape4k.aws.sfn.model.describeExecutionRequestOf
import io.bluetape4k.coroutines.flow.extensions.repeat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.future.await
import software.amazon.awssdk.services.sfn.SfnAsyncClient
import software.amazon.awssdk.services.sfn.model.DescribeExecutionRequest
import software.amazon.awssdk.services.sfn.model.DescribeExecutionResponse
import software.amazon.awssdk.services.sfn.model.ExecutionStatus
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
 * 첫 조회는 즉시 실행하고, `RUNNING` 응답 뒤에는 [SfnExecutionPollingOptions.pollInterval]만큼 기다립니다.
 * terminal 응답은 마지막으로 방출하며, 알 수 없는 상태나 SDK 조회 실패는 그대로 예외로 전파합니다.
 * Flow 수집을 취소해도 execution을 중단하거나 client를 닫지 않습니다.
 *
 * ```kotlin
 * client.describeExecutionFlow(request)
 *     .collect { response -> println(response.statusAsString()) }
 * ```
 */
fun SfnAsyncClient.describeExecutionFlow(
    request: DescribeExecutionRequest,
    options: SfnExecutionPollingOptions = SfnExecutionPollingOptions(),
): Flow<DescribeExecutionResponse> = flow {
    val response = describeExecution(request).await()
    when (response.status()) {
        ExecutionStatus.RUNNING,
        ExecutionStatus.SUCCEEDED,
        ExecutionStatus.FAILED,
        ExecutionStatus.TIMED_OUT,
        ExecutionStatus.ABORTED,
        ExecutionStatus.PENDING_REDRIVE -> emit(response)
        null,
        ExecutionStatus.UNKNOWN_TO_SDK_VERSION -> error(
            "Unsupported Step Functions execution status: ${response.statusAsString() ?: "<null>"}",
        )
    }
}.repeat(options.pollInterval).transformWhile { response ->
    emit(response)
    response.status() == ExecutionStatus.RUNNING
}

/**
 * [executionArn]으로 실행 상태를 polling하는 cold Flow를 반환합니다.
 *
 * request 기반 overload와 같은 polling, terminal, 취소, 예외 계약을 따릅니다.
 */
fun SfnAsyncClient.describeExecutionFlow(
    executionArn: String,
    options: SfnExecutionPollingOptions = SfnExecutionPollingOptions(),
): Flow<DescribeExecutionResponse> = describeExecutionFlow(
    describeExecutionRequestOf(executionArn),
    options,
)
