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
 * Kinesis 스트림에 레코드 하나를 게시하는 요청입니다.
 *
 * ## 계약
 *
 * Ktor 애플리케이션 코드가 타입이 같은 매개변수의 순서를 실수로 바꾸지 않도록
 * 스트림 이름과 파티션 키를 명명된 값으로 감쌉니다.
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
 * Kinesis 샤드 반복자를 가져오는 요청입니다.
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
 * Kinesis 샤드 하나에서 레코드를 cold Flow로 수집하는 요청입니다.
 *
 * ## 계약
 *
 * Flow는 호출자가 수집하는 명시적인 단일 샤드 흐름입니다. 이 타입은 리스너 컨테이너,
 * 임대 테이블, 체크포인트 저장소 또는 백그라운드 소비자를 제공하지 않습니다.
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
 * [KinesisKtorOperations.createConfiguredStream]에서 사용할 Kinesis 스트림 구성을 선언합니다.
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
 * [KinesisKtorOperations.recordFlow]의 폴링 및 재시도 옵션입니다.
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
 * Kinesis 샤드 반복자의 시작 위치입니다.
 */
sealed interface KinesisStartingPosition : Serializable {

    /** 샤드에서 사용할 수 있는 가장 오래된 레코드부터 읽습니다. */
    data object TrimHorizon : KinesisStartingPosition {
        private const val serialVersionUID: Long = -5888197472956153464L
        private fun readResolve(): Any = TrimHorizon
    }

    /** 반복자를 가져온 뒤 작성된 레코드를 읽습니다. */
    data object Latest : KinesisStartingPosition {
        private const val serialVersionUID: Long = -6388982200191080402L
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
            private const val serialVersionUID: Long = 4322339826570150123L
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
            private const val serialVersionUID: Long = -8598646731640081528L
        }
    }

    /** [timestamp] 이후의 레코드를 해당 시각을 포함해 읽습니다. */
    data class AtTimestamp(val timestamp: Instant) : KinesisStartingPosition {
        companion object {
            private const val serialVersionUID: Long = 1523906536953919234L
        }
    }
}
