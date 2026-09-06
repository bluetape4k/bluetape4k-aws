package io.bluetape4k.aws.kotlin.kinesis

import java.io.Serializable

/** SDK 구현과 무관한 Kinesis consumer 관측 schema v1 값입니다. */
data class KinesisCanonicalObservation(
    val eventKind: String,
    val outcome: String,
    val reason: String? = null,
    val retryClass: String? = null,
    val streamToken: String? = null,
    val shardToken: String? = null,
    val ownerToken: String? = null,
    val count: Int? = null,
    val retryCount: Int? = null,
): Serializable {

    init {
        require(eventKind in EVENT_KINDS) { "unsupported canonical Kinesis eventKind" }
        require(outcome in OUTCOMES) { "unsupported canonical Kinesis outcome" }
        require(reason == null || reason in REASONS) { "unsupported canonical Kinesis reason" }
        require(retryClass == null || retryClass in RETRY_CLASSES) {
            "unsupported canonical Kinesis retryClass"
        }
        listOfNotNull(streamToken, shardToken, ownerToken).forEach(::requireCanonicalKinesisToken)
        require(count == null || count in 0..MAX_COUNT) { "count must be in 0..$MAX_COUNT" }
        require(retryCount == null || retryCount in 0..MAX_COUNT) { "retryCount must be in 0..$MAX_COUNT" }
    }

    companion object {
        const val SCHEMA_VERSION: Int = 1
        const val TOKEN_LENGTH: Int = 24
        const val MAX_COUNT: Int = 10_000

        val EVENT_KINDS: Set<String> = setOf("discovery", "shard", "batch", "record", "lease", "checkpoint", "retry")
        val OUTCOMES: Set<String> = setOf("started", "success", "failed", "skipped", "lost")
        val REASONS: Set<String> = setOf(
            "empty",
            "shard_end",
            "lease_busy",
            "lease_lost",
            "iterator_expired",
            "throttled",
            "error",
            "cancelled",
        )
        val RETRY_CLASSES: Set<String> = setOf("discovery", "iterator", "throttle")

        private const val serialVersionUID: Long = -4996514270425790806L
    }
}

/** 기존 AWS Kotlin SDK event를 canonical schema v1 값으로 변환합니다. */
fun KinesisFlowEvent.toCanonicalObservations(): List<KinesisCanonicalObservation> = listOf(
    KinesisCanonicalObservation(
        eventKind = eventKind.name.lowercase(),
        outcome = outcome.name.lowercase(),
        reason = reason?.name?.lowercase(),
        retryClass = retryClass?.toCanonicalValue(),
        streamToken = streamToken?.toCanonicalKinesisToken(),
        shardToken = shardToken?.toCanonicalKinesisToken(),
        ownerToken = ownerToken?.toCanonicalKinesisToken(),
        count = count,
        retryCount = retryCount,
    )
)

private fun KinesisFlowEvent.RetryClass.toCanonicalValue(): String = when (this) {
    KinesisFlowEvent.RetryClass.DISCOVERY -> "discovery"
    KinesisFlowEvent.RetryClass.ITERATOR -> "iterator"
    KinesisFlowEvent.RetryClass.THROTTLE -> "throttle"
}

private fun String.toCanonicalKinesisToken(): String = take(KinesisCanonicalObservation.TOKEN_LENGTH)

private val CANONICAL_KINESIS_TOKEN = Regex("[0-9a-f]{${KinesisCanonicalObservation.TOKEN_LENGTH}}")

private fun requireCanonicalKinesisToken(token: String) {
    require(CANONICAL_KINESIS_TOKEN.matches(token)) {
        "canonical Kinesis identifiers must be ${KinesisCanonicalObservation.TOKEN_LENGTH}-character redacted tokens"
    }
}
