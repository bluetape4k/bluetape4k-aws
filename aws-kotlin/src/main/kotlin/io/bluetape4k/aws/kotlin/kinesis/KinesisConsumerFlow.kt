package io.bluetape4k.aws.kotlin.kinesis

import aws.sdk.kotlin.services.kinesis.KinesisClient
import aws.sdk.kotlin.services.kinesis.getRecords
import aws.sdk.kotlin.services.kinesis.getShardIterator
import aws.sdk.kotlin.services.kinesis.model.ExpiredIteratorException
import aws.sdk.kotlin.services.kinesis.model.GetShardIteratorRequest
import aws.sdk.kotlin.services.kinesis.model.KinesisException
import aws.sdk.kotlin.services.kinesis.model.Record
import aws.sdk.kotlin.services.kinesis.model.ShardIteratorType
import aws.smithy.kotlin.runtime.time.Instant as SmithyInstant
import io.bluetape4k.logging.KotlinLogging
import io.bluetape4k.logging.error
import io.bluetape4k.logging.warn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.math.BigInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration

private val consumerLog = KotlinLogging.logger { }

/**
 * Kinesis 전체 shard를 동적으로 발견해 bounded multi-shard consumer로 내보냅니다.
 *
 * 한 shard의 AWS 호출은 순차적이며 shard 사이에는 [KinesisConsumerOptions.maxShardConcurrency]만큼
 * 병렬성이 허용됩니다. public buffer는 두지 않고 rendezvous channel과 단일 outer emitter를
 * 사용하므로 collector의 `emit`이 반환되기 전 checkpoint가 저장되지 않습니다.
 * 호출자가 주입한 client와 lease/checkpoint store의 lifecycle은 호출자 소유입니다.
 * durable store를 사용하면 restart 시 inclusive checkpoint replay를 통해 at-least-once를
 * 얻을 수 있고, Noop store는 process-local 실행에만 적합합니다.
 *
 * lease loss가 관측된 뒤에는 새 record emit/save를 시작하지 않습니다. lease 검증 직후
 * takeover되는 TOCTOU 구간에서 이미 시작한 in-flight emit은 at-least-once 경계상 중복될
 * 수 있으며, fenced save는 거부되어 새 owner가 inclusive replay를 수행합니다.
 */
@Suppress("TooGenericExceptionCaught")
fun KinesisClient.consumerFlow(
    streamName: String,
    consumerGroup: String,
    streamIdentity: String,
    position: KinesisStartingPosition = KinesisStartingPosition.TrimHorizon,
    options: KinesisConsumerOptions,
    checkpointStore: KinesisCheckpointStore,
    leaseStore: KinesisLeaseStore,
    metrics: KinesisFlowMetrics = NoopKinesisFlowMetrics,
): Flow<KinesisShardRecord> {
    streamName.validateIdentifier("streamName", KinesisShardRecord.STREAM_NAME_MAX_LENGTH)
    val keyPrefix = KinesisShardIdentity(streamIdentity, consumerGroup)

    return flow {
        coroutineScope {
            val output = Channel<PendingRecord>(capacity = Channel.RENDEZVOUS)
            val activeJobs = mutableMapOf<String, kotlinx.coroutines.Job>()
            val concurrency = Semaphore(options.maxShardConcurrency)
            val discoveryJob = launch {
                discoverAndLaunch(
                    client = this@consumerFlow,
                    streamName = streamName,
                    identity = keyPrefix,
                    position = position,
                    options = options,
                    checkpointStore = checkpointStore,
                    leaseStore = leaseStore,
                    metrics = metrics,
                    output = output,
                    activeJobs = activeJobs,
                    concurrency = concurrency,
                )
            }

            try {
                // 이 loop만 Flow context에서 emit한다. shard job은 channel로 envelope만 전달한다.
                for (pending in output) {
                    try {
                        emit(pending.envelope)
                        pending.ack.complete(Unit)
                    } catch (e: Throwable) {
                        pending.ack.completeExceptionally(e)
                        throw e
                    }
                }
            } finally {
                discoveryJob.cancel()
                output.close()
            }
        }
    }
}

private data class KinesisShardIdentity(
    val streamIdentity: String,
    val consumerGroup: String,
) {
    init {
        streamIdentity.validateIdentifier("streamIdentity", KinesisShardKey.MAX_IDENTIFIER_LENGTH)
        consumerGroup.validateIdentifier("consumerGroup", KinesisShardKey.MAX_IDENTIFIER_LENGTH)
    }
}

private data class PendingRecord(
    val envelope: KinesisShardRecord,
    val ack: CompletableDeferred<Unit> = CompletableDeferred(),
)

@Suppress("LoopWithTooManyJumpStatements")
private suspend fun kotlinx.coroutines.CoroutineScope.discoverAndLaunch(
    client: KinesisClient,
    streamName: String,
    identity: KinesisShardIdentity,
    position: KinesisStartingPosition,
    options: KinesisConsumerOptions,
    checkpointStore: KinesisCheckpointStore,
    leaseStore: KinesisLeaseStore,
    metrics: KinesisFlowMetrics,
    output: SendChannel<PendingRecord>,
    activeJobs: MutableMap<String, kotlinx.coroutines.Job>,
    concurrency: Semaphore,
) {
    while (isActive) {
        activeJobs.entries.removeIf { (_, job) -> job.isCompleted }
        val graph = client.discoverKinesisShardGraph(streamName, options)
        metrics.onEvent(
            KinesisFlowEvent.Observation(
                eventKind = KinesisFlowEvent.EventKind.DISCOVERY,
                outcome = KinesisFlowEvent.Outcome.SUCCESS,
                count = graph.nodes.size,
            ),
        )

        for (node in graph.nodes.values) {
            if (!isActive || activeJobs.size >= options.maxShardConcurrency) break
            if (node.shardId in activeJobs) continue
            val key = KinesisShardKey(identity.streamIdentity, identity.consumerGroup, node.shardId)
            if (checkpointStore.load(key) is KinesisCheckpoint.ShardEnd) continue
            val dependenciesComplete = node.dependencies.all { parentId ->
                checkpointStore.load(
                    KinesisShardKey(identity.streamIdentity, identity.consumerGroup, parentId),
                ) is KinesisCheckpoint.ShardEnd
            }
            if (!dependenciesComplete) continue

            val job = launch {
                concurrency.withPermit {
                    consumeShard(
                        client = client,
                        streamName = streamName,
                        key = key,
                        node = node,
                        position = position,
                        options = options,
                        checkpointStore = checkpointStore,
                        leaseStore = leaseStore,
                        metrics = metrics,
                        output = output,
                    )
                }
            }
            activeJobs[node.shardId] = job
        }
        delay(options.discoveryInterval)
    }
}

@Suppress("LongMethod", "ThrowsCount", "TooGenericExceptionCaught", "ThrowingExceptionFromFinally")
private suspend fun kotlinx.coroutines.CoroutineScope.consumeShard(
    client: KinesisClient,
    streamName: String,
    key: KinesisShardKey,
    node: KinesisShardNode,
    position: KinesisStartingPosition,
    options: KinesisConsumerOptions,
    checkpointStore: KinesisCheckpointStore,
    leaseStore: KinesisLeaseStore,
    metrics: KinesisFlowMetrics,
    output: SendChannel<PendingRecord>,
) {
    val lease = leaseStore.acquire(key, options.ownerId, options.leaseDuration) ?: return
    val leaseRef = AtomicReference(lease)
    val leaseLoss = AtomicReference<KinesisLeaseLostException?>(null)
    val heartbeatFailure = AtomicReference<Throwable?>(null)
    val shardJob = currentCoroutineContext().job
    var primaryFailure: Throwable? = null
    val heartbeat = launchHeartbeat(
        shardJob = shardJob,
        leaseRef = leaseRef,
        leaseLoss = leaseLoss,
        key = key,
        options = options,
        leaseStore = leaseStore,
        metrics = metrics,
        heartbeatFailure = heartbeatFailure,
    )

    try {
        metrics.onEvent(
            KinesisFlowEvent.Observation(
                eventKind = KinesisFlowEvent.EventKind.LEASE,
                outcome = KinesisFlowEvent.Outcome.STARTED,
                shardToken = KinesisFlowEvent.redactedToken(key.shardId),
                ownerToken = KinesisFlowEvent.redactedToken(options.ownerId),
            ),
        )
        metrics.onEvent(
            KinesisFlowEvent.Observation(
                eventKind = KinesisFlowEvent.EventKind.SHARD,
                outcome = KinesisFlowEvent.Outcome.STARTED,
                shardToken = KinesisFlowEvent.redactedToken(key.shardId),
            ),
        )
        consumeShardRecords(
            client = client,
            streamName = streamName,
            key = key,
            node = node,
            position = position,
            options = options,
            checkpointStore = checkpointStore,
            leaseStore = leaseStore,
            leaseRef = leaseRef,
            leaseLoss = leaseLoss,
            metrics = metrics,
            output = output,
        )
        metrics.onEvent(
            KinesisFlowEvent.Observation(
                eventKind = KinesisFlowEvent.EventKind.SHARD,
                outcome = KinesisFlowEvent.Outcome.SUCCESS,
                shardToken = KinesisFlowEvent.redactedToken(key.shardId),
            ),
        )
    } catch (e: CancellationException) {
        val failure = heartbeatFailure.get() ?: leaseLoss.get() ?: e
        primaryFailure = failure
        throw failure
    } catch (e: Throwable) {
        primaryFailure = e
        throw e
    } finally {
        var cleanupFailure: Throwable? = null
        withContext(NonCancellable) {
            heartbeat.cancel()
            try {
                heartbeat.join()
            } catch (e: Throwable) {
                cleanupFailure = e
            }
            try {
                withTimeoutOrNull(options.leaseReleaseTimeout) {
                    leaseStore.release(leaseRef.get())
                }
            } catch (e: Throwable) {
                val heartbeatJoinFailure = cleanupFailure
                if (heartbeatJoinFailure == null) {
                    cleanupFailure = e
                } else if (heartbeatJoinFailure !== e) {
                    heartbeatJoinFailure.addSuppressed(e)
                }
                consumerLog.warn {
                    "Kinesis lease release failed: shard=${KinesisFlowEvent.redactedToken(key.shardId)} " +
                            "type=${e::class.simpleName}"
                }
            }
        }
        cleanupFailure?.let { failure ->
            val primary = primaryFailure
            if (primary == null) throw failure
            if (primary !== failure) primary.addSuppressed(failure)
        }
    }
}

@Suppress("TooGenericExceptionCaught", "RethrowCaughtException")
private fun kotlinx.coroutines.CoroutineScope.launchHeartbeat(
    shardJob: kotlinx.coroutines.Job,
    leaseRef: AtomicReference<KinesisLease>,
    leaseLoss: AtomicReference<KinesisLeaseLostException?>,
    key: KinesisShardKey,
    options: KinesisConsumerOptions,
    leaseStore: KinesisLeaseStore,
    metrics: KinesisFlowMetrics,
    heartbeatFailure: AtomicReference<Throwable?>,
) = launch {
    while (isActive) {
        delay(options.leaseRenewInterval)
        val renewed = leaseStore.renew(leaseRef.get(), options.leaseDuration)
        if (renewed == null) {
            val failure = KinesisLeaseLostException(
                "Kinesis lease lost for shard=${KinesisFlowEvent.redactedToken(key.shardId)}",
            )
            leaseLoss.compareAndSet(null, failure)
            try {
                metrics.onEvent(
                    KinesisFlowEvent.Observation(
                        eventKind = KinesisFlowEvent.EventKind.LEASE,
                        outcome = KinesisFlowEvent.Outcome.LOST,
                        reason = KinesisFlowEvent.Reason.LEASE_LOST,
                        shardToken = KinesisFlowEvent.redactedToken(key.shardId),
                        ownerToken = KinesisFlowEvent.redactedToken(options.ownerId),
                    ),
                )
            } catch (cause: Throwable) {
                heartbeatFailure.compareAndSet(null, cause)
                throw cause
            }
            shardJob.cancel(CancellationException("Kinesis lease heartbeat lost", failure))
            return@launch
        }
        leaseRef.set(renewed)
        try {
            metrics.onEvent(
                KinesisFlowEvent.Observation(
                    eventKind = KinesisFlowEvent.EventKind.LEASE,
                    outcome = KinesisFlowEvent.Outcome.SUCCESS,
                    shardToken = KinesisFlowEvent.redactedToken(key.shardId),
                ),
            )
        } catch (cause: Throwable) {
            heartbeatFailure.compareAndSet(null, cause)
            throw cause
        }
    }
}

@Suppress("CyclomaticComplexMethod", "LongMethod", "ThrowsCount")
private suspend fun consumeShardRecords(
    client: KinesisClient,
    streamName: String,
    key: KinesisShardKey,
    node: KinesisShardNode,
    position: KinesisStartingPosition,
    options: KinesisConsumerOptions,
    checkpointStore: KinesisCheckpointStore,
    leaseStore: KinesisLeaseStore,
    leaseRef: AtomicReference<KinesisLease>,
    leaseLoss: AtomicReference<KinesisLeaseLostException?>,
    metrics: KinesisFlowMetrics,
    output: SendChannel<PendingRecord>,
) {
    val checkpoint = checkpointStore.load(key)
    if (checkpoint is KinesisCheckpoint.ShardEnd) return
    var currentPosition: KinesisStartingPosition = when (checkpoint) {
        is KinesisCheckpoint.Sequence -> KinesisStartingPosition.AtSequenceNumber(checkpoint.sequenceNumber)
        else -> position
    }
    var lastSeenSequenceNumber: String? = (checkpoint as? KinesisCheckpoint.Sequence)?.sequenceNumber
    var shardIterator: String? = null
    var iteratorRetries = 0
    var throttleRetries = 0
    val requestLimit = minOf(options.recordOptions.batchLimit, options.maxRecordsPerPoll)

    while (true) {
        currentCoroutineContext().ensureActive()
        checkLeaseBeforeAction(leaseStore, leaseRef, leaseLoss, options)
        try {
            if (shardIterator == null) {
                shardIterator = client.fetchConsumerShardIterator(streamName, key.shardId, currentPosition)
            }
            val iterator = requireNotNull(shardIterator) { "Kinesis shard iterator was not initialized" }
            val response = client.getRecords(iterator, requestLimit)
            iteratorRetries = 0
            throttleRetries = 0
            val records = response.records.orEmpty()
            metrics.onEvent(
                KinesisFlowEvent.Observation(
                    eventKind = KinesisFlowEvent.EventKind.BATCH,
                    outcome = KinesisFlowEvent.Outcome.SUCCESS,
                    shardToken = KinesisFlowEvent.redactedToken(key.shardId),
                    count = records.size,
                ),
            )

            for (record in records) {
                val sequenceNumber = record.sequenceNumber.also { it.validateSequenceNumber() }
                checkLeaseBeforeAction(leaseStore, leaseRef, leaseLoss, options)
                val pending = PendingRecord(KinesisShardRecord(streamName, key.shardId, record))
                output.send(pending)
                pending.ack.await()
                checkLeaseBeforeAction(leaseStore, leaseRef, leaseLoss, options)
                checkpointStore.save(key, KinesisCheckpoint.Sequence(sequenceNumber), leaseRef.get())
                metrics.onEvent(
                    KinesisFlowEvent.Observation(
                        eventKind = KinesisFlowEvent.EventKind.RECORD,
                        outcome = KinesisFlowEvent.Outcome.SUCCESS,
                        shardToken = KinesisFlowEvent.redactedToken(key.shardId),
                        count = 1,
                    ),
                )
                lastSeenSequenceNumber = sequenceNumber
            }

            val hasReachedEnding = node.endingSequenceNumber?.let { ending ->
                lastSeenSequenceNumber?.let { compareSequence(it, ending) >= 0 } == true
            } == true
            if (hasReachedEnding || (node.endingSequenceNumber == null && response.nextShardIterator == null)) {
                checkLeaseBeforeAction(leaseStore, leaseRef, leaseLoss, options)
                checkpointStore.save(key, KinesisCheckpoint.ShardEnd, leaseRef.get())
                metrics.onEvent(
                    KinesisFlowEvent.Observation(
                        eventKind = KinesisFlowEvent.EventKind.CHECKPOINT,
                        outcome = KinesisFlowEvent.Outcome.SUCCESS,
                        reason = KinesisFlowEvent.Reason.SHARD_END,
                        shardToken = KinesisFlowEvent.redactedToken(key.shardId),
                    ),
                )
                return
            }
            if (response.nextShardIterator == null) {
                throw KinesisCheckpointException(
                    "Kinesis closed shard did not reach ending sequence for shard=" +
                            KinesisFlowEvent.redactedToken(key.shardId),
                )
            }
            shardIterator = response.nextShardIterator
            delay(if (records.isEmpty()) options.effectiveEmptyBackoff else options.recordOptions.pollInterval)
        } catch (e: CancellationException) {
            throw e
        } catch (e: ExpiredIteratorException) {
            iteratorRetries++
            if (lastSeenSequenceNumber == null && currentPosition is KinesisStartingPosition.Latest) throw e
            if (iteratorRetries > options.recordOptions.maxIteratorRetries) throw e
            currentPosition = lastSeenSequenceNumber
                ?.let(KinesisStartingPosition::AfterSequenceNumber)
                ?: currentPosition
            shardIterator = null
            metrics.onEvent(
                KinesisFlowEvent.Observation(
                    eventKind = KinesisFlowEvent.EventKind.RETRY,
                    outcome = KinesisFlowEvent.Outcome.SUCCESS,
                    reason = KinesisFlowEvent.Reason.ITERATOR_EXPIRED,
                    retryClass = KinesisFlowEvent.RetryClass.ITERATOR,
                    shardToken = KinesisFlowEvent.redactedToken(key.shardId),
                    retryCount = iteratorRetries,
                ),
            )
        } catch (e: KinesisException) {
            if (!e.sdkErrorMetadata.isRetryable) throw e
            throttleRetries++
            if (throttleRetries > options.recordOptions.maxThrottleRetries) throw e
            delay(jitteredBackoff(throttleRetries, options.recordOptions))
            metrics.onEvent(
                KinesisFlowEvent.Observation(
                    eventKind = KinesisFlowEvent.EventKind.RETRY,
                    outcome = KinesisFlowEvent.Outcome.SUCCESS,
                    reason = KinesisFlowEvent.Reason.THROTTLED,
                    retryClass = KinesisFlowEvent.RetryClass.THROTTLE,
                    shardToken = KinesisFlowEvent.redactedToken(key.shardId),
                    retryCount = throttleRetries,
                ),
            )
        }
    }
}

private suspend fun checkLeaseBeforeAction(
    leaseStore: KinesisLeaseStore,
    leaseRef: AtomicReference<KinesisLease>,
    leaseLoss: AtomicReference<KinesisLeaseLostException?>,
    options: KinesisConsumerOptions,
) {
    leaseLoss.get()?.let { throw it }
    val renewed = leaseStore.renew(leaseRef.get(), options.leaseDuration)
        ?: throw KinesisLeaseLostException("Kinesis lease ownership was fenced")
    leaseRef.set(renewed)
}

private suspend fun KinesisClient.fetchConsumerShardIterator(
    streamName: String,
    shardId: String,
    position: KinesisStartingPosition,
): String = getShardIterator(GetShardIteratorRequest {
    this.streamName = streamName
    this.shardId = shardId
    when (position) {
        KinesisStartingPosition.TrimHorizon -> shardIteratorType = ShardIteratorType.TrimHorizon
        KinesisStartingPosition.Latest -> shardIteratorType = ShardIteratorType.Latest
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
            timestamp = SmithyInstant.fromEpochSeconds(position.timestamp.epochSecond, position.timestamp.nano)
        }
    }
}).shardIterator ?: throw KinesisCheckpointException(
    "Kinesis getShardIterator returned no iterator for shard=${KinesisFlowEvent.redactedToken(shardId)}",
)

private fun compareSequence(left: String, right: String): Int {
    val leftNumber = left.toBigIntegerOrNull()
    val rightNumber = right.toBigIntegerOrNull()
    return if (leftNumber != null && rightNumber != null) leftNumber.compareTo(rightNumber) else left.compareTo(right)
}

private fun String.toBigIntegerOrNull(): BigInteger? = try {
    BigInteger(this)
} catch (_: NumberFormatException) {
    null
}
