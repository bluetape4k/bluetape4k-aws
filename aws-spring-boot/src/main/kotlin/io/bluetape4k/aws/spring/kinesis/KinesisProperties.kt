package io.bluetape4k.aws.spring.kinesis

import org.springframework.boot.context.properties.ConfigurationProperties
import java.io.Serializable
import java.net.URI
import java.time.Duration

/**
 * Spring Boot Kinesis 지원용 구성 속성입니다.
 *
 * ## 계약
 *
 * 서비스별 값은 공유 `bluetape4k.aws.*` 기본값보다 우선합니다. Kinesis 속성에 엔드포인트
 * 재정의를 직접 구성하면 클라이언트를 만들기 전에 Kinesis 또는 공유 리전도 있어야 합니다.
 */
@ConfigurationProperties(prefix = "bluetape4k.aws.kinesis")
data class KinesisProperties(
    val enabled: Boolean = true,
    val region: String? = null,
    val endpointOverride: URI? = null,
    val streams: Map<String, Stream> = emptyMap(),
    val consumer: Consumer = Consumer(),
) : Serializable {

    /**
     * [KinesisOperations.createConfiguredStream]에서 사용하는 선언적 스트림 설정입니다.
     */
    data class Stream(
        val shardCount: Int = 1,
    ) : Serializable {

        init {
            require(shardCount >= 1) { "shardCount must be greater than or equal to 1." }
        }

        companion object {
            private const val serialVersionUID: Long = 1L
        }
    }

    /**
     * [KinesisOperations.recordFlow]의 기본 폴링 및 재시도 설정입니다.
     */
    data class Consumer(
        val batchLimit: Int = KinesisRecordFlowOptions.DEFAULT_BATCH_LIMIT,
        val pollInterval: Duration = KinesisRecordFlowOptions.DEFAULT_POLL_INTERVAL,
        val emptyBackoff: Duration = KinesisRecordFlowOptions.DEFAULT_EMPTY_BACKOFF,
        val maxIteratorRetries: Int = KinesisRecordFlowOptions.DEFAULT_MAX_ITERATOR_RETRIES,
        val maxThrottleRetries: Int = KinesisRecordFlowOptions.DEFAULT_MAX_THROTTLE_RETRIES,
        val initialThrottleBackoff: Duration = KinesisRecordFlowOptions.DEFAULT_INITIAL_THROTTLE_BACKOFF,
        val maxThrottleBackoff: Duration = KinesisRecordFlowOptions.DEFAULT_MAX_THROTTLE_BACKOFF,
        val jitterRatio: Double = KinesisRecordFlowOptions.DEFAULT_JITTER_RATIO,
    ) : Serializable {

        init {
            require(batchLimit in 1..KinesisRecordFlowOptions.MAX_KINESIS_BATCH_LIMIT) {
                "batchLimit must be between 1 and ${KinesisRecordFlowOptions.MAX_KINESIS_BATCH_LIMIT}."
            }
            require(!pollInterval.isNegative) { "pollInterval must not be negative." }
            require(!emptyBackoff.isNegative) { "emptyBackoff must not be negative." }
            require(maxIteratorRetries >= 1) { "maxIteratorRetries must be greater than or equal to 1." }
            require(maxThrottleRetries >= 1) { "maxThrottleRetries must be greater than or equal to 1." }
            require(!initialThrottleBackoff.isNegative) { "initialThrottleBackoff must not be negative." }
            require(!maxThrottleBackoff.isNegative) { "maxThrottleBackoff must not be negative." }
            require(jitterRatio in 0.0..1.0) { "jitterRatio must be between 0.0 and 1.0." }
        }

        internal fun toFlowOptions(): KinesisRecordFlowOptions =
            KinesisRecordFlowOptions(
                batchLimit = batchLimit,
                pollInterval = pollInterval,
                emptyBackoff = emptyBackoff,
                maxIteratorRetries = maxIteratorRetries,
                maxThrottleRetries = maxThrottleRetries,
                initialThrottleBackoff = initialThrottleBackoff,
                maxThrottleBackoff = maxThrottleBackoff,
                jitterRatio = jitterRatio,
            )

        companion object {
            private const val serialVersionUID: Long = 1L
        }
    }

    companion object {
        private const val serialVersionUID: Long = 1L
    }
}
