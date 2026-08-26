package io.bluetape4k.aws.kinesis

import java.io.ObjectInputStream
import java.io.Serializable
import java.time.Instant

/**
 * Kinesis 샤드 이터레이터의 시작 위치입니다.
 *
 * `AtSequenceNumber`와 checkpoint 재개는 마지막 sequence를 포함합니다. 따라서 consumer의
 * 기본 delivery semantics는 at-least-once이며, 중복 제거가 필요하면 호출자가 처리합니다.
 */
sealed interface KinesisStartingPosition : Serializable {

    /** 보존 중인 가장 오래된 record부터 읽습니다. */
    data object TrimHorizon : KinesisStartingPosition {
        private const val serialVersionUID: Long = 1L

        @Suppress("unused")
        private fun readResolve(): Any = TrimHorizon
    }

    /** 이터레이터를 얻은 뒤 기록된 record부터 읽습니다. */
    data object Latest : KinesisStartingPosition {
        private const val serialVersionUID: Long = 1L

        @Suppress("unused")
        private fun readResolve(): Any = Latest
    }

    /** 지정한 sequence number의 record부터 읽습니다(포함). */
    data class AtSequenceNumber(val sequenceNumber: String) : KinesisStartingPosition {
        init {
            sequenceNumber.requireKinesisSequence()
        }

        @Suppress("unused")
        private fun readObject(input: ObjectInputStream) {
            input.defaultReadObject()
            sequenceNumber.requireKinesisSequence()
        }

        companion object {
            private const val serialVersionUID: Long = 1L
        }
    }

    /** 지정한 sequence number 다음의 record부터 읽습니다(제외). */
    data class AfterSequenceNumber(val sequenceNumber: String) : KinesisStartingPosition {
        init {
            sequenceNumber.requireKinesisSequence()
        }

        @Suppress("unused")
        private fun readObject(input: ObjectInputStream) {
            input.defaultReadObject()
            sequenceNumber.requireKinesisSequence()
        }

        companion object {
            private const val serialVersionUID: Long = 1L
        }
    }

    /** 지정한 시각 이후의 record부터 읽습니다. */
    data class AtTimestamp(val timestamp: Instant) : KinesisStartingPosition {
        init {
            requireNotNull(timestamp) { "timestamp must not be null" }
        }

        @Suppress("unused")
        private fun readObject(input: ObjectInputStream) {
            input.defaultReadObject()
            requireNotNull(timestamp) { "timestamp must not be null" }
        }

        companion object {
            private const val serialVersionUID: Long = 1L
        }
    }
}
