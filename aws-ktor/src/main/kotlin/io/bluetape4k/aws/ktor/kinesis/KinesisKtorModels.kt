package io.bluetape4k.aws.ktor.kinesis

import io.bluetape4k.support.requireGe
import io.bluetape4k.support.requireInRange
import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.support.requirePositiveNumber
import software.amazon.awssdk.core.SdkBytes
import software.amazon.awssdk.services.kinesis.model.ShardIteratorType
import java.io.ObjectInputStream
import java.io.Serializable
import java.time.Duration
import java.time.Instant

/**
 * Request for publishing one record to a Kinesis stream.
 *
 * ## Contract
 *
 * Wraps stream name and partition key as named values so Ktor application code
 * does not accidentally swap same-typed parameters.
 */
data class KinesisPutRecordRequest(
    val streamName: String,
    val partitionKey: String,
    val data: SdkBytes,
): Serializable {

    init {
        streamName.requireNotBlank("streamName")
        partitionKey.requireNotBlank("partitionKey")
    }

    companion object {
        private const val serialVersionUID: Long = 7178086878245536883L
    }
}

/**
 * Request for obtaining a Kinesis shard iterator.
 */
data class KinesisShardIteratorRequest(
    val streamName: String,
    val shardId: String,
    val type: ShardIteratorType = ShardIteratorType.TRIM_HORIZON,
    val startingSequenceNumber: String? = null,
): Serializable {

    init {
        streamName.requireNotBlank("streamName")
        shardId.requireNotBlank("shardId")
        startingSequenceNumber?.requireNotBlank("startingSequenceNumber")
    }

    companion object {
        private const val serialVersionUID: Long = 6767831282132268415L
    }
}

/**
 * Request for collecting records from one Kinesis shard as a cold Flow.
 *
 * ## Contract
 *
 * The Flow is caller-collected, single-shard, and explicit. This type does not
 * imply a listener container, lease table, checkpoint store, or background
 * consumer.
 */
data class KinesisRecordFlowRequest(
    val streamName: String,
    val shardId: String,
    val position: KinesisStartingPosition = KinesisStartingPosition.TrimHorizon,
    val options: KinesisRecordFlowOptions = KinesisRecordFlowOptions(),
): Serializable {

    init {
        streamName.requireNotBlank("streamName")
        shardId.requireNotBlank("shardId")
    }

    companion object {
        private const val serialVersionUID: Long = -5620050993366478688L
    }
}

/**
 * Declares a configured Kinesis stream for [KinesisKtorOperations.createConfiguredStream].
 */
data class KinesisKtorStream(
    val shardCount: Int = 1,
): Serializable {

    init {
        shardCount.requirePositiveNumber("shardCount")
    }

    companion object {
        private const val serialVersionUID: Long = -7161818684508501711L
    }
}

/**
 * Polling and retry options for [KinesisKtorOperations.recordFlow].
 */
data class KinesisRecordFlowOptions(
    val batchLimit: Int = DEFAULT_BATCH_LIMIT,
    val pollInterval: Duration = DEFAULT_POLL_INTERVAL,
    val emptyBackoff: Duration = DEFAULT_EMPTY_BACKOFF,
    val maxIteratorRetries: Int = DEFAULT_MAX_ITERATOR_RETRIES,
    val maxThrottleRetries: Int = DEFAULT_MAX_THROTTLE_RETRIES,
    val initialThrottleBackoff: Duration = DEFAULT_INITIAL_THROTTLE_BACKOFF,
    val maxThrottleBackoff: Duration = DEFAULT_MAX_THROTTLE_BACKOFF,
    val jitterRatio: Double = DEFAULT_JITTER_RATIO,
): Serializable {

    init {
        batchLimit.requireInRange(1, MAX_KINESIS_BATCH_LIMIT, "batchLimit")
        require(!pollInterval.isNegative) { "pollInterval must not be negative." }
        require(!emptyBackoff.isNegative) { "emptyBackoff must not be negative." }
        maxIteratorRetries.requireGe(0, "maxIteratorRetries")
        maxThrottleRetries.requireGe(0, "maxThrottleRetries")
        require(!initialThrottleBackoff.isNegative) { "initialThrottleBackoff must not be negative." }
        require(!maxThrottleBackoff.isNegative) { "maxThrottleBackoff must not be negative." }
        jitterRatio.requireInRange(0.0, 1.0, "jitterRatio")
    }

    companion object {
        private const val serialVersionUID: Long = -3839026228853038469L

        const val MAX_KINESIS_BATCH_LIMIT: Int = 10_000
        const val DEFAULT_BATCH_LIMIT: Int = 100
        val DEFAULT_POLL_INTERVAL: Duration = Duration.ofMillis(200)
        val DEFAULT_EMPTY_BACKOFF: Duration = Duration.ofSeconds(1)
        const val DEFAULT_MAX_ITERATOR_RETRIES: Int = 3
        const val DEFAULT_MAX_THROTTLE_RETRIES: Int = 5
        val DEFAULT_INITIAL_THROTTLE_BACKOFF: Duration = Duration.ofMillis(500)
        val DEFAULT_MAX_THROTTLE_BACKOFF: Duration = Duration.ofSeconds(30)
        const val DEFAULT_JITTER_RATIO: Double = 1.0
    }
}

/**
 * Starting position for a Kinesis shard iterator.
 */
sealed interface KinesisStartingPosition : Serializable {

    /** Read from the oldest available record in the shard. */
    data object TrimHorizon : KinesisStartingPosition {
        private const val serialVersionUID: Long = -5888197472956153464L
        private fun readResolve(): Any = TrimHorizon
    }

    /** Read records written after the iterator is obtained. */
    data object Latest : KinesisStartingPosition {
        private const val serialVersionUID: Long = -6388982200191080402L
        private fun readResolve(): Any = Latest
    }

    /** Read the record with [sequenceNumber], inclusive. */
    data class AtSequenceNumber(val sequenceNumber: String) : KinesisStartingPosition {

        init {
            sequenceNumber.requireNotBlank("sequenceNumber")
        }

        @Suppress("UnusedPrivateMember")
        private fun readObject(stream: ObjectInputStream) {
            stream.defaultReadObject()
            sequenceNumber.requireNotBlank("sequenceNumber")
        }

        companion object {
            private const val serialVersionUID: Long = 4322339826570150123L
        }
    }

    /** Read records after [sequenceNumber], exclusive. */
    data class AfterSequenceNumber(val sequenceNumber: String) : KinesisStartingPosition {

        init {
            sequenceNumber.requireNotBlank("sequenceNumber")
        }

        @Suppress("UnusedPrivateMember")
        private fun readObject(stream: ObjectInputStream) {
            stream.defaultReadObject()
            sequenceNumber.requireNotBlank("sequenceNumber")
        }

        companion object {
            private const val serialVersionUID: Long = -8598646731640081528L
        }
    }

    /** Read records at or after [timestamp]. */
    data class AtTimestamp(val timestamp: Instant) : KinesisStartingPosition {
        companion object {
            private const val serialVersionUID: Long = 1523906536953919234L
        }
    }
}
