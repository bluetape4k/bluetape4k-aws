package io.bluetape4k.aws.dynamodbstreams

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flatMapMerge
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.future.await
import software.amazon.awssdk.core.exception.SdkException
import software.amazon.awssdk.services.dynamodb.model.DescribeStreamRequest
import software.amazon.awssdk.services.dynamodb.model.ExpiredIteratorException
import software.amazon.awssdk.services.dynamodb.model.GetRecordsRequest
import software.amazon.awssdk.services.dynamodb.model.GetShardIteratorRequest
import software.amazon.awssdk.services.dynamodb.model.Record
import software.amazon.awssdk.services.dynamodb.model.Shard
import software.amazon.awssdk.services.dynamodb.model.ShardIteratorType
import software.amazon.awssdk.services.dynamodb.model.TrimmedDataAccessException
import software.amazon.awssdk.services.dynamodb.streams.DynamoDbStreamsAsyncClient
import io.bluetape4k.logging.KotlinLogging
import io.bluetape4k.logging.error
import io.bluetape4k.logging.warn
import io.bluetape4k.support.requireNotBlank
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

private val log = KotlinLogging.logger {}

/**
 * 단일 DynamoDB Streams shard를 polling해 Java SDK [Record]를 내보내는 cold Flow입니다.
 * checkpoint가 있으면 inclusive하게 재생하므로 at-least-once semantics를 유지합니다.
 */
fun DynamoDbStreamsAsyncClient.recordFlow(
    streamArn: String,
    shardId: String,
    position: DynamoDbStreamsStartingPosition = DynamoDbStreamsStartingPosition.TrimHorizon,
    options: DynamoDbStreamsRecordFlowOptions = DynamoDbStreamsRecordFlowOptions(),
    checkpointStore: DynamoDbStreamsCheckpointStore = NoopDynamoDbStreamsCheckpointStore,
    metrics: DynamoDbStreamsFlowMetrics = NoopDynamoDbStreamsFlowMetrics,
): Flow<Record> {
    streamArn.requireNotBlank("streamArn")
    shardId.requireNotBlank("shardId")

    return flow {
        consumeShard(
            streamArn = streamArn,
            shardId = shardId,
            position = position,
            options = options,
            checkpointStore = checkpointStore,
            metrics = metrics,
        ).collect { emit(it) }
    }
}

/** root shard tree를 bounded 병렬 처리하는 multi-shard Flow입니다. */
fun DynamoDbStreamsAsyncClient.shardRecordFlow(
    streamArn: String,
    position: DynamoDbStreamsStartingPosition = DynamoDbStreamsStartingPosition.TrimHorizon,
    options: DynamoDbStreamsRecordFlowOptions = DynamoDbStreamsRecordFlowOptions(),
    checkpointStore: DynamoDbStreamsCheckpointStore = NoopDynamoDbStreamsCheckpointStore,
    metrics: DynamoDbStreamsFlowMetrics = NoopDynamoDbStreamsFlowMetrics,
): Flow<DynamoDbStreamsShardRecord> {
    streamArn.requireNotBlank("streamArn")

    return flow {
        val shards = describeShards(streamArn, options)
        if (shards.isEmpty()) return@flow

        val shardIds = shards.map { it.requireShardId() }.toSet()
        val childrenByParent = shards
            .mapNotNull { shard -> shard.parentShardId()?.let { parentId -> parentId to shard } }
            .groupBy({ it.first }, { it.second })
        val roots = shards.filter { it.parentShardId() == null || it.parentShardId() !in shardIds }
        check(roots.isNotEmpty()) { "DynamoDB Streams shard graph has no root: streamArn=$streamArn" }
        val visited = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

        roots.asFlow()
            .flatMapMerge(options.maxShardConcurrency) { root ->
                consumeShardTree(
                    streamArn = streamArn,
                    shard = root,
                    childrenByParent = childrenByParent,
                    visited = visited,
                    position = position,
                    options = options,
                    checkpointStore = checkpointStore,
                    metrics = metrics,
                )
            }
            .collect { emit(it) }
    }
}

private suspend fun DynamoDbStreamsAsyncClient.describeShards(
    streamArn: String,
    options: DynamoDbStreamsRecordFlowOptions,
): List<Shard> {
    val shards = mutableListOf<Shard>()
    var exclusiveStartShardId: String? = null

    for (page in 1..options.maxDescribePages) {
        currentCoroutineContext().ensureActive()
        val response = describeStream(
            DescribeStreamRequest.builder()
                .streamArn(streamArn)
                .exclusiveStartShardId(exclusiveStartShardId)
                .build(),
        ).await()
        val description = response.streamDescription()
            ?: error("DescribeStream returned no streamDescription for streamArn=$streamArn")
        shards += description.shards()
        exclusiveStartShardId = description.lastEvaluatedShardId()
        if (exclusiveStartShardId == null) return shards
    }

    error(
        "DescribeStream pagination exceeded maxDescribePages=${options.maxDescribePages} " +
                "for streamArn=$streamArn",
    )
}

private fun DynamoDbStreamsAsyncClient.consumeShardTree(
    streamArn: String,
    shard: Shard,
    childrenByParent: Map<String, List<Shard>>,
    visited: MutableSet<String>,
    position: DynamoDbStreamsStartingPosition,
    options: DynamoDbStreamsRecordFlowOptions,
    checkpointStore: DynamoDbStreamsCheckpointStore,
    metrics: DynamoDbStreamsFlowMetrics,
): Flow<DynamoDbStreamsShardRecord> = flow {
    val shardId = shard.requireShardId()
    if (!visited.add(shardId)) return@flow

    recordFlow(
        streamArn = streamArn,
        shardId = shardId,
        position = position,
        options = options,
        checkpointStore = checkpointStore,
        metrics = metrics,
    ).collect { emit(DynamoDbStreamsShardRecord(streamArn, shardId, it)) }

    childrenByParent[shardId].orEmpty().forEach { child ->
        consumeShardTree(
            streamArn = streamArn,
            shard = child,
            childrenByParent = childrenByParent,
            visited = visited,
            position = position,
            options = options,
            checkpointStore = checkpointStore,
            metrics = metrics,
        ).collect { emit(it) }
    }
}

private fun Shard.requireShardId(): String = shardId()
    ?.also { it.requireNotBlank("shardId") }
    ?: error("DescribeStream returned a shard without shardId")

@Suppress("CyclomaticComplexMethod", "LongMethod")
private fun DynamoDbStreamsAsyncClient.consumeShard(
    streamArn: String,
    shardId: String,
    position: DynamoDbStreamsStartingPosition,
    options: DynamoDbStreamsRecordFlowOptions,
    checkpointStore: DynamoDbStreamsCheckpointStore,
    metrics: DynamoDbStreamsFlowMetrics,
): Flow<Record> = flow {
    val checkpoint = checkpointStore.load(streamArn, shardId)
        ?.also { it.requireNotBlank("checkpoint") }
    var currentPosition: DynamoDbStreamsStartingPosition = checkpoint
        ?.let(DynamoDbStreamsStartingPosition::AtSequenceNumber)
        ?: position
    var lastSeenSequenceNumber: String? = checkpoint
    var iteratorRetryCount = 0
    var throttleRetryCount = 0
    var shardIterator: String? = null

    metrics.onShardStarted(shardId)
    try {
        while (true) {
            currentCoroutineContext().ensureActive()

            try {
                if (shardIterator == null) {
                    shardIterator = fetchShardIterator(streamArn, shardId, currentPosition)
                }
                val currentIterator = requireNotNull(shardIterator)
                val response = getRecords(
                    GetRecordsRequest.builder()
                        .shardIterator(currentIterator)
                        .limit(options.batchLimit)
                        .build(),
                ).await()
                iteratorRetryCount = 0
                throttleRetryCount = 0
                val records = response.records()
                metrics.onBatch(shardId, records.size)

                for (record in records) {
                    emit(record)
                    val sequenceNumber = record.dynamodb()?.sequenceNumber()
                    if (sequenceNumber == null && checkpointStore !== NoopDynamoDbStreamsCheckpointStore) {
                        error("DynamoDB Streams record has no sequenceNumber for streamArn=$streamArn shard=$shardId")
                    }
                    if (sequenceNumber != null) {
                        checkpointStore.save(streamArn, shardId, sequenceNumber)
                        metrics.onCheckpointSaved(shardId, sequenceNumber)
                        lastSeenSequenceNumber = sequenceNumber
                    }
                }

                val nextIterator = response.nextShardIterator() ?: return@flow
                shardIterator = nextIterator
                delay(if (records.isEmpty()) options.emptyBackoff else options.pollInterval)

            } catch (e: CancellationException) {
                throw e

            } catch (e: TrimmedDataAccessException) {
                log.error { "DynamoDB Streams data was trimmed: streamArn=$streamArn shard=$shardId" }
                throw e

            } catch (e: ExpiredIteratorException) {
                iteratorRetryCount++
                if (lastSeenSequenceNumber == null && currentPosition is DynamoDbStreamsStartingPosition.Latest) {
                    log.error { "Latest iterator expired before a checkpoint: streamArn=$streamArn shard=$shardId" }
                    throw e
                }
                if (iteratorRetryCount > options.maxIteratorRetries) {
                    log.error {
                        "DynamoDB Streams iterator retries exhausted: streamArn=$streamArn " +
                                "shard=$shardId attempts=$iteratorRetryCount"
                    }
                    throw e
                }
                currentPosition = lastSeenSequenceNumber
                    ?.let(DynamoDbStreamsStartingPosition::AtSequenceNumber)
                    ?: currentPosition
                shardIterator = null
                metrics.onRetry(shardId, iteratorRetryCount, e)
                log.warn {
                    "DynamoDB Streams iterator retry $iteratorRetryCount/${options.maxIteratorRetries}: " +
                            "streamArn=$streamArn shard=$shardId"
                }

            } catch (e: SdkException) {
                if (!e.retryable()) throw e
                throttleRetryCount++
                if (throttleRetryCount > options.maxThrottleRetries) {
                    log.error {
                        "DynamoDB Streams throttle retries exhausted: streamArn=$streamArn " +
                                "shard=$shardId attempts=$throttleRetryCount"
                    }
                    throw e
                }
                metrics.onRetry(shardId, throttleRetryCount, e)
                val backoff = jitteredDynamoDbStreamsBackoff(throttleRetryCount, options)
                log.warn {
                    "DynamoDB Streams retry $throttleRetryCount/${options.maxThrottleRetries}: " +
                            "streamArn=$streamArn shard=$shardId backoff=$backoff"
                }
                delay(backoff)
            }
        }
    } finally {
        metrics.onShardCompleted(shardId)
    }
}

private suspend fun DynamoDbStreamsAsyncClient.fetchShardIterator(
    streamArn: String,
    shardId: String,
    position: DynamoDbStreamsStartingPosition,
): String {
    val request = GetShardIteratorRequest.builder()
        .streamArn(streamArn)
        .shardId(shardId)
        .apply {
            when (position) {
                DynamoDbStreamsStartingPosition.TrimHorizon -> shardIteratorType(ShardIteratorType.TRIM_HORIZON)
                DynamoDbStreamsStartingPosition.Latest -> shardIteratorType(ShardIteratorType.LATEST)
                is DynamoDbStreamsStartingPosition.AtSequenceNumber -> {
                    shardIteratorType(ShardIteratorType.AT_SEQUENCE_NUMBER)
                    sequenceNumber(position.sequenceNumber)
                }
                is DynamoDbStreamsStartingPosition.AfterSequenceNumber -> {
                    shardIteratorType(ShardIteratorType.AFTER_SEQUENCE_NUMBER)
                    sequenceNumber(position.sequenceNumber)
                }
            }
        }
        .build()
    return getShardIterator(request).await().shardIterator()
        ?: error("GetShardIterator returned no iterator for streamArn=$streamArn shard=$shardId")
}

internal fun jitteredDynamoDbStreamsBackoff(
    attempt: Int,
    options: DynamoDbStreamsRecordFlowOptions,
): Duration {
    require(attempt >= 1) { "attempt must be >= 1" }
    val shift = (attempt - 1).coerceAtMost(30)
    val initialMs = options.initialThrottleBackoff.inWholeMilliseconds
    val maxMs = options.maxThrottleBackoff.inWholeMilliseconds
    val cappedMs = if (initialMs > (Long.MAX_VALUE ushr shift)) {
        maxMs
    } else {
        (initialMs shl shift).coerceAtMost(maxMs)
    }
    return Random.Default.nextLong(0L, cappedMs + 1L).milliseconds
}

/** shard와 원본 Java SDK record를 함께 전달해 checkpoint key를 보존합니다. */
data class DynamoDbStreamsShardRecord(
    val streamArn: String,
    val shardId: String,
    val record: Record,
)
