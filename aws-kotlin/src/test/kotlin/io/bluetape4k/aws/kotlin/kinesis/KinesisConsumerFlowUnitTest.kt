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
import io.bluetape4k.assertions.shouldNotBeNull
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
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
}
