package io.bluetape4k.aws.kinesis

import java.io.Serializable
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * payload·credential·request token을 포함하지 않는 consumer 관측 이벤트입니다.
 *
 * 식별자는 생성 시 deterministic redacted token으로 변환됩니다. sealed subtype 추가는
 * exhaustive `when` 소비자에게 source-breaking이므로 다음 major version에서만 가능합니다.
 */
sealed interface KinesisFlowEvent : Serializable {
    val eventKind: String
    val outcome: String
    val reason: String?
    val retryClass: String?

    data class Shard(
        val streamToken: String,
        val shardToken: String,
        val ownerToken: String,
        override val eventKind: String,
        override val outcome: String,
        override val reason: String? = null,
        override val retryClass: String? = null,
    ) : KinesisFlowEvent {
        init {
            validateKinesisFlowEvent(eventKind, outcome, reason, retryClass)
            requireKinesisMetricToken(streamToken)
            requireKinesisMetricToken(shardToken)
            requireKinesisMetricToken(ownerToken)
        }
    }

    data class Lease(
        val streamToken: String,
        val shardToken: String,
        val ownerToken: String,
        override val eventKind: String = "lease",
        override val outcome: String,
        override val reason: String? = null,
        override val retryClass: String? = null,
    ) : KinesisFlowEvent {
        init {
            validateKinesisFlowEvent(eventKind, outcome, reason, retryClass)
            requireKinesisMetricToken(streamToken)
            requireKinesisMetricToken(shardToken)
            requireKinesisMetricToken(ownerToken)
        }
    }

    data class Batch(
        val streamToken: String,
        val shardToken: String,
        val recordCount: Int,
        override val eventKind: String = "batch",
        override val outcome: String = "read",
        override val reason: String? = null,
        override val retryClass: String? = null,
    ) : KinesisFlowEvent {
        init {
            validateKinesisFlowEvent(eventKind, outcome, reason, retryClass)
            requireKinesisMetricToken(streamToken)
            requireKinesisMetricToken(shardToken)
            require(recordCount in 0..MAX_KINESIS_METRIC_COUNT) {
                "recordCount must be in 0..$MAX_KINESIS_METRIC_COUNT"
            }
        }
    }

    data class Checkpoint(
        val streamToken: String,
        val shardToken: String,
        val sequenceToken: String,
        override val eventKind: String = "checkpoint",
        override val outcome: String = "saved",
        override val reason: String? = null,
        override val retryClass: String? = null,
    ) : KinesisFlowEvent {
        init {
            validateKinesisFlowEvent(eventKind, outcome, reason, retryClass)
            requireKinesisMetricToken(streamToken)
            requireKinesisMetricToken(shardToken)
            requireKinesisMetricToken(sequenceToken)
        }
    }

    data class Discovery(
        val streamToken: String,
        val page: Int,
        val shardCount: Int,
        override val eventKind: String = "discovery",
        override val outcome: String = "page",
        override val reason: String? = null,
        override val retryClass: String? = null,
    ) : KinesisFlowEvent {
        init {
            validateKinesisFlowEvent(eventKind, outcome, reason, retryClass)
            requireKinesisMetricToken(streamToken)
            require(page >= 1) { "page must be >= 1" }
            require(shardCount in 0..MAX_KINESIS_METRIC_COUNT) {
                "shardCount must be in 0..$MAX_KINESIS_METRIC_COUNT"
            }
        }
    }

    data class Retry(
        val streamToken: String,
        val shardToken: String?,
        val attempt: Int,
        override val eventKind: String = "retry",
        override val outcome: String = "retrying",
        override val reason: String? = null,
        override val retryClass: String? = null,
    ) : KinesisFlowEvent {
        init {
            validateKinesisFlowEvent(eventKind, outcome, reason, retryClass)
            requireKinesisMetricToken(streamToken)
            shardToken?.let(::requireKinesisMetricToken)
            require(attempt >= 1) { "attempt must be >= 1" }
        }
    }
}

/** consumer event를 비동기로 전달하는 callback입니다. callback 오류는 consumer 오류로 전파됩니다. */
fun interface KinesisFlowMetrics {
    suspend fun onEvent(event: KinesisFlowEvent)
}

/** 관측이 필요하지 않을 때 사용하는 no-op metrics입니다. */
object NoopKinesisFlowMetrics : KinesisFlowMetrics {
    override suspend fun onEvent(event: KinesisFlowEvent) = Unit
}

/** callback 기반 metrics adapter입니다. 이벤트 자체는 이미 payload-free/redacted 상태입니다. */
class LambdaKinesisFlowMetrics(
    private val callback: suspend (KinesisFlowEvent) -> Unit,
) : KinesisFlowMetrics {
    override suspend fun onEvent(event: KinesisFlowEvent) = callback(event)
}

internal fun redactedKinesisToken(value: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(StandardCharsets.UTF_8))
    return digest.joinToString("") { byte -> "%02x".format(byte) }.take(MAX_KINESIS_METRIC_TOKEN_LENGTH)
}

private const val MAX_KINESIS_METRIC_TOKEN_LENGTH: Int = 24
private const val MAX_KINESIS_METRIC_COUNT: Int = 10_000
private val KINESIS_METRIC_TOKEN = Regex("[0-9a-f]{${MAX_KINESIS_METRIC_TOKEN_LENGTH}}")
private val KINESIS_METRIC_EVENT_KINDS = setOf("shard", "batch", "checkpoint", "discovery", "retry", "lease")
private val KINESIS_METRIC_OUTCOMES =
    setOf("started", "read", "saved", "page", "retrying", "completed", "acquired", "renewed", "failed", "lost")
private val KINESIS_METRIC_REASONS =
    setOf("iterator_expired", "throttled", "cancelled", "lease_lost", "shard_end", "error")
private val KINESIS_METRIC_RETRY_CLASSES = setOf("iterator", "throttle", "discovery")

private fun requireKinesisMetricToken(token: String): String {
    require(KINESIS_METRIC_TOKEN.matches(token)) {
        "metrics identifiers must be redacted ${MAX_KINESIS_METRIC_TOKEN_LENGTH}-character tokens"
    }
    return token
}

private fun validateKinesisFlowEvent(
    eventKind: String,
    outcome: String,
    reason: String?,
    retryClass: String?,
) {
    require(eventKind in KINESIS_METRIC_EVENT_KINDS) { "unsupported metrics eventKind=$eventKind" }
    require(outcome in KINESIS_METRIC_OUTCOMES) { "unsupported metrics outcome=$outcome" }
    require(reason == null || reason in KINESIS_METRIC_REASONS) { "unsupported metrics reason=$reason" }
    require(retryClass == null || retryClass in KINESIS_METRIC_RETRY_CLASSES) {
        "unsupported metrics retryClass=$retryClass"
    }
}
