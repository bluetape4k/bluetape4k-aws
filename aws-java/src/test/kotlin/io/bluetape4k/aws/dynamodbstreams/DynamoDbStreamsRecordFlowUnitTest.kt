package io.bluetape4k.aws.dynamodbstreams

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import software.amazon.awssdk.core.exception.SdkException
import software.amazon.awssdk.services.dynamodb.model.DescribeStreamResponse
import software.amazon.awssdk.services.dynamodb.model.DescribeStreamRequest
import software.amazon.awssdk.services.dynamodb.model.ExpiredIteratorException
import software.amazon.awssdk.services.dynamodb.model.GetRecordsRequest
import software.amazon.awssdk.services.dynamodb.model.GetRecordsResponse
import software.amazon.awssdk.services.dynamodb.model.GetShardIteratorRequest
import software.amazon.awssdk.services.dynamodb.model.GetShardIteratorResponse
import software.amazon.awssdk.services.dynamodb.model.Record
import software.amazon.awssdk.services.dynamodb.model.Shard
import software.amazon.awssdk.services.dynamodb.model.ShardIteratorType
import software.amazon.awssdk.services.dynamodb.model.StreamDescription
import software.amazon.awssdk.services.dynamodb.model.StreamRecord
import software.amazon.awssdk.services.dynamodb.streams.DynamoDbStreamsAsyncClient
import java.util.concurrent.CompletableFuture

class DynamoDbStreamsRecordFlowUnitTest {

    private val client = mockk<DynamoDbStreamsAsyncClient>(relaxed = true)

    @BeforeEach
    fun setUp() {
        clearMocks(client)
    }

    private fun record(sequenceNumber: String): Record = Record.builder()
        .dynamodb(StreamRecord.builder().sequenceNumber(sequenceNumber).build())
        .build()

    private fun iteratorResponse(iterator: String) = GetShardIteratorResponse.builder()
        .shardIterator(iterator)
        .build()

    private fun recordsResponse(records: List<Record>, nextIterator: String? = null) = GetRecordsResponse.builder()
        .records(records)
        .nextShardIterator(nextIterator)
        .build()

    @Test
    fun `emits records and saves checkpoint only after downstream emit returns`() = runTest {
        val eventLog = mutableListOf<String>()
        val store = object : DynamoDbStreamsCheckpointStore {
            override suspend fun load(streamArn: String, shardId: String): String? = null

            override suspend fun save(streamArn: String, shardId: String, sequenceNumber: String) {
                eventLog += "save:$sequenceNumber"
            }
        }
        every { client.getShardIterator(any<GetShardIteratorRequest>()) } returns
                CompletableFuture.completedFuture(iteratorResponse("iter-1"))
        every { client.getRecords(any<GetRecordsRequest>()) } returns
                CompletableFuture.completedFuture(recordsResponse(listOf(record("seq-1"))))

        client.recordFlow("stream", "shard", checkpointStore = store).collect {
            eventLog += "emit"
        }

        eventLog shouldBeEqualTo listOf("emit", "save:seq-1")
    }

    @Test
    fun `checkpoint resumes inclusively at the saved sequence`() = runTest {
        val store = InMemoryDynamoDbStreamsCheckpointStore()
        store.save("stream", "shard", "seq-7")
        val request = slot<GetShardIteratorRequest>()
        every { client.getShardIterator(capture(request)) } returns
                CompletableFuture.completedFuture(iteratorResponse("iter-1"))
        every { client.getRecords(any<GetRecordsRequest>()) } returns
                CompletableFuture.completedFuture(recordsResponse(listOf(record("seq-7"))))

        client.recordFlow("stream", "shard", checkpointStore = store).toList()

        request.captured.shardIteratorType() shouldBeEqualTo ShardIteratorType.AT_SEQUENCE_NUMBER
        request.captured.sequenceNumber() shouldBeEqualTo "seq-7"
    }

    @Test
    fun `maps every supported starting position to the SDK iterator request`() = runTest {
        val cases = listOf(
            DynamoDbStreamsStartingPosition.TrimHorizon to (ShardIteratorType.TRIM_HORIZON to null),
            DynamoDbStreamsStartingPosition.Latest to (ShardIteratorType.LATEST to null),
            DynamoDbStreamsStartingPosition.AtSequenceNumber("seq-at") to
                    (ShardIteratorType.AT_SEQUENCE_NUMBER to "seq-at"),
            DynamoDbStreamsStartingPosition.AfterSequenceNumber("seq-after") to
                    (ShardIteratorType.AFTER_SEQUENCE_NUMBER to "seq-after"),
        )

        for ((position, expected) in cases) {
            clearMocks(client)
            val request = slot<GetShardIteratorRequest>()
            every { client.getShardIterator(capture(request)) } returns
                    CompletableFuture.completedFuture(iteratorResponse("iter-position"))
            every { client.getRecords(any<GetRecordsRequest>()) } returns
                    CompletableFuture.completedFuture(recordsResponse(emptyList()))

            client.recordFlow("stream", "shard", position = position).toList()

            request.captured.shardIteratorType() shouldBeEqualTo expected.first
            request.captured.sequenceNumber() shouldBeEqualTo expected.second
        }
    }

    @Test
    fun `latest fails fast when iterator expires before first checkpoint`() = runTest {
        every { client.getShardIterator(any<GetShardIteratorRequest>()) } returns
                CompletableFuture.completedFuture(iteratorResponse("iter-latest"))
        every { client.getRecords(any<GetRecordsRequest>()) } returns failedFuture(
            ExpiredIteratorException.builder().message("expired").build(),
        )

        assertFailsWith<ExpiredIteratorException> {
            client.recordFlow("stream", "shard", position = DynamoDbStreamsStartingPosition.Latest).toList()
        }
        verify(exactly = 1) { client.getShardIterator(any<GetShardIteratorRequest>()) }
    }

    @Test
    fun `retryable service error retries and then emits`() = runTest {
        val retryable = mockk<SdkException>(relaxed = true) {
            every { retryable() } returns true
        }
        var calls = 0
        every { client.getShardIterator(any<GetShardIteratorRequest>()) } returns
                CompletableFuture.completedFuture(iteratorResponse("iter-1"))
        every { client.getRecords(any<GetRecordsRequest>()) } answers {
            if (calls++ == 0) failedFuture(retryable)
            else CompletableFuture.completedFuture(recordsResponse(listOf(record("seq-2"))))
        }

        client.recordFlow("stream", "shard").toList().size shouldBeEqualTo 1
        verify(exactly = 2) { client.getRecords(any<GetRecordsRequest>()) }
    }

    @Test
    fun `recovers from expired iterator using the last emitted sequence`() = runTest {
        every { client.getShardIterator(any<GetShardIteratorRequest>()) } returnsMany listOf(
            CompletableFuture.completedFuture(iteratorResponse("iter-1")),
            CompletableFuture.completedFuture(iteratorResponse("iter-recovered")),
        )
        var calls = 0
        every { client.getRecords(any<GetRecordsRequest>()) } answers {
            when (calls++) {
                0 -> CompletableFuture.completedFuture(recordsResponse(listOf(record("seq-1")), "iter-expired"))
                1 -> failedFuture(ExpiredIteratorException.builder().message("expired").build())
                else -> CompletableFuture.completedFuture(recordsResponse(listOf(record("seq-2"))))
            }
        }

        client.recordFlow("stream", "shard").toList().size shouldBeEqualTo 2
        verify(exactly = 2) { client.getShardIterator(any<GetShardIteratorRequest>()) }
    }

    @Test
    fun `propagates trimmed data without fallback`() = runTest {
        every { client.getShardIterator(any<GetShardIteratorRequest>()) } returns
                CompletableFuture.completedFuture(iteratorResponse("iter-1"))
        every { client.getRecords(any<GetRecordsRequest>()) } returns failedFuture(
            software.amazon.awssdk.services.dynamodb.model.TrimmedDataAccessException.builder()
                .message("trimmed")
                .build(),
        )

        assertFailsWith<software.amazon.awssdk.services.dynamodb.model.TrimmedDataAccessException> {
            client.recordFlow("stream", "shard").toList()
        }
        verify(exactly = 1) { client.getRecords(any<GetRecordsRequest>()) }
    }

    @Test
    fun `propagates non-retryable service errors immediately`() = runTest {
        val nonRetryable = mockk<SdkException>(relaxed = true) {
            every { retryable() } returns false
        }
        every { client.getShardIterator(any<GetShardIteratorRequest>()) } returns
                CompletableFuture.completedFuture(iteratorResponse("iter-1"))
        every { client.getRecords(any<GetRecordsRequest>()) } returns failedFuture(nonRetryable)

        assertFailsWith<SdkException> {
            client.recordFlow("stream", "shard").toList()
        }
        verify(exactly = 1) { client.getRecords(any<GetRecordsRequest>()) }
    }

    @Test
    fun `fails after throttle retry budget is exhausted`() = runTest {
        val options = DynamoDbStreamsRecordFlowOptions(maxThrottleRetries = 2)
        val retryable = mockk<SdkException>(relaxed = true) {
            every { retryable() } returns true
        }
        every { client.getShardIterator(any<GetShardIteratorRequest>()) } returns
                CompletableFuture.completedFuture(iteratorResponse("iter-1"))
        every { client.getRecords(any<GetRecordsRequest>()) } returns failedFuture(retryable)

        assertFailsWith<SdkException> {
            client.recordFlow("stream", "shard", options = options).toList()
        }
        verify(exactly = 3) { client.getRecords(any<GetRecordsRequest>()) }
    }

    @Test
    fun `does not advance checkpoint when save fails`() = runTest {
        val store = InMemoryDynamoDbStreamsCheckpointStore()
        val expected = IllegalStateException("checkpoint unavailable")
        val failingStore = object : DynamoDbStreamsCheckpointStore {
            override suspend fun load(streamArn: String, shardId: String): String? = store.load(streamArn, shardId)

            override suspend fun save(streamArn: String, shardId: String, sequenceNumber: String) {
                throw expected
            }
        }
        every { client.getShardIterator(any<GetShardIteratorRequest>()) } returns
                CompletableFuture.completedFuture(iteratorResponse("iter-1"))
        every { client.getRecords(any<GetRecordsRequest>()) } returns
                CompletableFuture.completedFuture(recordsResponse(listOf(record("seq-save"))))

        assertFailsWith<IllegalStateException> {
            client.recordFlow("stream", "shard", checkpointStore = failingStore).toList()
        }
        store.load("stream", "shard") shouldBeEqualTo null
    }

    @Test
    fun `cancellation during downstream collection prevents checkpoint save`() = runTest {
        val store = InMemoryDynamoDbStreamsCheckpointStore()
        every { client.getShardIterator(any<GetShardIteratorRequest>()) } returns
                CompletableFuture.completedFuture(iteratorResponse("iter-1"))
        every { client.getRecords(any<GetRecordsRequest>()) } returns
                CompletableFuture.completedFuture(
                    recordsResponse(listOf(record("seq-cancel-1"), record("seq-cancel-2")), "iter-2"),
                )

        client.recordFlow("stream", "shard", checkpointStore = store).take(1).toList()

        store.load("stream", "shard") shouldBeEqualTo null
        verify(exactly = 1) { client.getRecords(any<GetRecordsRequest>()) }
    }

    @Test
    fun `metrics observe shard lifecycle batches checkpoints and retries`() = runTest {
        val events = mutableListOf<String>()
        val metrics = LambdaDynamoDbStreamsFlowMetrics(
            onStarted = { events += "started:$it" },
            onBatchRead = { shardId, count -> events += "batch:$shardId:$count" },
            onCheckpoint = { shardId, sequence -> events += "checkpoint:$shardId:$sequence" },
            onRetrying = { shardId, attempt, _ -> events += "retry:$shardId:$attempt" },
            onCompleted = { events += "completed:$it" },
        )
        val retryable = mockk<SdkException>(relaxed = true) {
            every { retryable() } returns true
        }
        var calls = 0
        every { client.getShardIterator(any<GetShardIteratorRequest>()) } returns
                CompletableFuture.completedFuture(iteratorResponse("iter-1"))
        every { client.getRecords(any<GetRecordsRequest>()) } answers {
            if (calls++ == 0) failedFuture(retryable)
            else CompletableFuture.completedFuture(recordsResponse(listOf(record("seq-metrics"))))
        }

        client.recordFlow("stream", "shard", metrics = metrics).toList()

        events shouldBeEqualTo listOf(
            "started:shard",
            "retry:shard:1",
            "batch:shard:1",
            "checkpoint:shard:seq-metrics",
            "completed:shard",
        )
    }

    @Test
    fun `shard flow waits for child after parent and preserves envelope`() = runTest {
        val parent = Shard.builder().shardId("parent").build()
        val child = Shard.builder().shardId("child").parentShardId("parent").build()
        val description = StreamDescription.builder().shards(parent, child).build()
        every { client.describeStream(any<DescribeStreamRequest>()) } returns CompletableFuture.completedFuture(
            DescribeStreamResponse.builder().streamDescription(description).build(),
        )
        every { client.getShardIterator(any<GetShardIteratorRequest>()) } answers {
            val shardId = firstArg<GetShardIteratorRequest>().shardId()
            CompletableFuture.completedFuture(iteratorResponse("iter-$shardId"))
        }
        every { client.getRecords(any<GetRecordsRequest>()) } answers {
            val iterator = firstArg<GetRecordsRequest>().shardIterator()
            CompletableFuture.completedFuture(recordsResponse(listOf(record(iterator))))
        }

        val result = client.shardRecordFlow("stream").toList()

        result.map { it.shardId } shouldBeEqualTo listOf("parent", "child")
        result.all { it.streamArn == "stream" } shouldBeEqualTo true
    }

    private fun <T> failedFuture(cause: Throwable): CompletableFuture<T> = CompletableFuture<T>().apply {
        completeExceptionally(cause)
    }
}
