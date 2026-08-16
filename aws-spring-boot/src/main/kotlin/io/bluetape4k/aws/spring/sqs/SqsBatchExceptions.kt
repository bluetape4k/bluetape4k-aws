package io.bluetape4k.aws.spring.sqs

import software.amazon.awssdk.services.sqs.model.SqsException
import java.util.concurrent.CompletionException
import java.util.concurrent.ExecutionException

/** 자동 배치 종료 중 실패한 자원 종류입니다. */
enum class SqsBatchCleanupComponent {
    MANAGER,
    EXECUTOR,
    TIMEOUT,
}

/** 자동 배치 초기화에 실패한 구성 요소입니다. */
enum class SqsBatchStartupComponent {
    MANAGER,
    TRANSPORT,
    TEMPLATE,
}

/** 실패 항목을 모두 수집한 자동 배치 전송 예외입니다. */
class SqsSendBatchFailedException(
    val result: SqsSendManyResult,
) : IllegalStateException(
    "SQS send batch failed: status=${result.status}, failureCount=${result.failed.size}",
) {
    override fun toString(): String =
        "SqsSendBatchFailedException(status=${result.status}, failureCount=${result.failed.size})"

    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

/** 자동 배치 자원 종료 실패를 안전한 component 목록으로 제한한 예외입니다. */
class SqsBatchCloseException private constructor(
    val components: List<SqsBatchCleanupComponent>,
    @Suppress("UNUSED_PARAMETER") normalizedMarker: Boolean,
) : IllegalStateException(
    "SQS batch close failed: components=${components.joinToString(",")}, failureCount=${components.size}",
) {
    constructor(components: Collection<SqsBatchCleanupComponent>) : this(
        normalizeCleanupComponents(components),
        true,
    )

    val failureCount: Int get() = components.size

    override fun toString(): String =
        "SqsBatchCloseException(components=${components.joinToString(",")}, failureCount=$failureCount)"

    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

/** 자동 배치 초기화 실패와 rollback 정리 결과를 안전하게 전달하는 예외입니다. */
class SqsBatchStartupException private constructor(
    val startupComponent: SqsBatchStartupComponent,
    val cleanupComponents: List<SqsBatchCleanupComponent>,
    @Suppress("UNUSED_PARAMETER") normalizedMarker: Boolean,
) : IllegalStateException(
    "SQS batch startup failed: component=$startupComponent, " +
        "cleanupComponents=${cleanupComponents.joinToString(",")}, " +
        "cleanupFailureCount=${cleanupComponents.size}",
) {
    constructor(
        startupComponent: SqsBatchStartupComponent,
        cleanupComponents: Collection<SqsBatchCleanupComponent> = emptyList(),
    ) : this(startupComponent, normalizeCleanupComponents(cleanupComponents), true)

    val cleanupFailureCount: Int get() = cleanupComponents.size

    override fun toString(): String =
        "SqsBatchStartupException(component=$startupComponent, " +
            "cleanupComponents=${cleanupComponents.joinToString(",")}, " +
            "cleanupFailureCount=$cleanupFailureCount)"

    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

internal class SqsBatchProtocolException private constructor(
    val submittedEntryCount: Int,
    val responseEntryCount: Int,
    val unknownEntryCount: Int,
    val duplicateEntryCount: Int,
    val missingEntryCount: Int,
) : IllegalStateException(
    "SQS batch response protocol mismatch: submittedCount=$submittedEntryCount, " +
        "responseCount=$responseEntryCount, unknownCount=$unknownEntryCount, " +
        "duplicateCount=$duplicateEntryCount, missingCount=$missingEntryCount",
) {
    override fun toString(): String =
        "SqsBatchProtocolException(submittedCount=$submittedEntryCount, " +
            "responseCount=$responseEntryCount, unknownCount=$unknownEntryCount, " +
            "duplicateCount=$duplicateEntryCount, missingCount=$missingEntryCount)"

    companion object {
        private const val serialVersionUID: Long = 1L

        fun from(
            submittedEntryIds: Collection<String>,
            responseEntryIds: Collection<String>,
        ): SqsBatchProtocolException {
            val submitted = submittedEntryIds.toList()
            val response = responseEntryIds.toList()
            val submittedSet = submitted.toSet()
            val responseCounts = response.groupingBy { it }.eachCount()
            val responseSet = responseCounts.keys
            return SqsBatchProtocolException(
                submittedEntryCount = submitted.size,
                responseEntryCount = response.size,
                unknownEntryCount = response.count { it !in submittedSet },
                duplicateEntryCount = responseCounts.values.sumOf { (it - 1).coerceAtLeast(0) },
                missingEntryCount = submittedSet.count { it !in responseSet },
            )
        }
    }
}

internal fun normalizeBatchFailure(entryId: String, cause: Throwable): SqsBatchEntryFailure {
    val root = unwrapBatchFailure(cause)
    val errorDetails = (root as? SqsException)?.awsErrorDetails()
    return if (root is SqsException && errorDetails != null) {
        SqsBatchEntryFailure(entryId, SqsBatchFailureKind.SERVICE, errorDetails.errorCode())
    } else {
        SqsBatchEntryFailure(entryId, SqsBatchFailureKind.TRANSPORT, null)
    }
}

private fun unwrapBatchFailure(cause: Throwable): Throwable {
    var current = cause
    while (current is CompletionException || current is ExecutionException) {
        val nested = current.cause?.takeUnless { it === current } ?: return current
        current = nested
    }
    return current
}

private fun normalizeCleanupComponents(
    components: Collection<SqsBatchCleanupComponent>,
): List<SqsBatchCleanupComponent> = components.distinct().sortedBy(SqsBatchCleanupComponent::ordinal)
