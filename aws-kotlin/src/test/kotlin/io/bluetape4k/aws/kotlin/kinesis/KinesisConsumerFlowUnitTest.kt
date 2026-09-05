package io.bluetape4k.aws.kotlin.kinesis

import aws.sdk.kotlin.services.kinesis.KinesisClient
import aws.sdk.kotlin.services.kinesis.model.GetRecordsRequest
import aws.sdk.kotlin.services.kinesis.model.GetRecordsResponse
import aws.sdk.kotlin.services.kinesis.model.GetShardIteratorRequest
import aws.sdk.kotlin.services.kinesis.model.GetShardIteratorResponse
import aws.sdk.kotlin.services.kinesis.model.ListShardsRequest
import aws.sdk.kotlin.services.kinesis.model.ListShardsResponse
import aws.sdk.kotlin.services.kinesis.model.Record
import aws.sdk.kotlin.services.kinesis.model.Shard
import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldNotBeNull
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds

/** 단일 shard polling, outer emitter, bounded request와 checkpoint 시점을 검증합니다. */
class KinesisConsumerFlowUnitTest {

    private val client = mockk<KinesisClient>(relaxed = true)

    @BeforeEach
    fun setUp() {
        clearMocks(client)
    }

    @Test
    fun `consumer emits ordered records and saves after downstream emit`() = runTest(timeout = 30.seconds) {
        val stream = "consumer-stream"
        val shard = "shardId-000000000000"
        val first = record("1")
        val eventLog = mutableListOf<String>()
        val checkpointStore = RecordingCheckpointStore(eventLog)
        val leaseStore = InMemoryKinesisLeaseStore()

        coEvery { client.listShards(any<ListShardsRequest>()) } returns
                ListShardsResponse { shards = listOf(Shard { shardId = shard }) }
        coEvery { client.getShardIterator(any<GetShardIteratorRequest>()) } returns
                GetShardIteratorResponse { shardIterator = "iterator-1" }
        coEvery { client.getRecords(any<GetRecordsRequest>()) } returns
                GetRecordsResponse { records = listOf(first); nextShardIterator = null }

        val records = mutableListOf<KinesisShardRecord>()
        val job = launch {
            client.consumerFlow(
            streamName = stream,
            consumerGroup = "group",
            streamIdentity = "stream-v1",
            options = KinesisConsumerOptions(
                ownerId = "owner",
                maxShardConcurrency = 1,
                maxRecordsPerPoll = 1,
            ),
            checkpointStore = checkpointStore,
            leaseStore = leaseStore,
            ).collect {
                eventLog += "emit:${it.record.sequenceNumber}"
                records += it
            }
        }
        checkpointStore.terminalSave.await()
        job.cancel()
        job.join()

        records.map { it.record.sequenceNumber } shouldBeEqualTo listOf("1")
        eventLog shouldBeEqualTo listOf("emit:1", "save:1", "save:ShardEnd")
        coVerify { client.getRecords(match { it.limit == 1 }) }
    }

    @Test
    fun `consumer uses inclusive checkpoint position on restart`() = runTest(timeout = 30.seconds) {
        val stream = "consumer-stream"
        val shard = "shardId-000000000000"
        val requests = mutableListOf<GetShardIteratorRequest>()
        val checkpointStore = InMemoryKinesisCheckpointStore()
        val key = KinesisShardKey("stream-v1", "group", shard)
        val leaseStore = InMemoryKinesisLeaseStore()
        val lease = leaseStore.acquire(key, "seed", 60.seconds).shouldNotBeNull()
        checkpointStore.save(key, KinesisCheckpoint.Sequence("42"), lease)
        leaseStore.release(lease)

        coEvery { client.listShards(any<ListShardsRequest>()) } returns
                ListShardsResponse { shards = listOf(Shard { shardId = shard }) }
        coEvery { client.getShardIterator(any<GetShardIteratorRequest>()) } answers {
            requests += firstArg<GetShardIteratorRequest>()
            GetShardIteratorResponse { shardIterator = "iterator-1" }
        }
        coEvery { client.getRecords(any<GetRecordsRequest>()) } returns
                GetRecordsResponse { records = listOf(record("42")); nextShardIterator = null }

        val saved = CompletableDeferred<Unit>()
        val job = launch {
            client.consumerFlow(
            streamName = stream,
            consumerGroup = "group",
            streamIdentity = "stream-v1",
            position = KinesisStartingPosition.Latest,
            options = KinesisConsumerOptions(ownerId = "owner"),
            checkpointStore = checkpointStore,
            leaseStore = leaseStore,
            metrics = KinesisFlowMetrics { event ->
                if (event.eventKind == KinesisFlowEvent.EventKind.RECORD) saved.complete(Unit)
            },
            ).collect { }
        }
        saved.await()
        job.cancel()
        job.join()

        requests.size shouldBeEqualTo 1
        requests.single().startingSequenceNumber shouldBeEqualTo "42"
    }

    @Test
    fun `discovery follows list shards pagination before launching`() = runTest(timeout = 30.seconds) {
        val shard = "shardId-000000000000"
        val observed = CompletableDeferred<Unit>()
        val requests = mutableListOf<ListShardsRequest>()
        val response = GetRecordsResponse {
            records = listOf(record("1"))
            nextShardIterator = null
        }

        coEvery { client.listShards(any<ListShardsRequest>()) } answers {
            val request = firstArg<ListShardsRequest>()
            requests += request
            if (request.nextToken == null) {
                ListShardsResponse {
                    shards = emptyList()
                    nextToken = "page-2"
                }
            } else {
                ListShardsResponse { shards = listOf(Shard { shardId = shard }) }
            }
        }
        coEvery { client.getShardIterator(any<GetShardIteratorRequest>()) } returns
                GetShardIteratorResponse { shardIterator = "iterator-1" }
        coEvery { client.getRecords(any<GetRecordsRequest>()) } returns response

        val job = launch {
            client.consumerFlow(
                streamName = "stream",
                consumerGroup = "group",
                streamIdentity = "stream-v1",
                options = KinesisConsumerOptions(ownerId = "owner"),
                checkpointStore = InMemoryKinesisCheckpointStore(),
                leaseStore = InMemoryKinesisLeaseStore(),
            ).collect { observed.complete(Unit) }
        }
        observed.await()
        job.cancelAndJoin()

        requests.size shouldBeEqualTo 2
        requests[1].nextToken shouldBeEqualTo "page-2"
    }

    @Test
    fun `discovery rejects a repeated list shards token`() = runTest(timeout = 30.seconds) {
        coEvery { client.listShards(any<ListShardsRequest>()) } returns
                ListShardsResponse { nextToken = "repeated-token" }

        val error = assertFailsWith<KinesisShardGraphException> {
            client.consumerFlow(
                streamName = "stream",
                consumerGroup = "group",
                streamIdentity = "stream-v1",
                options = KinesisConsumerOptions(ownerId = "owner"),
                checkpointStore = InMemoryKinesisCheckpointStore(),
                leaseStore = InMemoryKinesisLeaseStore(),
            ).first()
        }

        error.message shouldBeEqualTo "ListShards returned a non-progressing nextToken"
        coVerify(exactly = 2) { client.listShards(any<ListShardsRequest>()) }
    }

    @Test
    fun `discovery preserves the configured list shards page limit`() = runTest(timeout = 30.seconds) {
        var page = 0
        coEvery { client.listShards(any<ListShardsRequest>()) } answers {
            ListShardsResponse { nextToken = "page-${++page}" }
        }

        val error = assertFailsWith<KinesisShardGraphException> {
            client.consumerFlow(
                streamName = "stream",
                consumerGroup = "group",
                streamIdentity = "stream-v1",
                options = KinesisConsumerOptions(ownerId = "owner", maxListShardsPages = 2),
                checkpointStore = InMemoryKinesisCheckpointStore(),
                leaseStore = InMemoryKinesisLeaseStore(),
            ).first()
        }

        error.message shouldBeEqualTo "ListShards pagination exceeded maxListShardsPages=2"
        coVerify(exactly = 2) { client.listShards(any<ListShardsRequest>()) }
    }

    @Test
    fun `unknown parent is not promoted to root`() = runTest(timeout = 30.seconds) {
        coEvery { client.listShards(any<ListShardsRequest>()) } returns
                ListShardsResponse {
                    shards = listOf(
                        Shard {
                            shardId = "child"
                            parentShardId = "missing-parent"
                        },
                    )
                }

        assertFailsWith<KinesisShardGraphException> {
            client.consumerFlow(
                streamName = "stream",
                consumerGroup = "group",
                streamIdentity = "stream-v1",
                options = KinesisConsumerOptions(
                    ownerId = "owner",
                    maxUnknownParentDiscoveries = 1,
                ),
                checkpointStore = InMemoryKinesisCheckpointStore(),
                leaseStore = InMemoryKinesisLeaseStore(),
            ).first()
        }
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
        val shard = "shardId-000000000000"
        val collecting = CompletableDeferred<Unit>()

        coEvery { client.listShards(any<ListShardsRequest>()) } returns
                ListShardsResponse { shards = listOf(Shard { shardId = shard }) }
        coEvery { client.getShardIterator(any<GetShardIteratorRequest>()) } returns
                GetShardIteratorResponse { shardIterator = "iterator-1" }
        coEvery { client.getRecords(any<GetRecordsRequest>()) } returns
                GetRecordsResponse {
                    records = listOf(record("1"))
                    nextShardIterator = "iterator-2"
        }

        val completion = CompletableDeferred<Throwable?>()
        val job = async {
            client.consumerFlow(
                streamName = "stream",
                consumerGroup = "group",
                streamIdentity = "stream-v1",
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
        val shard = "shardId-000000000000"
        val leaseStore = RecordingLeaseStore { throw releaseFailure }
        val shardStarted = CompletableDeferred<Unit>()
        coEvery { client.listShards(any<ListShardsRequest>()) } returns
                ListShardsResponse { shards = listOf(Shard { shardId = shard }) }

        val completion = CompletableDeferred<Throwable?>()
        val job = async {
            client.consumerFlow(
                streamName = "stream",
                consumerGroup = "group",
                streamIdentity = "stream-v1",
                options = KinesisConsumerOptions(ownerId = "owner", leaseReleaseTimeout = 1.seconds),
                checkpointStore = InMemoryKinesisCheckpointStore(),
                leaseStore = leaseStore,
                metrics = KinesisFlowMetrics { event ->
                    if (event.eventKind == KinesisFlowEvent.EventKind.SHARD &&
                        event.outcome == KinesisFlowEvent.Outcome.STARTED
                    ) {
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
        val shard = "shardId-000000000000"
        val leaseStore = RecordingLeaseStore { throw releaseFailure }
        coEvery { client.listShards(any<ListShardsRequest>()) } returns
                ListShardsResponse { shards = listOf(Shard { shardId = shard }) }
        coEvery { client.getShardIterator(any<GetShardIteratorRequest>()) } returns
                GetShardIteratorResponse { shardIterator = "iterator-1" }
        coEvery { client.getRecords(any<GetRecordsRequest>()) } returns
                GetRecordsResponse { records = emptyList(); nextShardIterator = null }

        val completion = CompletableDeferred<Throwable?>()
        val job = async {
            client.consumerFlow(
                streamName = "stream",
                consumerGroup = "group",
                streamIdentity = "stream-v1",
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

    private fun record(sequenceNumber: String): Record = mockk(relaxed = true) {
        every { this@mockk.sequenceNumber } returns sequenceNumber
        every { partitionKey } returns "partition"
        every { this@mockk.data } returns "payload".encodeToByteArray()
    }

    private class RecordingCheckpointStore(
        private val eventLog: MutableList<String>,
    ) : KinesisCheckpointStore {
        private val delegate = InMemoryKinesisCheckpointStore()
        val firstSave = CompletableDeferred<Unit>()
        val terminalSave = CompletableDeferred<Unit>()

        override suspend fun load(key: KinesisShardKey): KinesisCheckpoint? = delegate.load(key)

        override suspend fun save(key: KinesisShardKey, checkpoint: KinesisCheckpoint, lease: KinesisLease) {
            delegate.save(key, checkpoint, lease)
            eventLog += "save:${checkpointName(checkpoint)}"
            firstSave.complete(Unit)
            if (checkpoint is KinesisCheckpoint.ShardEnd) terminalSave.complete(Unit)
        }

        private fun checkpointName(checkpoint: KinesisCheckpoint): String = when (checkpoint) {
            is KinesisCheckpoint.Sequence -> checkpoint.sequenceNumber
            KinesisCheckpoint.ShardEnd -> "ShardEnd"
        }
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
            leaseDuration: kotlin.time.Duration,
        ): KinesisLease = KinesisLease(key, ownerId, leaseCounter = 1)

        override suspend fun renew(
            lease: KinesisLease,
            leaseDuration: kotlin.time.Duration,
        ): KinesisLease = lease

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
