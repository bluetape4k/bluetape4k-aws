@file:Suppress("TooManyFunctions")

package io.bluetape4k.aws.sfn.model

import io.bluetape4k.support.requireNotBlank
import software.amazon.awssdk.services.sfn.model.DescribeExecutionRequest
import software.amazon.awssdk.services.sfn.model.ExecutionRedriveFilter
import software.amazon.awssdk.services.sfn.model.ExecutionStatus
import software.amazon.awssdk.services.sfn.model.ListExecutionsRequest
import software.amazon.awssdk.services.sfn.model.StartExecutionRequest
import software.amazon.awssdk.services.sfn.model.StopExecutionRequest

private const val MAX_EXECUTION_NAME_LENGTH = 80
private const val MAX_LIST_EXECUTIONS_RESULTS = 1000

/**
 * 실행 시작 요청의 SDK builder 필드만 사용해 구성합니다.
 * 필수 ARN·입력 정규화와 최종 검증이 필요한 경우 [startExecutionRequestOf]를 사용하세요.
 */
inline fun startExecutionRequest(
    builder: StartExecutionRequest.Builder.() -> Unit,
): StartExecutionRequest = StartExecutionRequest.builder()
    .apply(builder)
    .build()

/**
 * 실행 중지 요청의 SDK builder 필드만 사용해 구성합니다.
 * 필수 execution ARN 검증이 필요한 경우 [stopExecutionRequestOf]를 사용하세요.
 */
inline fun stopExecutionRequest(
    builder: StopExecutionRequest.Builder.() -> Unit,
): StopExecutionRequest = StopExecutionRequest.builder()
    .apply(builder)
    .build()

/**
 * 실행 상세 조회 요청의 SDK builder 필드만 사용해 구성합니다.
 * 필수 execution ARN 검증이 필요한 경우 [describeExecutionRequestOf]를 사용하세요.
 */
inline fun describeExecutionRequest(
    builder: DescribeExecutionRequest.Builder.() -> Unit,
): DescribeExecutionRequest = DescribeExecutionRequest.builder()
    .apply(builder)
    .build()

/**
 * 실행 목록 요청의 SDK builder 필드만 사용해 구성합니다.
 * ARN 선택, redrive 조건과 page limit 검증이 필요한 경우 [listExecutionsRequestOf]를 사용하세요.
 */
inline fun listExecutionsRequest(
    builder: ListExecutionsRequest.Builder.() -> Unit,
): ListExecutionsRequest = ListExecutionsRequest.builder()
    .apply(builder)
    .build()

/**
 * AWS Step Functions 실행 시작 요청을 구성합니다.
 *
 * callback을 적용한 뒤 최종 요청을 다시 검증하며, 입력을 생략하면 AWS가 요구하는 빈 JSON인
 * `{}`를 사용합니다. JSON 문자열은 파싱하거나 재직렬화하지 않고 호출자가 전달한 원문을 보존합니다.
 */
inline fun startExecutionRequestOf(
    stateMachineArn: String,
    name: String? = null,
    input: String? = null,
    traceHeader: String? = null,
    builder: StartExecutionRequest.Builder.() -> Unit = {},
): StartExecutionRequest = StartExecutionRequest.builder()
    .stateMachineArn(stateMachineArn)
    .also { name?.let(it::name) }
    .also { input?.let(it::input) }
    .also { traceHeader?.let(it::traceHeader) }
    .apply(builder)
    .build()
    .validateAndNormalize()

/**
 * 실행 중지 요청을 구성합니다.
 *
 * [error]와 [cause]는 null일 때 요청에 설정하지 않으며, 지정된 문자열은 AWS SDK로 그대로 전달합니다.
 */
inline fun stopExecutionRequestOf(
    executionArn: String,
    error: String? = null,
    cause: String? = null,
    builder: StopExecutionRequest.Builder.() -> Unit = {},
): StopExecutionRequest = StopExecutionRequest.builder()
    .executionArn(executionArn)
    .also { error?.let(it::error) }
    .also { cause?.let(it::cause) }
    .apply(builder)
    .build()
    .also { it.executionArn().requireNotBlank("executionArn") }

/**
 * 실행 상세 조회 요청을 구성합니다.
 */
inline fun describeExecutionRequestOf(
    executionArn: String,
    builder: DescribeExecutionRequest.Builder.() -> Unit = {},
): DescribeExecutionRequest = DescribeExecutionRequest.builder()
    .executionArn(executionArn)
    .apply(builder)
    .build()
    .also { it.executionArn().requireNotBlank("executionArn") }

/**
 * 실행 목록 요청을 구성합니다.
 *
 * state machine ARN과 Map Run ARN 중 정확히 하나를 사용해야 하며, callback을 적용한 최종 요청에도
 * 같은 local invariant를 적용합니다. `PENDING_REDRIVE`와 [redriveFilter]는 Map Run 목록에서만
 * 사용할 수 있습니다.
 */
inline fun listExecutionsRequestOf(
    stateMachineArn: String? = null,
    mapRunArn: String? = null,
    statusFilter: ExecutionStatus? = null,
    redriveFilter: ExecutionRedriveFilter? = null,
    maxResults: Int? = null,
    nextToken: String? = null,
    builder: ListExecutionsRequest.Builder.() -> Unit = {},
): ListExecutionsRequest =
    buildListExecutionsRequest(
        stateMachineArn,
        mapRunArn,
        statusFilter,
        redriveFilter,
        maxResults,
        nextToken,
        builder,
    ).validate()

inline fun buildListExecutionsRequest(
    stateMachineArn: String?,
    mapRunArn: String?,
    statusFilter: ExecutionStatus?,
    redriveFilter: ExecutionRedriveFilter?,
    maxResults: Int?,
    nextToken: String?,
    builder: ListExecutionsRequest.Builder.() -> Unit,
): ListExecutionsRequest = ListExecutionsRequest.builder()
    .also { stateMachineArn?.let(it::stateMachineArn) }
    .also { mapRunArn?.let(it::mapRunArn) }
    .also { statusFilter?.let(it::statusFilter) }
    .also { redriveFilter?.let(it::redriveFilter) }
    .also { maxResults?.let(it::maxResults) }
    .also { nextToken?.let(it::nextToken) }
    .apply(builder)
    .build()

fun StartExecutionRequest.validateAndNormalize(): StartExecutionRequest {
    stateMachineArn().requireNotBlank("stateMachineArn")
    name()?.let {
        require(it.isNotBlank() && it.length <= MAX_EXECUTION_NAME_LENGTH) {
            "name must contain 1..80 non-blank characters"
        }
    }

    val normalizedInput = input() ?: "{}"
    require(normalizedInput.isNotBlank()) { "input must not be blank" }
    return if (input() == null) toBuilder().input(normalizedInput).build() else this
}


fun ListExecutionsRequest.validate(): ListExecutionsRequest {
    val hasStateMachineArn = stateMachineArn() != null
    val hasMapRunArn = mapRunArn() != null
    require(hasStateMachineArn xor hasMapRunArn) {
        "Exactly one of stateMachineArn or mapRunArn is required"
    }

    validateCommon()

    require(stateMachineArn() == null || statusFilter() != ExecutionStatus.PENDING_REDRIVE) {
        "PENDING_REDRIVE requires mapRunArn"
    }
    require(stateMachineArn() == null || redriveFilter() == null) {
        "redriveFilter requires mapRunArn"
    }
    return this
}

fun ListExecutionsRequest.validateCommon() = apply {
    stateMachineArn()?.requireNotBlank("stateMachineArn")
    mapRunArn()?.requireNotBlank("mapRunArn")
    nextToken()?.requireNotBlank("nextToken")
    maxResults()?.let {
        require(it in 0..MAX_LIST_EXECUTIONS_RESULTS) { "maxResults must be in 0..1000" }
    }
}
