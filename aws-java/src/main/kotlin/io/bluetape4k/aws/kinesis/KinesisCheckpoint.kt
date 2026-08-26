package io.bluetape4k.aws.kinesis

import java.io.ObjectInputStream
import java.io.Serializable

/** durable shard checkpoint입니다. sequence checkpoint는 inclusive resume을 의미합니다. */
sealed interface KinesisCheckpoint : Serializable {

    /** 마지막으로 downstream emit이 끝난 Kinesis sequence number입니다. */
    data class Sequence(val sequenceNumber: String) : KinesisCheckpoint {
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

    /** shard의 ending sequence까지 소비해 더 이상 재실행하지 않는 terminal checkpoint입니다. */
    data object ShardEnd : KinesisCheckpoint {
        private const val serialVersionUID: Long = 1L

        @Suppress("unused")
        private fun readResolve(): Any = ShardEnd
    }
}
