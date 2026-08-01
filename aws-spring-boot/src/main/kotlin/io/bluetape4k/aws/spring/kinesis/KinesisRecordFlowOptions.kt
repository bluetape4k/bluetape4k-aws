package io.bluetape4k.aws.spring.kinesis

import java.io.Serializable
import java.time.Duration

/**
 * [KinesisOperations.recordFlow]의 폴링 및 재시도 옵션입니다.
 *
 * ## 계약
 *
 * Spring Boot 속성 바인딩과 프로그래밍 방식 구성이 같은 타입을 사용하도록 값을 의도적으로
 * Java [Duration]으로 표현합니다.
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
) : Serializable {

    init {
        require(batchLimit in 1..MAX_KINESIS_BATCH_LIMIT) {
            "batchLimit must be between 1 and $MAX_KINESIS_BATCH_LIMIT."
        }
        require(!pollInterval.isNegative) { "pollInterval must not be negative." }
        require(!emptyBackoff.isNegative) { "emptyBackoff must not be negative." }
        require(maxIteratorRetries >= 0) { "maxIteratorRetries must not be negative." }
        require(maxThrottleRetries >= 0) { "maxThrottleRetries must not be negative." }
        require(!initialThrottleBackoff.isNegative) { "initialThrottleBackoff must not be negative." }
        require(!maxThrottleBackoff.isNegative) { "maxThrottleBackoff must not be negative." }
        require(jitterRatio in 0.0..1.0) { "jitterRatio must be between 0.0 and 1.0." }
    }

    companion object {
        private const val serialVersionUID: Long = 1L

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
