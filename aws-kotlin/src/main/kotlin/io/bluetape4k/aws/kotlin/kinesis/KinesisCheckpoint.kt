package io.bluetape4k.aws.kotlin.kinesis

import io.bluetape4k.support.requireNotBlank
import java.io.ObjectInputStream
import java.io.Serializable

/**
 * 한 샤드에서 호출자에게 전달된 위치입니다.
 *
 * [Sequence]는 재시작 시 해당 sequence를 포함해 재생하는 at-least-once 경계이고,
 * [ShardEnd]는 부모 샤드가 완전히 종료됐음을 durable하게 표시하는 terminal 상태입니다.
 */
sealed interface KinesisCheckpoint : Serializable {

    /** 마지막으로 성공한 record의 sequence number입니다. */
    data class Sequence(val sequenceNumber: String) : KinesisCheckpoint {
        init {
            sequenceNumber.validateSequenceNumber()
        }

        @Suppress("UnusedPrivateMember")
        private fun readObject(stream: ObjectInputStream) {
            stream.defaultReadObject()
            sequenceNumber.validateSequenceNumber()
        }

        companion object {
            private const val serialVersionUID: Long = 1L
        }
    }

    /** 샤드의 모든 record가 처리된 terminal checkpoint입니다. */
    data object ShardEnd : KinesisCheckpoint {
        private const val serialVersionUID: Long = 1L

        private fun readResolve(): Any = ShardEnd
    }
}

/** Kinesis sequence number의 입력 형식과 길이를 검증합니다. */
internal fun String.validateSequenceNumber(): String {
    requireNotBlank("sequenceNumber")
    require(length <= KINESIS_SEQUENCE_MAX_LENGTH) {
        "sequenceNumber length must be <= $KINESIS_SEQUENCE_MAX_LENGTH, but was $length"
    }
    require(none { it.isISOControl() }) {
        "sequenceNumber must not contain control characters"
    }
    return this
}

internal const val KINESIS_SEQUENCE_MAX_LENGTH: Int = 1_024
