package io.bluetape4k.aws.spring.kinesis

import io.bluetape4k.support.requireNotBlank
import java.io.ObjectInputStream
import java.io.Serializable
import java.time.Instant

/**
 * Starting position for a Kinesis shard iterator.
 *
 * ## Contract
 *
 * This type is local to Spring Boot Kinesis support and maps to AWS SDK for Java
 * v2 `ShardIteratorType` values inside [KinesisCoroutinesTemplate].
 */
sealed interface KinesisStartingPosition : Serializable {

    /** Read from the oldest available record in the shard. */
    data object TrimHorizon : KinesisStartingPosition {
        private const val serialVersionUID: Long = 1L
        private fun readResolve(): Any = TrimHorizon
    }

    /** Read records written after the iterator is obtained. */
    data object Latest : KinesisStartingPosition {
        private const val serialVersionUID: Long = 1L
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
            private const val serialVersionUID: Long = 1L
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
            private const val serialVersionUID: Long = 1L
        }
    }

    /** Read records at or after [timestamp]. */
    data class AtTimestamp(val timestamp: Instant) : KinesisStartingPosition {
        companion object {
            private const val serialVersionUID: Long = 1L
        }
    }
}
