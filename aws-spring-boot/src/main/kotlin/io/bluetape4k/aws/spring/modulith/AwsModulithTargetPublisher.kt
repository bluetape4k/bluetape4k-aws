package io.bluetape4k.aws.spring.modulith

import kotlinx.coroutines.CancellationException

/** AWS publisher가 transport에 전달하는 외부화 명령입니다. */
internal data class AwsModulithPublishCommand(
    val targetAlias: String,
    val destination: String,
    val routingKey: String?,
    val eventId: String,
    val encoded: AwsModulithEncodedEvent,
)

/** provider의 원문 응답을 노출하지 않는 bounded publication 결과입니다. */
internal data class AwsModulithPublishResult(
    val service: AwsModulithTargetService,
    val targetAlias: String,
    val providerMessageIdPresent: Boolean,
)

/** AWS SDK 타입과 Spring Modulith transport를 분리하는 내부 publisher 계약입니다. */
internal fun interface AwsModulithTargetPublisher {
    suspend fun publish(command: AwsModulithPublishCommand): AwsModulithPublishResult
}

@Suppress("TooGenericExceptionCaught")
internal suspend fun <T> sanitizeAwsModulithResolutionCall(block: suspend () -> T): T = try {
    block()
} catch (error: Throwable) {
    throw sanitizeAwsModulithFailure(error) { AwsModulithTargetResolutionException() }
}

@Suppress("TooGenericExceptionCaught")
internal suspend fun <T> sanitizeAwsModulithPublishCall(block: suspend () -> T): T = try {
    block()
} catch (error: Throwable) {
    throw sanitizeAwsModulithPublishFailure(error)
}

internal fun sanitizeAwsModulithPublishFailure(error: Throwable): Throwable =
    sanitizeAwsModulithFailure(error) { AwsModulithPublishException() }

private inline fun sanitizeAwsModulithFailure(
    error: Throwable,
    fallback: () -> AwsModulithEventException,
): Throwable = when (error) {
    is CancellationException -> error
    is Error -> error
    is AwsModulithEventException -> error
    else -> fallback()
}

private val AWS_MODULITH_DESTINATION_PATTERN = Regex("[A-Za-z0-9_-]+(?:\\.fifo)?")

/** publisher 경계에서 ARN·URL·partition-qualified destination을 거부합니다. */
internal fun requireAwsModulithDestinationName(destination: String): String {
    require(AWS_MODULITH_DESTINATION_PATTERN.matches(destination)) {
        "destination must be a logical SNS topic or SQS queue name."
    }
    return destination
}
