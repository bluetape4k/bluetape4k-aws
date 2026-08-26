package io.bluetape4k.aws.kinesis

import java.io.Serializable
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Java SDK v2 Kinesis consumer의 shard polling과 retry 옵션입니다.
 *
 * 기존 Java Kinesis 모듈에는 public `recordFlow`가 없으므로 이 타입은 새 `consumerFlow`의
 * private async poller가 사용하는 primitive 옵션입니다. `emptyBackoff`는 호출자가 기존
 * 계약처럼 양수로 지정할 수 있지만 consumer에서는 [effectiveEmptyBackoff]를 사용합니다.
 */
data class KinesisRecordFlowOptions(
    val batchLimit: Int = DEFAULT_BATCH_LIMIT,
    val pollInterval: Duration = DEFAULT_POLL_INTERVAL,
    val emptyBackoff: Duration = DEFAULT_EMPTY_BACKOFF,
    val maxIteratorRetries: Int = DEFAULT_MAX_ITERATOR_RETRIES,
    val initialThrottleBackoff: Duration = DEFAULT_INITIAL_THROTTLE_BACKOFF,
    val maxThrottleBackoff: Duration = DEFAULT_MAX_THROTTLE_BACKOFF,
    val maxThrottleRetries: Int = DEFAULT_MAX_THROTTLE_RETRIES,
) : Serializable {

    init {
        require(batchLimit in 1..MAX_KINESIS_BATCH_LIMIT) {
            "batchLimit must be in 1..$MAX_KINESIS_BATCH_LIMIT, but was $batchLimit"
        }
        require(pollInterval >= MIN_POLL_INTERVAL) {
            "pollInterval must be >= $MIN_POLL_INTERVAL, but was $pollInterval"
        }
        require(emptyBackoff.isPositive()) {
            "emptyBackoff must be positive, but was $emptyBackoff"
        }
        require(maxIteratorRetries >= 0) {
            "maxIteratorRetries must be >= 0, but was $maxIteratorRetries"
        }
        require(initialThrottleBackoff.isPositive()) {
            "initialThrottleBackoff must be positive, but was $initialThrottleBackoff"
        }
        require(maxThrottleBackoff >= initialThrottleBackoff) {
            "maxThrottleBackoff ($maxThrottleBackoff) must be >= initialThrottleBackoff ($initialThrottleBackoff)"
        }
        require(maxThrottleRetries >= 0) {
            "maxThrottleRetries must be >= 0, but was $maxThrottleRetries"
        }
    }

    /** consumer poller가 사용할 빈 응답 지연입니다. Kinesis의 shard당 cadence 하한을 보장합니다. */
    val effectiveEmptyBackoff: Duration
        get() = maxOf(emptyBackoff, MIN_POLL_INTERVAL)

    companion object {
        private const val serialVersionUID: Long = 1L
        const val MAX_KINESIS_BATCH_LIMIT: Int = 10_000
        val MIN_POLL_INTERVAL: Duration = 200.milliseconds
        const val DEFAULT_BATCH_LIMIT: Int = 100
        val DEFAULT_POLL_INTERVAL: Duration = 200.milliseconds
        val DEFAULT_EMPTY_BACKOFF: Duration = 1.seconds
        const val DEFAULT_MAX_ITERATOR_RETRIES: Int = 3
        val DEFAULT_INITIAL_THROTTLE_BACKOFF: Duration = 500.milliseconds
        val DEFAULT_MAX_THROTTLE_BACKOFF: Duration = 30.seconds
        const val DEFAULT_MAX_THROTTLE_RETRIES: Int = 5
    }
}
