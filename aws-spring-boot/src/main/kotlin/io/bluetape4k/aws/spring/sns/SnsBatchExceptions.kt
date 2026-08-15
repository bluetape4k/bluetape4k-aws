package io.bluetape4k.aws.spring.sns

import kotlinx.coroutines.CancellationException
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
    val entryFingerprint: String,
) : IllegalStateException(
    "SNS batch transport failed: type=$failureType, " +
        "completedCount=${completedEntryIds.size}, fingerprint=$entryFingerprint",
) {

    val completedEntryIds: List<String> = completedEntryIds.toList()

    override fun toString(): String =
        "SnsBatchTransportException(failureType=$failureType, " +
            "completedCount=${completedEntryIds.size}, fingerprint=$entryFingerprint)"

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
            cause is TimeoutException || cause::class.simpleName?.contains("Timeout", ignoreCase = true) == true ->
                SnsBatchFailureType.TIMEOUT
            cause::class.qualifiedName?.contains("SdkServiceException") == true -> SnsBatchFailureType.SDK_SERVICE
            cause::class.qualifiedName?.contains("SdkClientException") == true -> SnsBatchFailureType.CLIENT
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
