package io.bluetape4k.aws.kotlin.kinesis

import java.io.Serializable
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Tuning options for [KinesisClient.recordFlow].
 *
 * ## Defaults
 * | Option | Default | Notes |
 * |---|---|---|
 * | batchLimit | 100 | Kinesis maximum is 10 000 |
 * | pollInterval | 200 ms | Minimum recommended poll interval per Kinesis quota |
 * | emptyBackoff | 1 s | Delay when a GetRecords response has no records |
 * | maxIteratorRetries | 3 | Maximum [ExpiredIteratorException] recovery attempts |
 * | initialThrottleBackoff | 500 ms | Exponential backoff seed for throttle retries |
 * | maxThrottleBackoff | 30 s | Maximum single delay between throttle retries |
 * | maxThrottleRetries | 5 | Maximum retryable [KinesisException] attempts |
 *
 * @param batchLimit Maximum records per [GetRecords] call. Must be in 1..[MAX_KINESIS_BATCH_LIMIT].
 * @param pollInterval Delay between successful [GetRecords] calls that returned records.
 *   Must be ≥ [MIN_POLL_INTERVAL] to respect the Kinesis 5 calls/s/shard quota.
 * @param emptyBackoff Delay when a [GetRecords] response has zero records.
 * @param maxIteratorRetries Maximum times the flow will recover from [ExpiredIteratorException]
 *   by re-fetching a shard iterator. Exhausting this limit throws.
 * @param initialThrottleBackoff Starting backoff duration for exponential jitter on throttle retries.
 * @param maxThrottleBackoff Upper bound for a single throttle-retry delay (applied before jitter).
 * @param maxThrottleRetries Maximum times the flow will retry a retryable [KinesisException].
 *   Exhausting this limit throws.
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
            "pollInterval must be ≥ $MIN_POLL_INTERVAL, but was $pollInterval"
        }
        require(emptyBackoff.isPositive()) {
            "emptyBackoff must be positive, but was $emptyBackoff"
        }
        require(maxIteratorRetries >= 0) {
            "maxIteratorRetries must be ≥ 0, but was $maxIteratorRetries"
        }
        require(initialThrottleBackoff.isPositive()) {
            "initialThrottleBackoff must be positive, but was $initialThrottleBackoff"
        }
        require(maxThrottleBackoff >= initialThrottleBackoff) {
            "maxThrottleBackoff ($maxThrottleBackoff) must be ≥ initialThrottleBackoff ($initialThrottleBackoff)"
        }
        require(maxThrottleRetries >= 0) {
            "maxThrottleRetries must be ≥ 0, but was $maxThrottleRetries"
        }
    }

    companion object {
        private const val serialVersionUID: Long = 1L

        /** Kinesis API hard limit for a single GetRecords call. */
        const val MAX_KINESIS_BATCH_LIMIT: Int = 10_000

        /** Minimum safe poll interval to avoid exceeding the 5 calls/s/shard quota. */
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
