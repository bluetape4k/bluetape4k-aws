package io.bluetape4k.aws.spring.kinesis

import io.bluetape4k.support.requireNotBlank
import java.io.ObjectInputStream
import java.io.Serializable
import java.time.Instant

/**
 * Kinesis 샤드 반복자의 시작 위치입니다.
 *
 * ## 계약
 *
 * 이 타입은 Spring Boot Kinesis 지원에 속하며 [KinesisCoroutinesTemplate] 안에서
 * AWS SDK for Java v2 `ShardIteratorType` 값으로 매핑됩니다.
 */
sealed interface KinesisStartingPosition : Serializable {

    /** 샤드에서 사용할 수 있는 가장 오래된 레코드부터 읽습니다. */
    data object TrimHorizon : KinesisStartingPosition {
        private const val serialVersionUID: Long = 1L
        private fun readResolve(): Any = TrimHorizon
    }

    /** 반복자를 가져온 뒤 작성된 레코드를 읽습니다. */
    data object Latest : KinesisStartingPosition {
        private const val serialVersionUID: Long = 1L
        private fun readResolve(): Any = Latest
    }

    /** [sequenceNumber]에 해당하는 레코드부터 읽습니다. */
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

    /** [sequenceNumber] 다음 레코드부터 읽습니다. */
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

    /** [timestamp] 이후의 레코드를 해당 시각을 포함해 읽습니다. */
    data class AtTimestamp(val timestamp: Instant) : KinesisStartingPosition {
        companion object {
            private const val serialVersionUID: Long = 1L
        }
    }
}
