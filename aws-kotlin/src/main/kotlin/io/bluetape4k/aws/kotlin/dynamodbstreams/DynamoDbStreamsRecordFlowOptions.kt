package io.bluetape4k.aws.kotlin.dynamodbstreams

import java.io.Serializable
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * DynamoDB Streams record Flow의 polling, retry, shard concurrency 옵션입니다.
 *
 * 기본값은 shard당 `GetRecords` 호출을 초당 5회 이하로 유지하도록 선택했습니다.
 */
data class DynamoDbStreamsRecordFlowOptions(
    val batchLimit: Int = DEFAULT_BATCH_LIMIT,
    val pollInterval: Duration = DEFAULT_POLL_INTERVAL,
    val emptyBackoff: Duration = DEFAULT_EMPTY_BACKOFF,
    val maxIteratorRetries: Int = DEFAULT_MAX_ITERATOR_RETRIES,
    val initialThrottleBackoff: Duration = DEFAULT_INITIAL_THROTTLE_BACKOFF,
    val maxThrottleBackoff: Duration = DEFAULT_MAX_THROTTLE_BACKOFF,
    val maxThrottleRetries: Int = DEFAULT_MAX_THROTTLE_RETRIES,
    val maxShardConcurrency: Int = DEFAULT_MAX_SHARD_CONCURRENCY,
    val maxDescribePages: Int = DEFAULT_MAX_DESCRIBE_PAGES,
) : Serializable {

    init {
        require(batchLimit in 1..MAX_BATCH_LIMIT) {
            "batchLimit must be in 1..$MAX_BATCH_LIMIT, but was $batchLimit"
        }
        require(pollInterval >= MIN_POLL_INTERVAL) {
            "pollInterval must be >= $MIN_POLL_INTERVAL, but was $pollInterval"
        }
        require(emptyBackoff >= pollInterval) {
            "emptyBackoff ($emptyBackoff) must be >= pollInterval ($pollInterval)"
        }
        require(maxIteratorRetries >= 0) {
            "maxIteratorRetries must be >= 0, but was $maxIteratorRetries"
        }
        require(initialThrottleBackoff.isPositive()) {
            "initialThrottleBackoff must be positive, but was $initialThrottleBackoff"
        }
        require(maxThrottleBackoff >= initialThrottleBackoff) {
            "maxThrottleBackoff ($maxThrottleBackoff) must be >= initialThrottleBackoff " +
                    "($initialThrottleBackoff)"
        }
        require(maxThrottleRetries >= 0) {
            "maxThrottleRetries must be >= 0, but was $maxThrottleRetries"
        }
        require(maxShardConcurrency >= 1) {
            "maxShardConcurrency must be >= 1, but was $maxShardConcurrency"
        }
        require(maxDescribePages >= 1) {
            "maxDescribePages must be >= 1, but was $maxDescribePages"
        }
    }

    companion object {
        private const val serialVersionUID: Long = 1L

        /** DynamoDB Streams `GetRecords`의 서비스 상한입니다. */
        const val MAX_BATCH_LIMIT: Int = 1_000

        /** shard당 초당 5회 호출을 넘지 않기 위한 최소 polling 간격입니다. */
        val MIN_POLL_INTERVAL: Duration = 200.milliseconds

        const val DEFAULT_BATCH_LIMIT: Int = 100
        val DEFAULT_POLL_INTERVAL: Duration = 200.milliseconds
        val DEFAULT_EMPTY_BACKOFF: Duration = 1.seconds
        const val DEFAULT_MAX_ITERATOR_RETRIES: Int = 3
        val DEFAULT_INITIAL_THROTTLE_BACKOFF: Duration = 500.milliseconds
        val DEFAULT_MAX_THROTTLE_BACKOFF: Duration = 30.seconds
        const val DEFAULT_MAX_THROTTLE_RETRIES: Int = 5
        const val DEFAULT_MAX_SHARD_CONCURRENCY: Int = 4
        const val DEFAULT_MAX_DESCRIBE_PAGES: Int = 100
    }
}
