package io.bluetape4k.aws.spring.kinesis

import io.bluetape4k.javatimes.millis
import io.bluetape4k.javatimes.seconds
import io.bluetape4k.support.requireGe
import io.bluetape4k.support.requireInRange
import io.bluetape4k.support.requirePositiveNumber
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
): Serializable {

    init {
        batchLimit.requireInRange(1, KinesisRecordFlowOptions.MAX_KINESIS_BATCH_LIMIT, "batchLimit")
        pollInterval.requireGe(Duration.ZERO, "pollInterval")
        emptyBackoff.requireGe(Duration.ZERO, "emptyBackoff")
        maxIteratorRetries.requirePositiveNumber("maxIteratorRetries")
        maxThrottleRetries.requirePositiveNumber("maxThrottleRetries")
        initialThrottleBackoff.requireGe(Duration.ZERO, "initialThrottleBackoff")
        maxThrottleBackoff.requireGe(Duration.ZERO, "maxThrottleBackoff")
        jitterRatio.requireInRange(0.0, 1.0, "jitterRatio")
    }

    companion object {
        private const val serialVersionUID: Long = 1L

        const val MAX_KINESIS_BATCH_LIMIT: Int = 10_000
        const val DEFAULT_BATCH_LIMIT: Int = 100
        val DEFAULT_POLL_INTERVAL: Duration = 200.millis()
        val DEFAULT_EMPTY_BACKOFF: Duration = 1.seconds()
        const val DEFAULT_MAX_ITERATOR_RETRIES: Int = 3
        const val DEFAULT_MAX_THROTTLE_RETRIES: Int = 5
        val DEFAULT_INITIAL_THROTTLE_BACKOFF: Duration = 500.millis()
        val DEFAULT_MAX_THROTTLE_BACKOFF: Duration = 30.seconds()
        const val DEFAULT_JITTER_RATIO: Double = 1.0
    }
}
