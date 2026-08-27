package io.bluetape4k.aws.kinesis

import io.bluetape4k.logging.KotlinLogging
import io.bluetape4k.logging.warn
import java.util.concurrent.atomic.AtomicReference
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.future.await
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import software.amazon.awssdk.core.exception.SdkException
import software.amazon.awssdk.services.kinesis.KinesisAsyncClient
import software.amazon.awssdk.services.kinesis.model.ExpiredIteratorException
import software.amazon.awssdk.services.kinesis.model.ExpiredNextTokenException
import software.amazon.awssdk.services.kinesis.model.GetRecordsRequest
import software.amazon.awssdk.services.kinesis.model.GetShardIteratorRequest
import software.amazon.awssdk.services.kinesis.model.ListShardsRequest
import software.amazon.awssdk.services.kinesis.model.Shard
import software.amazon.awssdk.services.kinesis.model.ShardIteratorType

private val log = KotlinLogging.logger {}

/**
 * Java SDK v2 async client에서 dynamic multi-shard consumer Flow를 생성합니다.
 *
 * client, checkpoint store, lease store의 lifecycle은 호출자가 소유합니다. Flow는 shard별
 * private async poller를 실행하지만 shard child가 직접 `emit`하지 않고 하나의 outer emitter와
 * rendezvous channel을 통해 downstream 처리 완료 뒤 checkpoint를 저장합니다.
 */
@Suppress("LongMethod", "LoopWithTooManyJumpStatements", "TooGenericExceptionCaught")
fun KinesisAsyncClient.consumerFlow(
    streamName: String,
    consumerGroup: String,
    streamIdentity: String,
    position: KinesisStartingPosition,
    options: KinesisConsumerOptions,
    checkpointStore: KinesisCheckpointStore,
    leaseStore: KinesisLeaseStore,
    metrics: KinesisFlowMetrics = NoopKinesisFlowMetrics,
): Flow<KinesisShardRecord> {
    streamName.requireKinesisStreamName()
    consumerGroup.requireKinesisIdentifier("consumerGroup")
    streamIdentity.requireKinesisIdentifier("streamIdentity")

    val kinesisClient = this
    return flow {
        coroutineScope {
            val pending = Channel<PendingRecord>(capacity = Channel.RENDEZVOUS)
            val semaphore = Semaphore(options.maxShardConcurrency)
            val activeJobs = mutableMapOf<String, Job>()
            val discoveryJob = launch {
                try {
                    while (isActive) {
                        activeJobs.entries.removeIf { (_, job) -> job.isCompleted }
                        val graph = kinesisClient.discoverShardGraph(streamName, options, metrics)
                        for (node in graph.nodes) {
                            if (!isActive || activeJobs.size >= options.maxShardConcurrency) break
                            val shardId = requireNotNull(node.shard.shardId())
                            if (shardId in activeJobs) continue
                            val key = KinesisShardKey(streamIdentity, consumerGroup, shardId)
                            if (checkpointStore.load(key) is KinesisCheckpoint.ShardEnd) continue
                            val dependenciesReady = node.dependencies.all { dependency ->
                                checkpointStore.load(
                                    KinesisShardKey(streamIdentity, consumerGroup, dependency),
                                ) is KinesisCheckpoint.ShardEnd
                            }
                            if (!dependenciesReady) continue
                            activeJobs[shardId] = launch {
                                semaphore.withPermit {
                                    kinesisClient.consumeShard(
                                        scope = this,
                                        streamName = streamName,
                                        streamIdentity = streamIdentity,
                                        consumerGroup = consumerGroup,
                                        node = node,
                                        position = position,
                                        options = options,
                                        checkpointStore = checkpointStore,
                                        leaseStore = leaseStore,
                                        metrics = metrics,
                                        pending = pending,
                                    )
                                }
                            }
                        }
                        delay(options.discoveryInterval)
                    }
                } finally {
                    pending.close()
                }
            }

            try {
                for (record in pending) {
                    try {
                        emit(record.envelope)
                        record.ack.complete(Unit)
                    } catch (cause: Throwable) {
                        record.ack.completeExceptionally(cause)
                        throw cause
                    }
                }
            } finally {
                discoveryJob.cancelAndJoin()
                pending.close()
            }
        }
    }
}

private data class PendingRecord(
    val envelope: KinesisShardRecord,
    val ack: CompletableDeferred<Unit>,
)

private suspend fun KinesisAsyncClient.discoverShardGraph(
    streamName: String,
    options: KinesisConsumerOptions,
    metrics: KinesisFlowMetrics,
): KinesisShardGraph {
    var unknownParentAttempts = 0
    while (true) {
        val graph = KinesisShardGraph.from(discoverShards(streamName, options, metrics), options.maxDiscoveredShards)
        val knownIds = graph.nodes.mapTo(mutableSetOf()) { requireNotNull(it.shard.shardId()) }
        val missingParents = graph.nodes
            .flatMap { it.dependencies }
            .filterNot(knownIds::contains)
            .toSet()
        if (missingParents.isEmpty()) return graph

        unknownParentAttempts++
        if (unknownParentAttempts >= options.maxUnknownParentDiscoveries) {
            throw KinesisShardGraphException(
                "unknown shard parent dependencies exceeded maxUnknownParentDiscoveries=" +
                        options.maxUnknownParentDiscoveries,
            )
        }
        delay(options.discoveryInterval)
    }
}

@Suppress(
    "CyclomaticComplexMethod",
    "LongMethod",
    "NestedBlockDepth",
    "ReturnCount",
    "ThrowsCount",
    "TooGenericExceptionCaught",
)
private suspend fun KinesisAsyncClient.consumeShard(
    scope: CoroutineScope,
    streamName: String,
    streamIdentity: String,
    consumerGroup: String,
    node: KinesisShardGraph.Node,
    position: KinesisStartingPosition,
    options: KinesisConsumerOptions,
    checkpointStore: KinesisCheckpointStore,
    leaseStore: KinesisLeaseStore,
    metrics: KinesisFlowMetrics,
    pending: Channel<PendingRecord>,
) {
    val shard = node.shard
    val shardId = requireNotNull(shard.shardId())
    val key = KinesisShardKey(streamIdentity, consumerGroup, shardId)
    val lease = leaseStore.acquire(key, options.ownerId, options.leaseDuration) ?: return
    val currentLease = AtomicReference(lease)
    val leaseLost = AtomicReference<KinesisLeaseLostException?>(null)
    val heartbeatFailure = AtomicReference<Throwable?>(null)
    val heartbeat = scope.launchHeartbeat(
        leaseStore = leaseStore,
        options = options,
        currentLease = currentLease,
        leaseLost = leaseLost,
        metrics = metrics,
        streamIdentity = streamIdentity,
        shardId = shardId,
        heartbeatFailure = heartbeatFailure,
    )

    try {
        emitEvent(
            metrics,
            KinesisFlowEvent.Lease(
                streamToken = redactedKinesisToken(streamIdentity),
                shardToken = redactedKinesisToken(shardId),
                ownerToken = redactedKinesisToken(options.ownerId),
                outcome = "acquired",
            ),
        )
        emitEvent(
            metrics,
            KinesisFlowEvent.Shard(
                streamToken = redactedKinesisToken(streamIdentity),
                shardToken = redactedKinesisToken(shardId),
                ownerToken = redactedKinesisToken(options.ownerId),
                eventKind = "shard",
                outcome = "started",
            ),
        )
        val checkpoint = checkpointStore.load(key)
        if (checkpoint is KinesisCheckpoint.ShardEnd) return

        var currentPosition = checkpoint
            ?.let { it as? KinesisCheckpoint.Sequence }
            ?.let { KinesisStartingPosition.AtSequenceNumber(it.sequenceNumber) }
            ?: position
        var lastSeenSequenceNumber = (checkpoint as? KinesisCheckpoint.Sequence)?.sequenceNumber
        var iterator: String? = null
        var iteratorRetries = 0
        var throttleRetries = 0
        val maxRecords = minOf(options.recordOptions.batchLimit, options.maxRecordsPerPoll)
        val endingSequence = shard.sequenceNumberRange()?.endingSequenceNumber()

        while (true) {
            currentCoroutineContext().ensureActive()
            try {
                val currentIterator = iterator ?: fetchShardIterator(streamName, shardId, currentPosition).also {
                    iterator = it
                }
                val response = getRecords(
                    GetRecordsRequest.builder()
                        .shardIterator(currentIterator)
                        .limit(maxRecords)
                        .build(),
                ).await()
                iteratorRetries = 0
                throttleRetries = 0
                val records = response.records()
                emitEvent(
                    metrics,
                    KinesisFlowEvent.Batch(
                        streamToken = redactedKinesisToken(streamIdentity),
                        shardToken = redactedKinesisToken(shardId),
                        recordCount = records.size,
                    ),
                )

                for (record in records) {
                    currentCoroutineContext().ensureActive()
                    val sequence = record.sequenceNumber()
                        ?: throw KinesisCheckpointException("Kinesis record has no sequence number")
                    validateLease(leaseStore, options, currentLease, leaseLost)
                    val pendingRecord = PendingRecord(
                        envelope = KinesisShardRecord(streamName, shardId, record),
                        ack = CompletableDeferred(),
                    )
                    pending.send(pendingRecord)
                    pendingRecord.ack.await()
                    currentCoroutineContext().ensureActive()
                    leaseLost.get()?.let { throw it }
                    val fencedLease = validateLease(leaseStore, options, currentLease, leaseLost)
                    checkpointStore.save(key, KinesisCheckpoint.Sequence(sequence), fencedLease)
                    lastSeenSequenceNumber = sequence
                    emitEvent(
                        metrics,
                        KinesisFlowEvent.Checkpoint(
                            streamToken = redactedKinesisToken(streamIdentity),
                            shardToken = redactedKinesisToken(shardId),
                            sequenceToken = redactedKinesisToken(sequence),
                        ),
                    )
                }

                val nextIterator = response.nextShardIterator()
                val reachedEnding = endingSequence?.let { ending ->
                    lastSeenSequenceNumber?.let { last -> compareKinesisSequence(last, ending) >= 0 }
                } ?: false
                if (nextIterator == null || reachedEnding) {
                    checkpointStore.save(
                        key,
                        KinesisCheckpoint.ShardEnd,
                        validateLease(leaseStore, options, currentLease, leaseLost),
                    )
                    emitEvent(
                        metrics,
                        KinesisFlowEvent.Shard(
                            streamToken = redactedKinesisToken(streamIdentity),
                            shardToken = redactedKinesisToken(shardId),
                            ownerToken = redactedKinesisToken(options.ownerId),
                            eventKind = "shard",
                            outcome = "completed",
                        ),
                    )
                    return
                }
                iterator = nextIterator
                delay(
                    if (records.isEmpty()) options.recordOptions.effectiveEmptyBackoff
                    else options.recordOptions.pollInterval,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: ExpiredIteratorException) {
                iteratorRetries++
                if (iteratorRetries > options.recordOptions.maxIteratorRetries) throw e
                if (lastSeenSequenceNumber == null && currentPosition is KinesisStartingPosition.Latest) throw e
                currentPosition = lastSeenSequenceNumber
                    ?.let { KinesisStartingPosition.AfterSequenceNumber(it) }
                    ?: currentPosition
                iterator = null
                emitRetry(metrics, streamIdentity, shardId, iteratorRetries, "iterator", "iterator_expired")
            } catch (e: SdkException) {
                if (!e.retryable()) throw e
                throttleRetries++
                if (throttleRetries > options.recordOptions.maxThrottleRetries) throw e
                emitRetry(metrics, streamIdentity, shardId, throttleRetries, "throttle", "throttled")
                delay(jitteredKinesisBackoff(throttleRetries, options.recordOptions))
            }
        }
    } catch (e: CancellationException) {
        throw heartbeatFailure.get() ?: leaseLost.get() ?: e
    } finally {
        heartbeat.cancelAndJoin()
        withContext(NonCancellable) {
            withTimeoutOrNull(options.leaseReleaseTimeout) {
                try {
                    leaseStore.release(currentLease.get())
                } catch (e: Throwable) {
                    log.warn(e) { "Kinesis lease release failed after consumer termination" }
                }
            }
        }
    }
}

@Suppress("TooGenericExceptionCaught", "RethrowCaughtException")
private fun kotlinx.coroutines.CoroutineScope.launchHeartbeat(
    leaseStore: KinesisLeaseStore,
    options: KinesisConsumerOptions,
    currentLease: AtomicReference<KinesisLease>,
    leaseLost: AtomicReference<KinesisLeaseLostException?>,
    metrics: KinesisFlowMetrics,
    streamIdentity: String,
    shardId: String,
    heartbeatFailure: AtomicReference<Throwable?>,
) = launch {
    while (true) {
        delay(options.leaseRenewInterval)
        currentCoroutineContext().ensureActive()
        val renewed = leaseStore.renew(currentLease.get(), options.leaseDuration)
        if (renewed == null) {
            val failure = KinesisLeaseLostException()
            leaseLost.compareAndSet(null, failure)
            try {
                emitEvent(
                    metrics,
                    KinesisFlowEvent.Lease(
                        streamToken = redactedKinesisToken(streamIdentity),
                        shardToken = redactedKinesisToken(shardId),
                        ownerToken = redactedKinesisToken(options.ownerId),
                        outcome = "lost",
                        reason = "lease_lost",
                    ),
                )
            } catch (cause: Throwable) {
                heartbeatFailure.compareAndSet(null, cause)
                throw cause
            }
            throw failure
        }
        currentLease.set(renewed)
        emitEvent(
            metrics,
            KinesisFlowEvent.Lease(
                streamToken = redactedKinesisToken(streamIdentity),
                shardToken = redactedKinesisToken(shardId),
                ownerToken = redactedKinesisToken(options.ownerId),
                outcome = "renewed",
            ),
        )
    }
}

private suspend fun validateLease(
    leaseStore: KinesisLeaseStore,
    options: KinesisConsumerOptions,
    currentLease: AtomicReference<KinesisLease>,
    leaseLost: AtomicReference<KinesisLeaseLostException?>,
): KinesisLease {
    leaseLost.get()?.let { throw it }
    val renewed = leaseStore.renew(currentLease.get(), options.leaseDuration)
    if (renewed == null) {
        val failure = KinesisLeaseLostException()
        leaseLost.compareAndSet(null, failure)
        throw leaseLost.get() ?: failure
    }
    currentLease.set(renewed)
    return renewed
}

private suspend fun emitEvent(metrics: KinesisFlowMetrics, event: KinesisFlowEvent) = metrics.onEvent(event)

private suspend fun emitRetry(
    metrics: KinesisFlowMetrics,
    streamIdentity: String,
    shardId: String,
    attempt: Int,
    retryClass: String,
    reason: String,
) {
    emitEvent(
        metrics,
        KinesisFlowEvent.Retry(
            streamToken = redactedKinesisToken(streamIdentity),
            shardToken = redactedKinesisToken(shardId),
            attempt = attempt,
            reason = reason,
            retryClass = retryClass,
        ),
    )
}

private suspend fun KinesisAsyncClient.fetchShardIterator(
    streamName: String,
    shardId: String,
    position: KinesisStartingPosition,
): String {
    val request = GetShardIteratorRequest.builder()
        .streamName(streamName)
        .shardId(shardId)
        .apply {
            when (position) {
                KinesisStartingPosition.TrimHorizon -> shardIteratorType(ShardIteratorType.TRIM_HORIZON)
                KinesisStartingPosition.Latest -> shardIteratorType(ShardIteratorType.LATEST)
                is KinesisStartingPosition.AtSequenceNumber -> {
                    shardIteratorType(ShardIteratorType.AT_SEQUENCE_NUMBER)
                    startingSequenceNumber(position.sequenceNumber)
                }
                is KinesisStartingPosition.AfterSequenceNumber -> {
                    shardIteratorType(ShardIteratorType.AFTER_SEQUENCE_NUMBER)
                    startingSequenceNumber(position.sequenceNumber)
                }
                is KinesisStartingPosition.AtTimestamp -> {
                    shardIteratorType(ShardIteratorType.AT_TIMESTAMP)
                    timestamp(position.timestamp)
                }
            }
        }
        .build()
    return getShardIterator(request).await().shardIterator()
        ?: error("GetShardIterator returned no iterator")
}

@Suppress("CyclomaticComplexMethod", "NestedBlockDepth", "ThrowsCount")
private suspend fun KinesisAsyncClient.discoverShards(
    streamName: String,
    options: KinesisConsumerOptions,
    metrics: KinesisFlowMetrics,
): List<Shard> {
    var attempt = 0
    while (true) {
        try {
            val result = mutableListOf<Shard>()
            val seenIds = linkedSetOf<String>()
            val seenTokens = mutableSetOf<String>()
            var nextToken: String? = null
            for (page in 1..options.maxListShardsPages) {
                currentCoroutineContext().ensureActive()
                nextToken?.let { token ->
                    if (!seenTokens.add(token)) {
                        throw KinesisShardGraphException("ListShards pagination did not make progress")
                    }
                }
                val request = ListShardsRequest.builder()
                    .apply {
                        if (nextToken == null) streamName(streamName) else nextToken(nextToken)
                        maxResults(minOf(options.maxDiscoveredShards, 1_000))
                    }
                    .build()
                val response = listShards(request).await()
                response.shards().forEach { shard ->
                    val id = shard.shardId()?.requireKinesisIdentifier("shardId")
                        ?: throw KinesisShardGraphException("ListShards returned a shard without shardId")
                    if (seenIds.add(id)) result += shard
                    if (result.size > options.maxDiscoveredShards) {
                        throw KinesisShardGraphException("discovered shard count exceeded configured maximum")
                    }
                }
                emitEvent(
                    metrics,
                    KinesisFlowEvent.Discovery(
                        streamToken = redactedKinesisToken(streamName),
                        page = page,
                        shardCount = response.shards().size,
                    ),
                )
                nextToken = response.nextToken()
                if (nextToken == null) return result
            }
            throw KinesisShardGraphException("ListShards pagination exceeded configured page limit")
        } catch (e: CancellationException) {
            throw e
        } catch (e: ExpiredNextTokenException) {
            attempt++
            if (attempt > options.maxDiscoveryRetries) throw e
            delay(jitteredKinesisBackoff(attempt, options.recordOptions))
        } catch (e: SdkException) {
            if (!e.retryable()) throw e
            attempt++
            if (attempt > options.maxDiscoveryRetries) throw e
            delay(jitteredKinesisBackoff(attempt, options.recordOptions))
        }
    }
}

private fun compareKinesisSequence(left: String, right: String): Int =
    runCatching { java.math.BigInteger(left).compareTo(java.math.BigInteger(right)) }
        .getOrElse { left.compareTo(right) }

private fun jitteredKinesisBackoff(attempt: Int, options: KinesisRecordFlowOptions): Duration {
    val shift = (attempt - 1).coerceAtMost(30)
    val initial = options.initialThrottleBackoff.inWholeMilliseconds
    val max = options.maxThrottleBackoff.inWholeMilliseconds
    val capped = if (initial > (Long.MAX_VALUE ushr shift)) max else (initial shl shift).coerceAtMost(max)
    return Random.Default.nextLong(0L, capped + 1).milliseconds
}
