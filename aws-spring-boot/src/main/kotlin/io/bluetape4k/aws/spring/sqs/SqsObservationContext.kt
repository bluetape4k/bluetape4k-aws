package io.bluetape4k.aws.spring.sqs

import io.micrometer.observation.Observation
import java.io.Serializable
import java.net.URI
import java.net.URISyntaxException

/**
 * SQS 관찰 수명 주기의 처리 단계를 나타냅니다.
 */
enum class SqsObservationStage {
    RECEIVE,
    PROCESS,
    ACKNOWLEDGEMENT,
}

/**
 * SQS 관찰의 종료 결과를 나타냅니다.
 */
enum class SqsObservationOutcome {
    UNKNOWN,
    SUCCESS,
    RETRIED,
    ERROR,
    CANCELLED,
    PARTIAL,
}

/**
 * SQS 메시지가 전달된 횟수의 제한된 분류입니다.
 */
enum class SqsObservationDelivery {
    UNKNOWN,
    FIRST,
    REDELIVERED,
}

/**
 * 관찰 사용자 확장 지점에 전달하는 비식별 SQS metadata입니다.
 *
 * 원본 메시지 본문, receipt handle, 임의 속성, 전체 URL은 보관하지 않습니다. 생성자는 내부로
 * 제한하며, listener와 queue 값은 관찰 경계에서 정제합니다. `batch=true`이면 batch 크기가
 * 1이어도 메시지 및 FIFO 식별자를 제거합니다.
 */
class SqsObservationMetadata internal constructor(
    listenerId: String,
    queueName: String,
    val stage: SqsObservationStage,
    val batch: Boolean,
    messageId: String? = null,
    messageGroupId: String? = null,
    messageDeduplicationId: String? = null,
    val initialAttempt: Int? = null,
    val batchSize: Int = 0,
    val acknowledgementAction: SqsAcknowledgementAction? = null,
    val delivery: SqsObservationDelivery = SqsObservationDelivery.UNKNOWN,
    queueNameResolved: Boolean = false,
) : Serializable {

    val listenerId: String = listenerId.ifBlank { UNKNOWN }
    val queueName: String = if (queueNameResolved) {
        queueName.also(::requireResolvedSqsObservationQueueName)
    } else {
        resolveSqsObservationQueueName(queueName)
    }
    val messageId: String? = messageId.takeUnless { batch }
    val messageGroupId: String? = messageGroupId.takeUnless { batch }
    val messageDeduplicationId: String? = messageDeduplicationId.takeUnless { batch }

    init {
        require(batchSize >= 0) { "batchSize must not be negative." }
        when (stage) {
            SqsObservationStage.RECEIVE ->
                require(initialAttempt == null || initialAttempt >= 1) {
                    "initialAttempt must be greater than or equal to 1 when present."
                }

            SqsObservationStage.PROCESS,
            SqsObservationStage.ACKNOWLEDGEMENT,
            -> require(initialAttempt != null && initialAttempt >= 1) {
                "initialAttempt must be greater than or equal to 1 for $stage."
            }
        }
    }

    override fun toString(): String =
        "SqsObservationMetadata(listenerId=$listenerId, queueName=$queueName, " +
            "stage=$stage, batch=$batch, batchSize=$batchSize, " +
            "acknowledgementAction=$acknowledgementAction, delivery=$delivery)"

    companion object {
        private const val UNKNOWN: String = "unknown"

        @Suppress("MayBeConstant", "SerialVersionUIDInSerializableClass")
        @JvmField
        val serialVersionUID: Long = 1L
    }
}

/**
 * SQS 관찰에 필요한 제한된 mutable 상태를 보관하는 Micrometer context입니다.
 *
 * 사용자에게는 읽기 가능한 metadata와 attempt만 제공하고, 수명 주기 구현은 내부 setter로
 * outcome, 재시도 및 acknowledgement 집계를 갱신합니다.
 */
class SqsObservationContext internal constructor(
    val metadata: SqsObservationMetadata,
) : Observation.Context() {

    var outcome: SqsObservationOutcome = SqsObservationOutcome.UNKNOWN
        internal set

    var retryCount: Int = 0
        internal set

    var acknowledgementSuccessCount: Int = 0
        internal set

    var acknowledgementFailureCount: Int = 0
        internal set

    val attempt: Int?
        get() = currentAttempt

    internal var currentAttempt: Int? = metadata.initialAttempt
    internal var failureStage: String? = null
}

/**
 * SQS `ApproximateReceiveCount`를 안전한 delivery 분류로 변환합니다.
 */
internal fun resolveSqsObservationDelivery(receiveCount: String?): SqsObservationDelivery =
    receiveCount
        ?.trim()
        ?.toLongOrNull()
        ?.takeIf { it > 0 }
        ?.let { count ->
            if (count == 1L) SqsObservationDelivery.FIRST else SqsObservationDelivery.REDELIVERED
        }
        ?: SqsObservationDelivery.UNKNOWN

/**
 * queue URL에서 안전한 raw 마지막 path segment만 관찰 이름으로 허용합니다.
 *
 * URI parser의 raw path를 사용하므로 percent decoding을 수행하지 않으며, query, fragment,
 * user-info, host 및 계정 식별자는 결과에 포함되지 않습니다.
 */
internal fun resolveSqsObservationQueueName(queueUrl: String?): String {
    val rawPath = queueUrl
        ?.takeIf { it.isNotBlank() }
        ?.let { value ->
            try {
                URI(value).rawPath
            } catch (_: URISyntaxException) {
                null
            }
        }
        ?: return "unknown"

    val segment = rawPath.substringAfterLast('/')
    return segment.takeIf {
        SQS_QUEUE_NAME_PATTERN.matches(it) &&
            !SQS_ACCOUNT_ID_PATTERN.matches(it.removeSuffix(".fifo"))
    } ?: "unknown"
}

internal class SqsObservationQueueNameCache(
    private val sanitizer: (String?) -> String = ::resolveSqsObservationQueueName,
) {
    @Volatile
    private var cached: Entry? = null

    fun resolve(queueUrl: String): String {
        cached?.takeIf { it.queueUrl == queueUrl }?.let { return it.queueName }
        return synchronized(this) {
            cached?.takeIf { it.queueUrl == queueUrl }?.queueName
                ?: sanitizer(queueUrl).also { queueName ->
                    cached = Entry(queueUrl, queueName)
                }
        }
    }

    private data class Entry(
        val queueUrl: String,
        val queueName: String,
    )
}

private fun requireResolvedSqsObservationQueueName(queueName: String) {
    require(
        queueName == "unknown" ||
            SQS_QUEUE_NAME_PATTERN.matches(queueName) &&
            !SQS_ACCOUNT_ID_PATTERN.matches(queueName.removeSuffix(".fifo")),
    ) { "queueName must already satisfy the SQS observation allowlist." }
}

private val SQS_QUEUE_NAME_PATTERN = Regex("(?=.{1,80}$)[A-Za-z0-9_-]+(?:\\.fifo)?")
private val SQS_ACCOUNT_ID_PATTERN = Regex("\\d{12}")
