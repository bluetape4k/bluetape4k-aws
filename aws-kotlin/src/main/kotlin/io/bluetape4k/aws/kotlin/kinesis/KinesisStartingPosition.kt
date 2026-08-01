package io.bluetape4k.aws.kotlin.kinesis

import io.bluetape4k.support.requireNotBlank
import java.io.ObjectInputStream
import java.io.Serializable
import java.time.Instant

/**
 * Kinesis 샤드 반복자의 시작 위치입니다.
 *
 * ## 변형
 * - [TrimHorizon] — 샤드에서 사용할 수 있는 가장 오래된 레코드부터 모든 레코드.
 * - [Latest] — 반복자를 얻은 뒤 기록된 레코드. **주의:** 첫 체크포인트를 확보하기 전에 반복자가
 *   만료되면(5분 TTL) 데이터 손실 없이 복구할 수 없습니다. 이 경우 [recordFlow]는 레코드를 조용히
 *   건너뛰지 않고 즉시 [aws.sdk.kotlin.services.kinesis.model.ExpiredIteratorException]을 던집니다.
 * - [AtSequenceNumber] — 지정한 시퀀스 번호의 레코드(포함).
 * - [AfterSequenceNumber] — 지정한 시퀀스 번호 뒤의 레코드(제외).
 * - [AtTimestamp] — 지정한 타임스탬프 이후의 레코드(포함).
 *
 * ## 직렬화
 * 모든 변형은 [Serializable]을 구현합니다. 싱글턴 변형([TrimHorizon], [Latest])은 역직렬화 후에도
 * 싱글턴 계약을 유지하도록 `readResolve()`를 사용합니다. Java 역직렬화는 `init` 블록을 우회하므로
 * `sequenceNumber` 필드가 있는 변형은 `readObject()`에서 필드를 검증합니다.
 */
sealed interface KinesisStartingPosition : Serializable {

    /**
     * 샤드에서 사용할 수 있는 가장 오래된 레코드부터 읽습니다.
     */
    data object TrimHorizon : KinesisStartingPosition {
        private const val serialVersionUID: Long = 1L
        private fun readResolve(): Any = TrimHorizon
    }

    /**
     * 반복자를 얻은 뒤 기록된 레코드를 읽습니다.
     *
     * **경고:** 레코드를 처리하기 전에 샤드 반복자가 만료되면(5분 TTL) 재개할 시퀀스 번호
     * 체크포인트가 없습니다. 이 경우 [recordFlow]는 TTL 동안 기록된 모든 레코드를 조용히 건너뛰는
     * 새 `Latest` 반복자를 가져오지 않고 즉시 예외를 던집니다.
     */
    data object Latest : KinesisStartingPosition {
        private const val serialVersionUID: Long = 1L
        private fun readResolve(): Any = Latest
    }

    /**
     * 지정한 [sequenceNumber]의 레코드를 읽습니다(포함).
     *
     * @param sequenceNumber 비어 있지 않은 Kinesis 시퀀스 번호 문자열
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
     * 지정한 [sequenceNumber] 뒤의 레코드를 읽습니다(제외).
     *
     * @param sequenceNumber 비어 있지 않은 Kinesis 시퀀스 번호 문자열
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
     * 지정한 [timestamp] 이후의 레코드를 읽습니다(포함).
     *
     * @param timestamp 시작 시각. [aws.smithy.kotlin.runtime.time.Instant.fromEpochSeconds]를 통해
     *   AWS SDK [aws.smithy.kotlin.runtime.time.Instant]로 변환할 때 나노초 정밀도를 보존합니다.
     */
    data class AtTimestamp(val timestamp: Instant) : KinesisStartingPosition {
        companion object {
            private const val serialVersionUID: Long = 1L
        }
    }
}
