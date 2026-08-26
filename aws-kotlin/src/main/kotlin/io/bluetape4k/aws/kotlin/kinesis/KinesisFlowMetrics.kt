package io.bluetape4k.aws.kotlin.kinesis

import java.io.Serializable
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import kotlin.time.Duration

/** Kinesis consumer 운영 이벤트를 전달하는 callback SPI입니다. */
fun interface KinesisFlowMetrics {
    /** payload 없는 bounded 이벤트를 비동기적으로 관측합니다. */
    suspend fun onEvent(event: KinesisFlowEvent)
}

/** metrics callback을 사용하지 않는 기본 구현입니다. */
object NoopKinesisFlowMetrics : KinesisFlowMetrics {
    override suspend fun onEvent(event: KinesisFlowEvent) = Unit
}

/**
 * payload·credential·request token을 포함하지 않는 bounded metrics 이벤트입니다.
 *
 * `sealed` subtype을 추가하면 exhaustive `when` 소비자에 source break가 생기므로
 * 다음 major version에서만 subtype을 추가할 수 있습니다.
 */
sealed interface KinesisFlowEvent : Serializable {
    val eventKind: EventKind
    val outcome: Outcome
    val reason: Reason?
    val retryClass: RetryClass?
    val streamToken: String?
    val shardToken: String?
    val ownerToken: String?
    val count: Int?
    val duration: Duration?
    val retryCount: Int?

    /** 모든 consumer 상태 변화를 표현하는 bounded 관측 이벤트입니다. */
    data class Observation(
        override val eventKind: EventKind,
        override val outcome: Outcome,
        override val reason: Reason? = null,
        override val retryClass: RetryClass? = null,
        override val streamToken: String? = null,
        override val shardToken: String? = null,
        override val ownerToken: String? = null,
        override val count: Int? = null,
        override val duration: Duration? = null,
        override val retryCount: Int? = null,
    ) : KinesisFlowEvent {
        init {
            streamToken?.let(::requireRedactedToken)
            shardToken?.let(::requireRedactedToken)
            ownerToken?.let(::requireRedactedToken)
            require(count == null || count >= 0)
            require(retryCount == null || retryCount >= 0)
            require(duration == null || !duration.isNegative())
        }

        companion object {
            private const val serialVersionUID: Long = 1L
        }
    }

    /** 이벤트 종류는 고정된 low-cardinality 집합입니다. */
    enum class EventKind { DISCOVERY, SHARD, BATCH, RECORD, LEASE, CHECKPOINT, RETRY }

    /** 이벤트 결과입니다. */
    enum class Outcome { STARTED, SUCCESS, FAILED, SKIPPED, LOST }

    /** 실패·종료 이유를 표현하는 고정 집합입니다. */
    enum class Reason { EMPTY, SHARD_END, LEASE_BUSY, LEASE_LOST, ITERATOR_EXPIRED, THROTTLED, ERROR, CANCELLED }

    /** retry 분류를 표현하는 고정 집합입니다. */
    enum class RetryClass { DISCOVERY, ITERATOR, THROTTLE }

    companion object {
        const val MAX_TOKEN_LENGTH: Int = 64

        private val REDACTED_TOKEN = Regex("[0-9a-f]{64}")

        private fun requireRedactedToken(token: String): String {
            require(token.length == MAX_TOKEN_LENGTH && REDACTED_TOKEN.matches(token)) {
                "metrics identifiers must be SHA-256 redacted tokens"
            }
            return token
        }

        /** 원본 식별자를 metrics/log에 노출하지 않는 deterministic token으로 바꿉니다. */
        fun redactedToken(value: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(StandardCharsets.UTF_8))
            return digest.joinToString(separator = "") { byte -> "%02x".format(byte) }
                .take(MAX_TOKEN_LENGTH)
        }
    }
}
