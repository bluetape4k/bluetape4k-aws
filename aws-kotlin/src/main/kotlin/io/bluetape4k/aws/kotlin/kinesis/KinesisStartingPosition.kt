package io.bluetape4k.aws.kotlin.kinesis

import io.bluetape4k.support.requireNotBlank
import java.io.ObjectInputStream
import java.io.Serializable
import java.time.Instant

/**
 * Starting position for a Kinesis shard iterator.
 *
 * ## Variants
 * - [TrimHorizon] — all records in the shard from the oldest available record.
 * - [Latest] — records written after the iterator is obtained. **Caution:** if the iterator
 *   expires (5-minute TTL) before the first checkpoint is captured, recovery is impossible
 *   without data loss. [recordFlow] will throw [aws.sdk.kotlin.services.kinesis.model.ExpiredIteratorException]
 *   immediately in this case rather than silently skipping records.
 * - [AtSequenceNumber] — the record with the given sequence number (inclusive).
 * - [AfterSequenceNumber] — records after the given sequence number (exclusive).
 * - [AtTimestamp] — records at or after the given timestamp.
 *
 * ## Serialization
 * All variants implement [Serializable]. Singleton variants ([TrimHorizon], [Latest]) use
 * `readResolve()` to preserve the singleton contract after deserialization. Variants with
 * a `sequenceNumber` field validate the field in `readObject()` because Java deserialization
 * bypasses `init` blocks.
 */
sealed interface KinesisStartingPosition : Serializable {

    /**
     * Reads from the oldest available record in the shard.
     */
    data object TrimHorizon : KinesisStartingPosition {
        private const val serialVersionUID: Long = 1L
        private fun readResolve(): Any = TrimHorizon
    }

    /**
     * Reads records written after the iterator is obtained.
     *
     * **Warning:** If the shard iterator expires (5-minute TTL) before any record is processed,
     * there is no sequence-number checkpoint to resume from. In this case [recordFlow] throws
     * immediately rather than re-fetching a new `Latest` iterator (which would silently skip
     * all records written during the TTL window).
     */
    data object Latest : KinesisStartingPosition {
        private const val serialVersionUID: Long = 1L
        private fun readResolve(): Any = Latest
    }

    /**
     * Reads the record with the given [sequenceNumber] (inclusive).
     *
     * @param sequenceNumber Non-blank Kinesis sequence number string.
     */
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
            private const val serialVersionUID: Long = 1L
        }
    }

    /**
     * Reads records after the given [sequenceNumber] (exclusive).
     *
     * @param sequenceNumber Non-blank Kinesis sequence number string.
     */
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
            private const val serialVersionUID: Long = 1L
        }
    }

    /**
     * Reads records at or after the given [timestamp].
     *
     * @param timestamp The starting point in time. Nanosecond precision is preserved when
     *   converting to the AWS SDK [aws.smithy.kotlin.runtime.time.Instant] via
     *   [aws.smithy.kotlin.runtime.time.Instant.fromEpochSeconds].
     */
    data class AtTimestamp(val timestamp: Instant) : KinesisStartingPosition {
        companion object {
            private const val serialVersionUID: Long = 1L
        }
    }
}
