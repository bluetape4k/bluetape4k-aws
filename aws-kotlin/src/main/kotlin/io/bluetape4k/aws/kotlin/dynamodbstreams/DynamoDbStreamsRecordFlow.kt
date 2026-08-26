@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.bluetape4k.aws.kotlin.dynamodbstreams

import aws.sdk.kotlin.services.dynamodbstreams.DynamoDbStreamsClient
import aws.sdk.kotlin.services.dynamodbstreams.describeStream
import aws.sdk.kotlin.services.dynamodbstreams.getRecords
import aws.sdk.kotlin.services.dynamodbstreams.getShardIterator
import aws.sdk.kotlin.services.dynamodbstreams.model.DescribeStreamRequest
import aws.sdk.kotlin.services.dynamodbstreams.model.DynamoDbStreamsException
import aws.sdk.kotlin.services.dynamodbstreams.model.ExpiredIteratorException
import aws.sdk.kotlin.services.dynamodbstreams.model.GetRecordsRequest
import aws.sdk.kotlin.services.dynamodbstreams.model.GetShardIteratorRequest
import aws.sdk.kotlin.services.dynamodbstreams.model.Record
import aws.sdk.kotlin.services.dynamodbstreams.model.Shard
import aws.sdk.kotlin.services.dynamodbstreams.model.ShardIteratorType
import aws.sdk.kotlin.services.dynamodbstreams.model.TrimmedDataAccessException
import io.bluetape4k.logging.KotlinLogging
import io.bluetape4k.logging.error
import io.bluetape4k.logging.warn
import io.bluetape4k.support.requireNotBlank
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flatMapMerge
import kotlinx.coroutines.flow.flow
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

private val log = KotlinLogging.logger {}

/**
 * 단일 DynamoDB Streams shard를 polling해 AWS SDK [Record]를 내보내는 cold Flow입니다.
 *
 * checkpoint가 제공되면 마지막으로 전달한 sequence number를 포함해 재생하므로
 * at-least-once semantics를 유지합니다. 주입된 client의 소유권은 호출자에게 있으며,
 * 단기 client는 [withDynamoDbStreamsClient]로 감싸야 합니다.
 */
fun DynamoDbStreamsClient.recordFlow(
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

/**
 * stream 전체를 shard graph 순서로 소비하는 bounded multi-shard Flow입니다.
 *
 * 서로 다른 root shard tree는 [DynamoDbStreamsRecordFlowOptions.maxShardConcurrency]까지
 * 병렬 처리하지만, 한 tree의 child shard는 parent가 닫힌 뒤에만 읽습니다. 전역 순서는
 * 보장하지 않으며 envelope에 stream ARN과 shard ID를 보존합니다.
 */
fun DynamoDbStreamsClient.shardRecordFlow(
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
            .mapNotNull { shard -> shard.parentShardId?.let { parentId -> parentId to shard } }
            .groupBy({ it.first }, { it.second })
        val roots = shards.filter { it.parentShardId == null || it.parentShardId !in shardIds }
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

/** Shard graph를 페이지 단위로 읽습니다. 페이지 상한으로 누락을 숨기지 않습니다. */
private suspend fun DynamoDbStreamsClient.describeShards(
    streamArn: String,
    options: DynamoDbStreamsRecordFlowOptions,
): List<Shard> {
    val shards = mutableListOf<Shard>()
    var exclusiveStartShardId: String? = null

    for (page in 1..options.maxDescribePages) {
        currentCoroutineContext().ensureActive()
        val response = describeStream(DescribeStreamRequest {
            this.streamArn = streamArn
            this.exclusiveStartShardId = exclusiveStartShardId
        })
        val description = response.streamDescription
            ?: error("DescribeStream returned no streamDescription for streamArn=$streamArn")
        shards += description.shards.orEmpty().filterNotNull()
        exclusiveStartShardId = description.lastEvaluatedShardId
        if (exclusiveStartShardId == null) return shards
    }

    error(
        "DescribeStream pagination exceeded maxDescribePages=${options.maxDescribePages} " +
                "for streamArn=$streamArn",
    )
}

private fun DynamoDbStreamsClient.consumeShardTree(
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

private fun Shard.requireShardId(): String = shardId
    ?.also { it.requireNotBlank("shardId") }
    ?: error("DescribeStream returned a shard without shardId")

@Suppress("CyclomaticComplexMethod", "LongMethod")
private fun DynamoDbStreamsClient.consumeShard(
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

                val response = getRecords(GetRecordsRequest {
                    this.shardIterator = currentIterator
                    limit = options.batchLimit
                })
                iteratorRetryCount = 0
                throttleRetryCount = 0
                val records = response.records.orEmpty().filterNotNull()
                metrics.onBatch(shardId, records.size)

                for (record in records) {
                    emit(record)
                    val sequenceNumber = record.dynamodb?.sequenceNumber
                    if (sequenceNumber == null && checkpointStore !== NoopDynamoDbStreamsCheckpointStore) {
                        error("DynamoDB Streams record has no sequenceNumber for streamArn=$streamArn shard=$shardId")
                    }
                    sequenceNumber?.let {
                        checkpointStore.save(streamArn, shardId, it)
                        metrics.onCheckpointSaved(shardId, it)
                        lastSeenSequenceNumber = it
                    }
                }

                val nextIterator = response.nextShardIterator ?: return@flow
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

            } catch (e: DynamoDbStreamsException) {
                if (!e.sdkErrorMetadata.isRetryable) throw e
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

private suspend fun DynamoDbStreamsClient.fetchShardIterator(
    streamArn: String,
    shardId: String,
    position: DynamoDbStreamsStartingPosition,
): String {
    val response = getShardIterator(GetShardIteratorRequest {
        this.streamArn = streamArn
        this.shardId = shardId
        when (position) {
            DynamoDbStreamsStartingPosition.TrimHorizon -> shardIteratorType = ShardIteratorType.TrimHorizon
            DynamoDbStreamsStartingPosition.Latest -> shardIteratorType = ShardIteratorType.Latest
            is DynamoDbStreamsStartingPosition.AtSequenceNumber -> {
                shardIteratorType = ShardIteratorType.AtSequenceNumber
                sequenceNumber = position.sequenceNumber
            }
            is DynamoDbStreamsStartingPosition.AfterSequenceNumber -> {
                shardIteratorType = ShardIteratorType.AfterSequenceNumber
                sequenceNumber = position.sequenceNumber
            }
        }
    })
    return response.shardIterator
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

/** shard와 원본 record를 함께 전달해 checkpoint key를 보존합니다. */
data class DynamoDbStreamsShardRecord(
    val streamArn: String,
    val shardId: String,
    val record: Record,
)
