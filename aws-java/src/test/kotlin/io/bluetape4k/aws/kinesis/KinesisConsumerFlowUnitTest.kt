package io.bluetape4k.aws.kinesis

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldNotBeNull
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import software.amazon.awssdk.services.kinesis.KinesisAsyncClient
import software.amazon.awssdk.services.kinesis.model.GetRecordsRequest
import software.amazon.awssdk.services.kinesis.model.GetRecordsResponse
import software.amazon.awssdk.services.kinesis.model.GetShardIteratorRequest
import software.amazon.awssdk.services.kinesis.model.GetShardIteratorResponse
import software.amazon.awssdk.services.kinesis.model.ListShardsRequest
import software.amazon.awssdk.services.kinesis.model.ListShardsResponse
import software.amazon.awssdk.services.kinesis.model.Record
import software.amazon.awssdk.services.kinesis.model.SequenceNumberRange
import software.amazon.awssdk.services.kinesis.model.Shard
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class KinesisConsumerFlowUnitTest {

    @Test
    fun `discovers shards and saves each record only after downstream emit returns`() = runTest {
        val client = mockk<KinesisAsyncClient>(relaxed = true)
        val key = KinesisShardKey("orders-v1", "consumer", "shard-0")
        val leaseStore = InMemoryKinesisLeaseStore()
        val events = mutableListOf<String>()
        val delegateStore = InMemoryKinesisCheckpointStore()
        val terminalSave = CompletableDeferred<Unit>()
        val checkpointStore = object : KinesisCheckpointStore {
            override suspend fun load(key: KinesisShardKey): KinesisCheckpoint? = delegateStore.load(key)

            override suspend fun save(key: KinesisShardKey, checkpoint: KinesisCheckpoint, lease: KinesisLease) {
                events += "save:$checkpoint"
                delegateStore.save(key, checkpoint, lease)
                if (checkpoint is KinesisCheckpoint.ShardEnd) terminalSave.complete(Unit)
            }
        }
        val options = KinesisConsumerOptions(
            ownerId = "worker-1",
            recordOptions = KinesisRecordFlowOptions(emptyBackoff = KinesisRecordFlowOptions.MIN_POLL_INTERVAL),
        )
        val shard = Shard.builder()
            .shardId(key.shardId)
            .sequenceNumberRange(SequenceNumberRange.builder().build())
            .build()
        every { client.listShards(any<ListShardsRequest>()) } returns CompletableFuture.completedFuture(
            ListShardsResponse.builder().shards(shard).build(),
        )
        every { client.getShardIterator(any<GetShardIteratorRequest>()) } returns CompletableFuture.completedFuture(
            GetShardIteratorResponse.builder().shardIterator("iter-1").build(),
        )
        every { client.getRecords(any<GetRecordsRequest>()) } returns CompletableFuture.completedFuture(
            GetRecordsResponse.builder()
                .records(Record.builder().sequenceNumber("1").build())
                .nextShardIterator(null)
                .build(),
        )

        val records = mutableListOf<KinesisShardRecord>()
        val job = launch {
            client.consumerFlow(
                streamName = "orders",
                consumerGroup = "consumer",
                streamIdentity = "orders-v1",
                position = KinesisStartingPosition.TrimHorizon,
                options = options,
                checkpointStore = checkpointStore,
                leaseStore = leaseStore,
            ).collect {
                records += it
                events += "emit"
            }
        }
        withTimeout(5_000) { terminalSave.await() }
        job.cancel()
        job.join()

        records.size shouldBeEqualTo 1
        delegateStore.load(key) shouldBeEqualTo KinesisCheckpoint.ShardEnd
        events shouldBeEqualTo listOf(
            "emit",
            "save:Sequence(sequenceNumber=1)",
            "save:ShardEnd",
        )
    }

    @Test
    fun `list shards request is bounded by the configured page size`() = runTest {
        val client = mockk<KinesisAsyncClient>(relaxed = true)
        val request = slot<ListShardsRequest>()
        val firstRequest = CompletableDeferred<Unit>()
        every { client.listShards(capture(request)) } answers {
            firstRequest.complete(Unit)
            CompletableFuture.completedFuture(ListShardsResponse.builder().shards(emptyList()).build())
        }

        val job = launch {
            client.consumerFlow(
                streamName = "orders",
                consumerGroup = "consumer",
                streamIdentity = "orders-v1",
                position = KinesisStartingPosition.Latest,
                options = KinesisConsumerOptions(ownerId = "worker-1"),
                checkpointStore = NoopKinesisCheckpointStore,
                leaseStore = NoopKinesisLeaseStore,
            ).collect()
        }
        withTimeout(5_000) { firstRequest.await() }
        job.cancel()
        job.join()

        request.captured.streamName() shouldBeEqualTo "orders"
        (request.captured.maxResults() ?: 0 <= 1_000).shouldBeTrue()
    }

    @Test
    fun `cancellation completes one bounded lease release and preserves its cause`() = runTest(timeout = 30.seconds) {
        val cancellation = CancellationException("collector cancelled")

        val result = terminateConsumer(cancellation)

        result.leaseStore.releaseCount.get() shouldBeEqualTo 1
        result.leaseStore.releaseCompleted.isCompleted.shouldBeTrue()
        result.completion::class shouldBeEqualTo cancellation::class
        result.completion.message shouldBeEqualTo cancellation.message
        result.completion.failureChain().any { it === cancellation }.shouldBeTrue()
    }

    @Test
    fun `lease release timeout stays bounded and attempts release once`() = runTest(timeout = 30.seconds) {
        val cancellation = CancellationException("collector cancelled")
        val leaseStore = RecordingLeaseStore { awaitCancellation() }

        val result = terminateConsumer(cancellation, leaseStore)

        result.leaseStore.releaseCount.get() shouldBeEqualTo 1
        result.leaseStore.releaseCompleted.isCompleted shouldBeEqualTo false
        result.completion::class shouldBeEqualTo cancellation::class
        result.completion.message shouldBeEqualTo cancellation.message
        result.completion.failureChain().any { it === cancellation }.shouldBeTrue()
    }

    @Test
    fun `shard failure remains primary when lease release fails`() = runTest(timeout = 30.seconds) {
        val primaryFailure = IllegalArgumentException("shard failed")
        val releaseFailure = IllegalStateException("release failed")

        val result = failShardStart(primaryFailure, releaseFailure)

        result.leaseStore.releaseCount.get() shouldBeEqualTo 1
        val failureChain = result.completion.failureChain()
        result.completion::class shouldBeEqualTo primaryFailure::class
        result.completion.message shouldBeEqualTo primaryFailure.message
        failureChain.flatMap { it.suppressed.asIterable() }.any {
            it::class == releaseFailure::class && it.message == releaseFailure.message
        }.shouldBeTrue()
    }

    @Test
    fun `lease release failure is propagated after successful shard completion`() = runTest(timeout = 30.seconds) {
        val releaseFailure = IllegalStateException("release failed")

        val result = finishShard(releaseFailure)

        result.leaseStore.releaseCount.get() shouldBeEqualTo 1
        result.completion::class shouldBeEqualTo releaseFailure::class
        result.completion.message shouldBeEqualTo releaseFailure.message
        result.completion.failureChain().any { it === releaseFailure }.shouldBeTrue()
    }

    private suspend fun terminateConsumer(
        cancellation: CancellationException,
        leaseStore: RecordingLeaseStore = RecordingLeaseStore(),
    ): CancellationResult = supervisorScope {
        val client = mockk<KinesisAsyncClient>(relaxed = true)
        val shardId = "shard-0"
        val shard = Shard.builder()
            .shardId(shardId)
            .sequenceNumberRange(SequenceNumberRange.builder().build())
            .build()
        val collecting = CompletableDeferred<Unit>()

        every { client.listShards(any<ListShardsRequest>()) } returns CompletableFuture.completedFuture(
            ListShardsResponse.builder().shards(shard).build(),
        )
        every { client.getShardIterator(any<GetShardIteratorRequest>()) } returns CompletableFuture.completedFuture(
            GetShardIteratorResponse.builder().shardIterator("iter-1").build(),
        )
        every { client.getRecords(any<GetRecordsRequest>()) } returns CompletableFuture.completedFuture(
            GetRecordsResponse.builder()
                .records(Record.builder().sequenceNumber("1").build())
                .nextShardIterator("iter-2")
                .build(),
        )

        val completion = CompletableDeferred<Throwable?>()
        val job = async {
            client.consumerFlow(
                streamName = "stream",
                consumerGroup = "group",
                streamIdentity = "stream-v1",
                position = KinesisStartingPosition.TrimHorizon,
                options = KinesisConsumerOptions(
                    ownerId = "owner",
                    leaseDuration = 10.seconds,
                    leaseRenewInterval = 5.seconds,
                    leaseReleaseTimeout = 1.seconds,
                ),
                checkpointStore = InMemoryKinesisCheckpointStore(),
                leaseStore = leaseStore,
            ).collect {
                collecting.complete(Unit)
                throw cancellation
            }
        }
        job.invokeOnCompletion { completion.complete(it) }

        val actual = withTimeout(5.seconds) {
            collecting.await()
            job.join()
            leaseStore.releaseStarted.await()
            completion.await().shouldNotBeNull()
        }
        CancellationResult(actual, leaseStore)
    }

    private suspend fun failShardStart(
        primaryFailure: Throwable,
        releaseFailure: Throwable,
    ): CancellationResult = supervisorScope {
        val client = mockk<KinesisAsyncClient>(relaxed = true)
        val shard = Shard.builder()
            .shardId("shard-0")
            .sequenceNumberRange(SequenceNumberRange.builder().build())
            .build()
        val leaseStore = RecordingLeaseStore { throw releaseFailure }
        val shardStarted = CompletableDeferred<Unit>()
        every { client.listShards(any<ListShardsRequest>()) } returns CompletableFuture.completedFuture(
            ListShardsResponse.builder().shards(shard).build(),
        )

        val completion = CompletableDeferred<Throwable?>()
        val job = async {
            client.consumerFlow(
                streamName = "stream",
                consumerGroup = "group",
                streamIdentity = "stream-v1",
                position = KinesisStartingPosition.TrimHorizon,
                options = KinesisConsumerOptions(ownerId = "owner", leaseReleaseTimeout = 1.seconds),
                checkpointStore = InMemoryKinesisCheckpointStore(),
                leaseStore = leaseStore,
                metrics = KinesisFlowMetrics { event ->
                    if (event is KinesisFlowEvent.Shard && event.outcome == "started") {
                        shardStarted.complete(Unit)
                        throw primaryFailure
                    }
                },
            ).collect()
        }
        job.invokeOnCompletion { completion.complete(it) }

        val actual = withTimeout(5.seconds) {
            shardStarted.await()
            job.join()
            leaseStore.releaseStarted.await()
            completion.await().shouldNotBeNull()
        }
        CancellationResult(actual, leaseStore)
    }

    private suspend fun finishShard(
        releaseFailure: Throwable,
    ): CancellationResult = supervisorScope {
        val client = mockk<KinesisAsyncClient>(relaxed = true)
        val shard = Shard.builder()
            .shardId("shard-0")
            .sequenceNumberRange(SequenceNumberRange.builder().build())
            .build()
        val leaseStore = RecordingLeaseStore { throw releaseFailure }
        every { client.listShards(any<ListShardsRequest>()) } returns CompletableFuture.completedFuture(
            ListShardsResponse.builder().shards(shard).build(),
        )
        every { client.getShardIterator(any<GetShardIteratorRequest>()) } returns CompletableFuture.completedFuture(
            GetShardIteratorResponse.builder().shardIterator("iter-1").build(),
        )
        every { client.getRecords(any<GetRecordsRequest>()) } returns CompletableFuture.completedFuture(
            GetRecordsResponse.builder().records(emptyList()).nextShardIterator(null).build(),
        )

        val completion = CompletableDeferred<Throwable?>()
        val job = async {
            client.consumerFlow(
                streamName = "stream",
                consumerGroup = "group",
                streamIdentity = "stream-v1",
                position = KinesisStartingPosition.TrimHorizon,
                options = KinesisConsumerOptions(ownerId = "owner", leaseReleaseTimeout = 1.seconds),
                checkpointStore = InMemoryKinesisCheckpointStore(),
                leaseStore = leaseStore,
            ).collect()
        }
        job.invokeOnCompletion { completion.complete(it) }

        val actual = withTimeout(5.seconds) {
            job.join()
            leaseStore.releaseStarted.await()
            completion.await().shouldNotBeNull()
        }
        CancellationResult(actual, leaseStore)
    }

    private class RecordingLeaseStore(
        private val releaseAction: suspend () -> Unit = {},
    ) : KinesisLeaseStore {
        val releaseCount = AtomicInteger()
        val releaseStarted = CompletableDeferred<Unit>()
        val releaseCompleted = CompletableDeferred<Unit>()

        override suspend fun acquire(
            key: KinesisShardKey,
            ownerId: String,
            leaseDuration: Duration,
        ): KinesisLease = KinesisLease(key, ownerId, leaseCounter = 1)

        override suspend fun renew(lease: KinesisLease, leaseDuration: Duration): KinesisLease = lease

        override suspend fun release(lease: KinesisLease) {
            releaseCount.incrementAndGet()
            releaseStarted.complete(Unit)
            releaseAction()
            releaseCompleted.complete(Unit)
        }
    }

    private data class CancellationResult(
        val completion: Throwable,
        val leaseStore: RecordingLeaseStore,
    )

    private fun Throwable.failureChain(): List<Throwable> = generateSequence(this) { it.cause }.toList()
}
