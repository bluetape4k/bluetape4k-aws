package io.bluetape4k.aws.kinesis

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

        private const val serialVersionUID: Long = 5689270817494739911L
    }
}

/** 기존 Java SDK v2 event를 canonical schema v1 값으로 변환합니다. */
fun KinesisFlowEvent.toCanonicalObservations(): List<KinesisCanonicalObservation> = when (this) {
    is KinesisFlowEvent.Shard -> {
        if (eventKind == "shard" && outcome == "completed") {
            listOf(
                canonicalObservation("checkpoint", "success", reason = "shard_end"),
                canonicalObservation("shard", "success"),
            )
        } else {
            listOf(canonicalObservation(eventKind, canonicalOutcome(outcome), reason, retryClass))
        }
    }

    is KinesisFlowEvent.Lease -> listOf(
        canonicalObservation(
            eventKind = eventKind,
            outcome = when (outcome) {
                "acquired" -> "started"
                else -> canonicalOutcome(outcome)
            },
            reason = reason,
            retryClass = retryClass,
        )
    )

    is KinesisFlowEvent.Batch -> listOf(
        canonicalObservation(eventKind, canonicalOutcome(outcome), reason, retryClass, count = recordCount)
    )

    is KinesisFlowEvent.Checkpoint -> listOf(
        canonicalObservation("record", canonicalOutcome(outcome), reason, retryClass, count = 1)
    )

    is KinesisFlowEvent.Discovery -> listOf(
        KinesisCanonicalObservation(
            eventKind = eventKind,
            outcome = canonicalOutcome(outcome),
            reason = reason,
            retryClass = retryClass,
            streamToken = streamToken,
            count = shardCount,
        )
    )

    is KinesisFlowEvent.Retry -> listOf(
        canonicalObservation(
            eventKind = eventKind,
            outcome = canonicalOutcome(outcome),
            reason = reason,
            retryClass = retryClass,
            retryCount = attempt,
        )
    )
}

private fun KinesisFlowEvent.canonicalObservation(
    eventKind: String,
    outcome: String,
    reason: String? = null,
    retryClass: String? = null,
    count: Int? = null,
    retryCount: Int? = null,
): KinesisCanonicalObservation {
    val event = this
    return KinesisCanonicalObservation(
        eventKind = eventKind,
        outcome = outcome,
        reason = reason,
        retryClass = retryClass,
        streamToken = event.canonicalStreamToken,
        shardToken = event.canonicalShardToken,
        ownerToken = event.canonicalOwnerToken,
        count = count,
        retryCount = retryCount,
    )
}

private val KinesisFlowEvent.canonicalStreamToken: String?
    get() = when (this) {
        is KinesisFlowEvent.Shard -> streamToken
        is KinesisFlowEvent.Lease -> streamToken
        is KinesisFlowEvent.Batch -> streamToken
        is KinesisFlowEvent.Checkpoint -> streamToken
        is KinesisFlowEvent.Retry -> streamToken
        is KinesisFlowEvent.Discovery -> streamToken
    }

private val KinesisFlowEvent.canonicalShardToken: String?
    get() = when (this) {
        is KinesisFlowEvent.Shard -> shardToken
        is KinesisFlowEvent.Lease -> shardToken
        is KinesisFlowEvent.Batch -> shardToken
        is KinesisFlowEvent.Checkpoint -> shardToken
        is KinesisFlowEvent.Retry -> shardToken
        is KinesisFlowEvent.Discovery -> null
    }

private val KinesisFlowEvent.canonicalOwnerToken: String?
    get() = when (this) {
        is KinesisFlowEvent.Shard -> ownerToken
        is KinesisFlowEvent.Lease -> ownerToken
        else -> null
    }

private fun canonicalOutcome(outcome: String): String = when (outcome) {
    "started" -> "started"
    "failed" -> "failed"
    "lost" -> "lost"
    else -> "success"
}

private val CANONICAL_KINESIS_TOKEN = Regex("[0-9a-f]{${KinesisCanonicalObservation.TOKEN_LENGTH}}")

private fun requireCanonicalKinesisToken(token: String) {
    require(CANONICAL_KINESIS_TOKEN.matches(token)) {
        "canonical Kinesis identifiers must be ${KinesisCanonicalObservation.TOKEN_LENGTH}-character redacted tokens"
    }
}
