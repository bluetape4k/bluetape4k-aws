@file:Suppress("MatchingDeclarationName")

package io.bluetape4k.aws.sfn

import io.bluetape4k.aws.sfn.model.describeExecutionRequestOf
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
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

/** 주어진 request로 실행 상태를 polling하는 cold Flow를 반환합니다. */
fun SfnAsyncClient.describeExecutionFlow(
    request: DescribeExecutionRequest,
    options: SfnExecutionPollingOptions = SfnExecutionPollingOptions(),
): Flow<DescribeExecutionResponse> = flow {
    while (true) {
        currentCoroutineContext().ensureActive()
        val response = describeExecution(request).await()
        currentCoroutineContext().ensureActive()
        when (response.status()) {
            ExecutionStatus.RUNNING -> {
                emit(response)
                delay(options.pollInterval)
            }
            ExecutionStatus.SUCCEEDED,
            ExecutionStatus.FAILED,
            ExecutionStatus.TIMED_OUT,
            ExecutionStatus.ABORTED,
            ExecutionStatus.PENDING_REDRIVE -> {
                emit(response)
                return@flow
            }
            null,
            ExecutionStatus.UNKNOWN_TO_SDK_VERSION -> error(
                "Unsupported Step Functions execution status: ${response.statusAsString() ?: "<null>"}",
            )
        }
    }
}

/** execution ARN으로 실행 상태를 polling하는 cold Flow를 반환합니다. */
fun SfnAsyncClient.describeExecutionFlow(
    executionArn: String,
    options: SfnExecutionPollingOptions = SfnExecutionPollingOptions(),
): Flow<DescribeExecutionResponse> = describeExecutionFlow(
    describeExecutionRequestOf(executionArn),
    options,
)
