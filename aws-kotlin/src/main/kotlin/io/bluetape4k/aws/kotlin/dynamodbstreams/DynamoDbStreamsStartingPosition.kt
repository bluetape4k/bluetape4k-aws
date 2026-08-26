package io.bluetape4k.aws.kotlin.dynamodbstreams

import io.bluetape4k.support.requireNotBlank
import java.io.ObjectInputStream
import java.io.Serializable

/**
 * DynamoDB Streams shard iterator의 시작 위치입니다.
 *
 * DynamoDB Streams API가 제공하는 네 가지 iterator type을 1:1로 표현합니다.
 * Kinesis와 달리 timestamp 기반 iterator는 지원하지 않습니다.
 */
sealed interface DynamoDbStreamsStartingPosition : Serializable {

    /** 보존 중인 가장 오래된 record부터 읽습니다. */
    data object TrimHorizon : DynamoDbStreamsStartingPosition {
        private const val serialVersionUID: Long = 1L

        @Suppress("unused")
        private fun readResolve(): Any = TrimHorizon
    }

    /** iterator를 얻은 뒤 기록된 record부터 읽습니다. */
    data object Latest : DynamoDbStreamsStartingPosition {
        private const val serialVersionUID: Long = 1L

        @Suppress("unused")
        private fun readResolve(): Any = Latest
    }

    /** 지정한 sequence number의 record를 포함해 읽습니다. */
    data class AtSequenceNumber(val sequenceNumber: String) : DynamoDbStreamsStartingPosition {
        init {
            sequenceNumber.requireNotBlank("sequenceNumber")
        }

        @Suppress("unused")
        private fun readObject(input: ObjectInputStream) {
            input.defaultReadObject()
            sequenceNumber.requireNotBlank("sequenceNumber")
        }

        companion object {
            private const val serialVersionUID: Long = 1L
        }
    }

    /** 지정한 sequence number 다음의 record부터 읽습니다. */
    data class AfterSequenceNumber(val sequenceNumber: String) : DynamoDbStreamsStartingPosition {
        init {
            sequenceNumber.requireNotBlank("sequenceNumber")
        }

        @Suppress("unused")
        private fun readObject(input: ObjectInputStream) {
            input.defaultReadObject()
            sequenceNumber.requireNotBlank("sequenceNumber")
        }

        companion object {
            private const val serialVersionUID: Long = 1L
        }
    }
}
