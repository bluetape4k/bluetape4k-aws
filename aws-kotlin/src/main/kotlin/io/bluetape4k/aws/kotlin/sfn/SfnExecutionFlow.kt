@file:Suppress("MatchingDeclarationName")

package io.bluetape4k.aws.kotlin.sfn

import aws.sdk.kotlin.services.sfn.SfnClient
import aws.sdk.kotlin.services.sfn.model.DescribeExecutionRequest
import aws.sdk.kotlin.services.sfn.model.DescribeExecutionResponse
import aws.sdk.kotlin.services.sfn.model.ExecutionStatus
import io.bluetape4k.aws.kotlin.sfn.model.describeExecutionRequestOf
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
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

/** 주어진 request로 실행 상태를 polling하는 cold Flow를 반환합니다. */
fun SfnClient.describeExecutionFlow(
    request: DescribeExecutionRequest,
    options: SfnExecutionPollingOptions = SfnExecutionPollingOptions(),
): Flow<DescribeExecutionResponse> = flow {
    while (true) {
        currentCoroutineContext().ensureActive()
        val response = describeExecution(request)
        currentCoroutineContext().ensureActive()
        when (val status = response.status) {
            ExecutionStatus.Running -> {
                emit(response)
                delay(options.pollInterval)
            }
            ExecutionStatus.Succeeded,
            ExecutionStatus.Failed,
            ExecutionStatus.TimedOut,
            ExecutionStatus.Aborted,
            ExecutionStatus.PendingRedrive -> {
                emit(response)
                return@flow
            }
            is ExecutionStatus.SdkUnknown -> error(
                "Unsupported Step Functions execution status: ${status.value}",
            )
        }
    }
}

/** execution ARN으로 실행 상태를 polling하는 cold Flow를 반환합니다. */
fun SfnClient.describeExecutionFlow(
    executionArn: String,
    options: SfnExecutionPollingOptions = SfnExecutionPollingOptions(),
): Flow<DescribeExecutionResponse> = describeExecutionFlow(
    describeExecutionRequestOf(executionArn),
    options,
)
