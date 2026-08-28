package io.bluetape4k.aws.spring.sns

import kotlinx.coroutines.CancellationException
import software.amazon.awssdk.core.exception.ApiCallAttemptTimeoutException
import software.amazon.awssdk.core.exception.ApiCallTimeoutException
import software.amazon.awssdk.core.exception.SdkClientException
import software.amazon.awssdk.core.exception.SdkServiceException
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.TimeoutException

/** SNS 배치 전송 실패를 안전한 분류로 제한합니다. */
enum class SnsBatchFailureType {
    SDK_SERVICE,
    CLIENT,
    TIMEOUT,
    UNKNOWN,
}

/** SNS batch 응답을 반환하지 못했을 때 원인과 민감한 payload를 숨기는 예외입니다. */
class SnsBatchTransportException private constructor(
    val failureType: SnsBatchFailureType,
    completedEntryIds: Collection<String>,
    /**
     * 낮은 엔트로피 ID에서 원본 ID 집합을 추정하는 데 악용될 수 있는 호환성용 값입니다.
     *
     * 이 값은 호환성을 위해 유지하지만 기본 `message`와 `toString()`에는 포함하지 않습니다.
     * 명시적으로 외부 observability에 전달할 때는 호출자가 privacy와 수명주기를 책임져야 합니다.
     */
    @Deprecated("entryFingerprint는 privacy-safe 진단 계약이 아니므로 직접 사용을 피하세요.")
    val entryFingerprint: String,
) : IllegalStateException(
    "SNS batch transport failed: type=$failureType, " +
        "completedCount=${completedEntryIds.size}",
) {

    val completedEntryIds: List<String> = completedEntryIds.toList()

    override fun toString(): String =
        "SnsBatchTransportException(failureType=$failureType, " +
            "completedCount=${completedEntryIds.size})"

    companion object {
        private const val serialVersionUID: Long = 1L
        private const val FINGERPRINT_BYTE_COUNT: Int = 6

        /** 원본 throwable을 보관하지 않고 허용된 분류만 남깁니다. */
        fun from(cause: Throwable, completedEntryIds: Collection<String>): SnsBatchTransportException {
            if (cause is CancellationException) {
                throw cause
            }
            val ids = completedEntryIds.toList()
            return SnsBatchTransportException(
                failureType = classify(cause),
                completedEntryIds = ids,
                entryFingerprint = fingerprint(ids),
            )
        }

        private fun classify(cause: Throwable): SnsBatchFailureType = when {
            cause is ApiCallTimeoutException ||
                cause is ApiCallAttemptTimeoutException ||
                cause is TimeoutException ->
                SnsBatchFailureType.TIMEOUT
            cause is SdkServiceException -> SnsBatchFailureType.SDK_SERVICE
            cause is SdkClientException -> SnsBatchFailureType.CLIENT
            else -> SnsBatchFailureType.UNKNOWN
        }

        private fun fingerprint(ids: List<String>): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val bytes = digest.digest(ids.joinToString("\u0000").toByteArray(StandardCharsets.UTF_8))
            return bytes.take(FINGERPRINT_BYTE_COUNT)
                .joinToString(separator = "") { byte -> "%02x".format(byte) }
        }
    }
}

/** SNS PublishBatch 응답 ID가 요청과 일치하지 않을 때 발생하는 안전한 예외입니다. */
class SnsBatchProtocolException private constructor(
    val submittedEntryCount: Int,
    val responseEntryCount: Int,
    val unknownEntryCount: Int,
    val duplicateEntryCount: Int,
    val missingEntryCount: Int,
) : IllegalStateException(
    "SNS batch response protocol mismatch: submittedCount=$submittedEntryCount, " +
        "responseCount=$responseEntryCount, unknownCount=$unknownEntryCount, " +
        "duplicateCount=$duplicateEntryCount, missingCount=$missingEntryCount",
) {

    val completedEntryIds: List<String> = emptyList()

    override fun toString(): String =
        "SnsBatchProtocolException(submittedCount=$submittedEntryCount, " +
            "responseCount=$responseEntryCount, unknownCount=$unknownEntryCount, " +
            "duplicateCount=$duplicateEntryCount, missingCount=$missingEntryCount)"

    companion object {
        private const val serialVersionUID: Long = 1L

        fun from(
            submittedEntryIds: Collection<String>,
            responseEntryIds: Collection<String>,
        ): SnsBatchProtocolException {
            val submitted = submittedEntryIds.toList()
            val response = responseEntryIds.toList()
            val submittedSet = submitted.toSet()
            val responseCounts = response.groupingBy { it }.eachCount()
            val unknownCount = response.count { it !in submittedSet }
            val duplicateCount = responseCounts.values.sumOf { (it - 1).coerceAtLeast(0) }
            val missingCount = submittedSet.count { it !in response.toSet() }
            return SnsBatchProtocolException(
                submittedEntryCount = submitted.size,
                responseEntryCount = response.size,
                unknownEntryCount = unknownCount,
                duplicateEntryCount = duplicateCount,
                missingEntryCount = missingCount,
            )
        }
    }
}
