@file:Suppress("DEPRECATION")

package io.bluetape4k.aws.kotlin.kinesis

import aws.sdk.kotlin.services.kinesis.model.DryRunOperationException
import aws.sdk.kotlin.services.kinesis.model.ResourceInUseException
import aws.sdk.kotlin.services.kinesis.model.ResourceNotFoundException
import aws.smithy.kotlin.runtime.InternalApi
import aws.smithy.kotlin.runtime.ServiceErrorMetadata
import aws.smithy.kotlin.runtime.ServiceException
import aws.smithy.kotlin.runtime.http.response.HttpResponse
import aws.smithy.kotlin.runtime.net.url.Url
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.net.URI
import java.util.UUID
import java.util.concurrent.TimeoutException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds

/** Kinesis DryRun 테스트에서 사용하는 operation 식별자입니다. */
internal enum class KinesisDryRunOperation(
    val wireName: String,
    val token: String,
) {
    PUT_RECORD("PutRecord", "put-record"),
    PUT_RECORDS("PutRecords", "put-records"),
    GET_SHARD_ITERATOR("GetShardIterator", "get-shard-iterator"),
    GET_RECORDS("GetRecords", "get-records"),
}

/** 에뮬레이터 capability 행의 상태입니다. */
internal enum class KinesisDryRunStatus(val wireValue: String) {
    SUPPORTED("supported"),
    UNSUPPORTED("unsupported"),
    FAILED("failed"),
}

/** 로그와 capability report에 허용하는 제한된 reason code입니다. */
internal enum class KinesisDryRunReason(val wireValue: String) {
    DRY_RUN_ACCEPTED("dry_run_accepted"),
    NOT_IMPLEMENTED("not_implemented"),
    UNKNOWN_DRY_RUN_MEMBER("unknown_dry_run_member"),
    DRY_RUN_IGNORED_RESPONSE("dry_run_ignored_response"),
    DRY_RUN_IGNORED_WRITE("dry_run_ignored_write"),
    NORMAL_RESPONSE("normal_response"),
    ACCESS_DENIED("access_denied"),
    HTTP_FORBIDDEN("http_forbidden"),
    ENDPOINT_FAILURE("endpoint_failure"),
    TIMEOUT("timeout"),
    ASSERTION_FAILURE("assertion_failure"),
    UNEXPECTED_FAILURE("unexpected_failure"),
}

internal data class KinesisDryRunDecision(
    val status: KinesisDryRunStatus,
    val reason: KinesisDryRunReason,
)

/** validator가 허용하는 capability report의 단일 행입니다. */
internal data class KinesisDryRunCapabilityRow(
    val schemaVersion: Int,
    val backend: String,
    val backendVersion: String,
    val operation: KinesisDryRunOperation,
    val status: KinesisDryRunStatus,
    val sanitizedReason: KinesisDryRunReason,
    val streamToken: String,
) {
    fun assumptionMessage(): String = listOf(
        "backend=$backend",
        "version=$backendVersion",
        "operation=${operation.wireName}",
        "status=${status.wireValue}",
        "reason=${sanitizedReason.wireValue}",
        "streamToken=$streamToken",
    ).joinToString(" ")
}

internal data class KinesisDryRunTestBoundary(
    val endpoint: Url?,
    val accessKey: String?,
    val secretKey: String?,
)

internal const val KINESIS_DRY_RUN_FAKE_ACCESS_KEY: String = "test"
internal const val KINESIS_DRY_RUN_FAKE_SECRET_KEY: String = "test"
internal const val KINESIS_DRY_RUN_REPORT_SCHEMA_VERSION: Int = 1

internal val KINESIS_DRY_RUN_OPERATION_TIMEOUT: Duration = 30.seconds
internal val KINESIS_DRY_RUN_MAX_POLL_INTERVAL: Duration = 500.milliseconds

/** DryRun 성공 응답과 명시적으로 허용한 backend capability만 분류합니다. */
@OptIn(InternalApi::class)
@Suppress("CyclomaticComplexMethod")
internal fun classifyKinesisDryRunFailure(failure: Throwable?): KinesisDryRunDecision {
    val serviceFailure = failure as? ServiceException
    val errorCode = serviceFailure?.sdkErrorMetadata?.errorCode
    val statusCode = serviceFailure?.sdkErrorMetadata?.attributes?.let { attributes ->
        attributes.getOrNull(ServiceErrorMetadata.ProtocolResponse)?.let { response ->
            (response as? HttpResponse)?.status?.value
        }
    }
    val reason = when {
        failure == null -> KinesisDryRunReason.NORMAL_RESPONSE
        failure is DryRunOperationException -> KinesisDryRunReason.DRY_RUN_ACCEPTED
        isNotImplemented(statusCode, errorCode) -> KinesisDryRunReason.NOT_IMPLEMENTED
        isUnknownDryRunMember(statusCode, errorCode, failure.message.orEmpty()) -> {
            KinesisDryRunReason.UNKNOWN_DRY_RUN_MEMBER
        }
        errorCode?.lowercase() in ACCESS_DENIED_ERROR_CODES -> KinesisDryRunReason.ACCESS_DENIED
        statusCode == 403 -> KinesisDryRunReason.HTTP_FORBIDDEN
        failure.isTimeoutFailure() -> KinesisDryRunReason.TIMEOUT
        failure is AssertionError -> KinesisDryRunReason.ASSERTION_FAILURE
        failure is IOException -> KinesisDryRunReason.ENDPOINT_FAILURE
        else -> KinesisDryRunReason.UNEXPECTED_FAILURE
    }
    val status = when (reason) {
        KinesisDryRunReason.DRY_RUN_ACCEPTED -> KinesisDryRunStatus.SUPPORTED
        KinesisDryRunReason.NOT_IMPLEMENTED,
        KinesisDryRunReason.UNKNOWN_DRY_RUN_MEMBER,
        -> KinesisDryRunStatus.UNSUPPORTED
        else -> KinesisDryRunStatus.FAILED
    }
    return KinesisDryRunDecision(status, reason)
}

internal fun sanitizedKinesisDryRunEvidence(
    backend: String,
    backendVersion: String,
    operation: KinesisDryRunOperation,
    failure: Throwable?,
    streamToken: String,
): KinesisDryRunCapabilityRow = sanitizedKinesisDryRunEvidence(
    backend = backend,
    backendVersion = backendVersion,
    operation = operation,
    decision = classifyKinesisDryRunFailure(failure),
    streamToken = streamToken,
)

internal fun sanitizedKinesisDryRunEvidence(
    backend: String,
    backendVersion: String,
    operation: KinesisDryRunOperation,
    decision: KinesisDryRunDecision,
    streamToken: String,
): KinesisDryRunCapabilityRow {
    validatedKinesisDryRunBackend(backend)
    require(backendVersion.matches(BACKEND_VERSION_PATTERN)) { "backend version is not bounded" }
    require(streamToken.matches(STREAM_TOKEN_PATTERN)) { "stream token is not generated-safe" }
    return KinesisDryRunCapabilityRow(
        schemaVersion = KINESIS_DRY_RUN_REPORT_SCHEMA_VERSION,
        backend = backend,
        backendVersion = backendVersion,
        operation = operation,
        status = decision.status,
        sanitizedReason = decision.reason,
        streamToken = streamToken,
    )
}

/** 네트워크 호출 직전에 endpoint와 정적 fake 자격 증명을 fail-closed로 검증합니다. */
internal fun verifyKinesisDryRunTestBoundary(boundary: KinesisDryRunTestBoundary) {
    val endpoint = boundary.endpoint ?: throw IllegalArgumentException("emulator endpoint is required")
    val uri = URI(endpoint.toString())
    require(uri.scheme == "http" || uri.scheme == "https") { "emulator endpoint must use HTTP or HTTPS" }
    require(uri.userInfo == null) { "emulator endpoint must not contain userinfo" }
    require(uri.query == null && uri.fragment == null) { "emulator endpoint must not contain query or fragment" }
    require(uri.host in LOOPBACK_HOSTS) { "emulator endpoint must be loopback" }
    require(boundary.accessKey == KINESIS_DRY_RUN_FAKE_ACCESS_KEY) {
        "emulator access credentials must use static fake markers"
    }
    require(boundary.secretKey == KINESIS_DRY_RUN_FAKE_SECRET_KEY) {
        "emulator secret credentials must use static fake markers"
    }
}

internal fun boundedKinesisPollInterval(requested: Duration): Duration {
    require(requested.isPositive()) { "poll interval must be positive" }
    return requested.coerceAtMost(KINESIS_DRY_RUN_MAX_POLL_INTERVAL)
}

internal fun validatedKinesisDryRunBackend(backend: String): String {
    require(backend in ALLOWED_BACKENDS) { "unsupported emulator backend" }
    return backend
}

internal suspend fun <T> withinKinesisOperationDeadline(
    timeout: Duration = KINESIS_DRY_RUN_OPERATION_TIMEOUT,
    block: suspend () -> T,
): T {
    require(timeout.isPositive()) { "operation timeout must be positive" }
    require(timeout <= KINESIS_DRY_RUN_OPERATION_TIMEOUT) { "operation timeout exceeds 30 seconds" }
    return withTimeout(timeout) { block() }
}

/** 테스트 clock/delay를 주입할 수 있는 bounded observation polling helper입니다. */
internal suspend fun <T> awaitKinesisCondition(
    timeout: Duration = KINESIS_DRY_RUN_OPERATION_TIMEOUT,
    pollInterval: Duration = 250.milliseconds,
    now: () -> Long = System::nanoTime,
    delay: ((Duration) -> Unit)? = null,
    condition: suspend () -> T?,
): T {
    return observeKinesisConditionUntilDeadline(timeout, pollInterval, now, delay, condition)
        ?: throw TimeoutException("Kinesis DryRun observation timed out")
}

/** deadline 전체에서 조건을 관측하고, 끝까지 충족되지 않으면 null을 반환합니다. */
internal suspend fun <T> observeKinesisConditionUntilDeadline(
    timeout: Duration = KINESIS_DRY_RUN_OPERATION_TIMEOUT,
    pollInterval: Duration = 250.milliseconds,
    now: () -> Long = System::nanoTime,
    delay: ((Duration) -> Unit)? = null,
    condition: suspend () -> T?,
): T? {
    require(timeout.isPositive()) { "operation timeout must be positive" }
    require(timeout <= KINESIS_DRY_RUN_OPERATION_TIMEOUT) { "operation timeout exceeds 30 seconds" }
    val interval = boundedKinesisPollInterval(pollInterval)
    val deadline = now() + timeout.inWholeNanoseconds
    while (now() < deadline) {
        val remainingBeforePoll = deadline - now()
        val completedPoll = withTimeoutOrNull(remainingBeforePoll.nanoseconds) {
            CompletedKinesisPoll(condition())
        } ?: throw TimeoutException("Kinesis DryRun observation timed out during poll")
        completedPoll.value?.let { return it }

        val remainingAfterPoll = deadline - now()
        if (remainingAfterPoll <= 0) break
        val boundedDelay = interval.coerceAtMost(remainingAfterPoll.nanoseconds)
        if (delay == null) kotlinx.coroutines.delay(boundedDelay) else delay(boundedDelay)
    }
    return null
}

/** stream 이름에는 실행 nonce와 UUID 일부를 넣어 다른 테스트의 자원을 소유하지 않게 합니다. */
internal fun newKinesisDryRunStreamToken(prefix: String = "dryrun"): String {
    require(prefix.matches(STREAM_PREFIX_PATTERN)) { "stream token prefix is not generated-safe" }
    return "$prefix-${UUID.randomUUID().toString().replace("-", "").take(20)}"
}

/** create ambiguity와 cancellation 중에도 소유한 stream만 bounded cleanup합니다. */
@Suppress("SwallowedException", "ThrowsCount")
internal suspend fun <T> withOwnedKinesisStream(
    maxAttempts: Int = 3,
    nameFactory: (Int) -> String = { attempt -> "${newKinesisDryRunStreamToken()}-$attempt" },
    describe: suspend (String) -> Unit,
    create: suspend (String) -> Unit,
    delete: suspend (String) -> Unit,
    cleanupTimeout: Duration = KINESIS_DRY_RUN_OPERATION_TIMEOUT,
    block: suspend (String) -> T,
): T {
    require(maxAttempts > 0) { "maxAttempts must be positive" }
    require(cleanupTimeout.isPositive()) { "cleanup timeout must be positive" }
    require(cleanupTimeout <= KINESIS_DRY_RUN_OPERATION_TIMEOUT) { "cleanup timeout exceeds 30 seconds" }

    repeat(maxAttempts) { attemptIndex ->
        val streamName = nameFactory(attemptIndex + 1)
        require(streamName.isNotBlank()) { "stream name must not be blank" }
        when (preflightStream(describe, streamName)) {
            StreamOwnership.COLLISION -> return@repeat
            StreamOwnership.ABSENT -> Unit
        }

        // Registration deliberately precedes create: a response lost after server-side create is cleanup-safe.
        try {
            create(streamName)
        } catch (collision: ResourceInUseException) {
            // A concurrent creator owns this name; never delete it.
            return@repeat
        } catch (createFailure: Throwable) {
            cleanupOwnedKinesisStream(streamName, delete, cleanupTimeout, createFailure)
            throw createFailure
        }

        var primary: Throwable? = null
        try {
            return block(streamName)
        } catch (failure: Throwable) {
            primary = failure
            throw failure
        } finally {
            cleanupOwnedKinesisStream(streamName, delete, cleanupTimeout, primary)
        }
    }
    throw IllegalStateException("unable to allocate an owned Kinesis stream after $maxAttempts attempts")
}

@Suppress("SwallowedException", "TooGenericExceptionCaught")
private suspend fun preflightStream(
    describe: suspend (String) -> Unit,
    streamName: String,
): StreamOwnership = try {
    describe(streamName)
    StreamOwnership.COLLISION
} catch (_: ResourceNotFoundException) {
    StreamOwnership.ABSENT
} catch (_: ResourceInUseException) {
    StreamOwnership.COLLISION
}

private enum class StreamOwnership {
    ABSENT,
    COLLISION,
}

private data class CompletedKinesisPoll<T>(val value: T?)

private suspend fun cleanupOwnedKinesisStream(
    streamName: String,
    delete: suspend (String) -> Unit,
    cleanupTimeout: Duration,
    primary: Throwable?,
) {
    var directCleanupFailure: Throwable? = null
    val cleanupFailure = try {
        withContext(NonCancellable) {
            withTimeout(cleanupTimeout) {
                try {
                    delete(streamName)
                } catch (_: ResourceNotFoundException) {
                    // Delete is idempotent when the server already removed the owned stream.
                } catch (failure: Throwable) {
                    directCleanupFailure = failure
                    throw failure
                }
            }
        }
        null
    } catch (failure: Throwable) {
        directCleanupFailure ?: failure
    }
    if (cleanupFailure != null) {
        if (primary != null) {
            primary.addSuppressed(cleanupFailure)
        } else {
            throw cleanupFailure
        }
    }
}

private fun String.hasUnknownDryRunMemberCause(): Boolean {
    val normalized = lowercase()
    if (!normalized.contains("dryrun")) return false
    return UNKNOWN_MEMBER_WORDS.any { word -> normalized.contains(word) } &&
        MEMBER_WORDS.any { word -> normalized.contains(word) }
}

private fun isNotImplemented(statusCode: Int?, errorCode: String?): Boolean =
    statusCode == 501 && errorCode in NOT_IMPLEMENTED_ERROR_CODES

private fun isUnknownDryRunMember(
    statusCode: Int?,
    errorCode: String?,
    message: String,
): Boolean = statusCode == 400 &&
    errorCode in UNKNOWN_MEMBER_ERROR_CODES &&
    message.hasUnknownDryRunMemberCause()

private fun Throwable?.isTimeoutFailure(): Boolean {
    if (this == null) return false
    val message = message.orEmpty()
    return listOf(
        this is TimeoutException,
        javaClass.simpleName.contains("Timeout", ignoreCase = true),
        message.contains("timeout", ignoreCase = true),
        message.contains("timed out", ignoreCase = true),
    ).any { it }
}

private val NOT_IMPLEMENTED_ERROR_CODES = setOf("NotImplemented", "NotImplementedException")
private val UNKNOWN_MEMBER_ERROR_CODES = setOf(
    "SerializationException",
    "ValidationException",
    "InvalidArgumentException",
)
private val ACCESS_DENIED_ERROR_CODES = setOf("accessdenied", "accessdeniedexception")
private val UNKNOWN_MEMBER_WORDS = setOf("unknown", "unsupported", "unrecognized", "invalid")
private val MEMBER_WORDS = setOf("member", "field", "parameter")
private val ALLOWED_BACKENDS = setOf("floci", "localstack")
private val BACKEND_VERSION_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9._+:/-]{0,63}")
private val STREAM_PREFIX_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9-]{0,20}")
private val STREAM_TOKEN_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,95}")
private val LOOPBACK_HOSTS = setOf("127.0.0.1", "localhost", "[::1]")
