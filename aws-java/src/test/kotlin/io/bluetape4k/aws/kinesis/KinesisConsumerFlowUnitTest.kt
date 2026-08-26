package io.bluetape4k.aws.kinesis

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
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
}
