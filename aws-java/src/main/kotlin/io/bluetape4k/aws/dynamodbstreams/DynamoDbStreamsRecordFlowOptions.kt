package io.bluetape4k.aws.dynamodbstreams

import io.bluetape4k.support.requireGe
import io.bluetape4k.support.requireGt
import io.bluetape4k.support.requireInRange
import io.bluetape4k.support.requireZeroOrPositiveNumber
import java.io.Serializable
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** DynamoDB Streams record Flow의 polling, retry, shard concurrency 옵션입니다. */
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
): Serializable {
    init {
        batchLimit.requireInRange(1, MAX_BATCH_LIMIT, "batchLimit")
        pollInterval.requireGe(MIN_POLL_INTERVAL, "pollInterval")
        emptyBackoff.requireGe(pollInterval, "emptyBackoff")
        maxIteratorRetries.requireZeroOrPositiveNumber("maxIteratorRetries")
        initialThrottleBackoff.requireGt(Duration.ZERO, "initialThrottleBackoff")
        maxThrottleBackoff.requireGe(initialThrottleBackoff, "maxThrottleBackoff")
        maxThrottleRetries.requireZeroOrPositiveNumber("maxThrottleRetries")
        maxShardConcurrency.requireGe(1, "maxShardConcurrency")
        maxDescribePages.requireGt(1, "maxDescribePages")
    }

    companion object {
        private const val serialVersionUID: Long = 1L
        const val MAX_BATCH_LIMIT: Int = 1_000
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
