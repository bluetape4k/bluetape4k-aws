package io.bluetape4k.aws.kotlin.kinesis

import aws.sdk.kotlin.services.kinesis.KinesisClient
import aws.sdk.kotlin.services.kinesis.getRecords
import aws.sdk.kotlin.services.kinesis.getShardIterator
import aws.sdk.kotlin.services.kinesis.model.ExpiredIteratorException
import aws.sdk.kotlin.services.kinesis.model.KinesisException
import aws.sdk.kotlin.services.kinesis.model.Record
import aws.sdk.kotlin.services.kinesis.model.ShardIteratorType
import aws.smithy.kotlin.runtime.time.Instant as SmithyInstant
import io.bluetape4k.logging.KotlinLogging
import io.bluetape4k.logging.error
import io.bluetape4k.logging.warn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

private val log = KotlinLogging.logger {}

/**
 * Returns a cold [Flow] that continuously polls a single Kinesis shard and emits each [Record].
 *
 * The flow loops indefinitely, calling [KinesisClient.getRecords] on the given shard and emitting
 * all received records before advancing the iterator. The loop exits when Kinesis signals that the
 * shard has been closed (i.e., `nextShardIterator` is `null`).
 *
 * ## Error handling
 *
 * - **[CancellationException]** — propagated immediately; never retried.
 * - **[ExpiredIteratorException]** — the iterator is re-fetched using the last seen sequence
 *   number (via [KinesisStartingPosition.AfterSequenceNumber]). If no record has been seen yet
 *   and the starting position is [KinesisStartingPosition.Latest], the error is propagated
 *   immediately because re-fetching a new `Latest` iterator would silently skip all records
 *   written during the 5-minute TTL window. Recovery is attempted at most
 *   [KinesisRecordFlowOptions.maxIteratorRetries] times.
 * - **Retryable [KinesisException]** (e.g. `ProvisionedThroughputExceededException`) — retried
 *   with exponential jitter backoff, up to [KinesisRecordFlowOptions.maxThrottleRetries] times.
 * - **Non-retryable [KinesisException]** — propagated immediately.
 *
 * ## Usage
 *
 * ```kotlin
 * kinesisClient.recordFlow(
 *     streamName = "my-stream",
 *     shardId = "shardId-000000000000",
 *     position = KinesisStartingPosition.TrimHorizon,
 * ).collect { record ->
 *     println(record.data.decodeToString())
 * }
 * ```
 *
 * @param streamName Kinesis stream name.
 * @param shardId Target shard identifier.
 * @param position Starting position. Defaults to [KinesisStartingPosition.TrimHorizon].
 * @param options Tuning parameters. Defaults to [KinesisRecordFlowOptions].
 * @return Cold [Flow] of [Record] values.
 */
fun KinesisClient.recordFlow(
    streamName: String,
    shardId: String,
    position: KinesisStartingPosition = KinesisStartingPosition.TrimHorizon,
    options: KinesisRecordFlowOptions = KinesisRecordFlowOptions(),
): Flow<Record> = flow {
    var currentPosition = position
    var lastSeenSequenceNumber: String? = null
    var iteratorRetryCount = 0
    var throttleRetryCount = 0
    var shardIterator: String? = null   // null triggers a (re-)fetch on next iteration

    while (true) {
        currentCoroutineContext().ensureActive()

        try {
            if (shardIterator == null) {
                shardIterator = fetchShardIterator(streamName, shardId, currentPosition)
            }

            val response = getRecords {
                limit = options.batchLimit
                this.shardIterator = shardIterator!!
            }

            iteratorRetryCount = 0
            throttleRetryCount = 0

            for (record in response.records) {
                emit(record)
                lastSeenSequenceNumber = record.sequenceNumber
            }

            // nextShardIterator == null means the shard was closed (resharding)
            if (response.nextShardIterator == null) return@flow
            shardIterator = response.nextShardIterator!!

            val pollDelay = if (response.records.isEmpty()) options.emptyBackoff else options.pollInterval
            delay(pollDelay)

        } catch (e: CancellationException) {
            throw e

        } catch (e: ExpiredIteratorException) {
            iteratorRetryCount++
            if (iteratorRetryCount > options.maxIteratorRetries) {
                log.error { "Shard iterator expired after $iteratorRetryCount attempts: stream=$streamName shard=$shardId" }
                throw e
            }

            val lastSeen = lastSeenSequenceNumber
            if (lastSeen == null && currentPosition is KinesisStartingPosition.Latest) {
                // Re-fetching Latest would silently skip all records written during the TTL window.
                log.error {
                    "Iterator expired for Latest position with no checkpoint: " +
                            "stream=$streamName shard=$shardId — cannot recover without data loss"
                }
                throw e
            }

            log.warn {
                "Shard iterator expired (attempt $iteratorRetryCount/${options.maxIteratorRetries}): " +
                        "stream=$streamName shard=$shardId"
            }
            currentPosition = lastSeen
                ?.let { KinesisStartingPosition.AfterSequenceNumber(it) }
                ?: currentPosition
            shardIterator = null   // delegate fetch to next iteration's try-block

        } catch (e: KinesisException) {
            if (!e.sdkErrorMetadata.isRetryable) throw e

            throttleRetryCount++
            if (throttleRetryCount > options.maxThrottleRetries) {
                log.error {
                    "Throttle retries exhausted after $throttleRetryCount attempts: " +
                            "stream=$streamName shard=$shardId error=${e.message}"
                }
                throw e
            }

            log.warn { "Throttle retry $throttleRetryCount/${options.maxThrottleRetries}: stream=$streamName shard=$shardId" }
            val backoff = jitteredBackoff(throttleRetryCount, options)
            delay(backoff)
        }
    }
}

/**
 * Fetches a fresh shard iterator for the given [position].
 *
 * This is called at flow startup and again after [ExpiredIteratorException] recovery.
 * The call is inside the main `try {}` block so that any transient errors (e.g. throttling on
 * `GetShardIterator`) are handled by the sibling `catch` clauses on the next iteration.
 */
private suspend fun KinesisClient.fetchShardIterator(
    streamName: String,
    shardId: String,
    position: KinesisStartingPosition,
): String {
    return getShardIterator {
        this.streamName = streamName
        this.shardId = shardId
        when (position) {
            is KinesisStartingPosition.TrimHorizon -> {
                shardIteratorType = ShardIteratorType.TrimHorizon
            }
            is KinesisStartingPosition.Latest -> {
                shardIteratorType = ShardIteratorType.Latest
            }
            is KinesisStartingPosition.AtSequenceNumber -> {
                shardIteratorType = ShardIteratorType.AtSequenceNumber
                startingSequenceNumber = position.sequenceNumber
            }
            is KinesisStartingPosition.AfterSequenceNumber -> {
                shardIteratorType = ShardIteratorType.AfterSequenceNumber
                startingSequenceNumber = position.sequenceNumber
            }
            is KinesisStartingPosition.AtTimestamp -> {
                shardIteratorType = ShardIteratorType.AtTimestamp
                timestamp = SmithyInstant.fromEpochSeconds(
                    position.timestamp.epochSecond,
                    position.timestamp.nano,
                )
            }
        }
    }.shardIterator ?: error(
        "getShardIterator returned null iterator for stream=$streamName shard=$shardId"
    )
}

/**
 * Computes a randomised (full-jitter) exponential backoff duration for a throttle retry attempt.
 *
 * The base delay doubles each attempt (capped at [KinesisRecordFlowOptions.maxThrottleBackoff])
 * and jitter is applied by sampling uniformly from `[0, base]`. Arithmetic is performed in
 * milliseconds (Long) to avoid [Duration] overflow at high attempt counts.
 *
 * @param attempt 1-based retry attempt number.
 * @param options Flow options supplying backoff parameters.
 * @return A [Duration] in `[0, maxThrottleBackoff]`.
 */
internal fun jitteredBackoff(attempt: Int, options: KinesisRecordFlowOptions): Duration {
    val maxMs = options.maxThrottleBackoff.inWholeMilliseconds
    val baseMs = options.initialThrottleBackoff.inWholeMilliseconds
    val cappedMs = (baseMs shl (attempt - 1).coerceAtMost(30)).coerceAtMost(maxMs)
    return Random.Default.nextLong(0L, cappedMs + 1L).milliseconds
}
