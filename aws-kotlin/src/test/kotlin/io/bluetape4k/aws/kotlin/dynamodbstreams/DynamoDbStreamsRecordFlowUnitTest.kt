package io.bluetape4k.aws.kotlin.dynamodbstreams

import aws.sdk.kotlin.services.dynamodbstreams.DynamoDbStreamsClient
import aws.sdk.kotlin.services.dynamodbstreams.model.DescribeStreamResponse
import aws.sdk.kotlin.services.dynamodbstreams.model.DynamoDbStreamsException
import aws.sdk.kotlin.services.dynamodbstreams.model.ExpiredIteratorException
import aws.sdk.kotlin.services.dynamodbstreams.model.GetRecordsRequest
import aws.sdk.kotlin.services.dynamodbstreams.model.GetRecordsResponse
import aws.sdk.kotlin.services.dynamodbstreams.model.GetShardIteratorRequest
import aws.sdk.kotlin.services.dynamodbstreams.model.GetShardIteratorResponse
import aws.sdk.kotlin.services.dynamodbstreams.model.Record
import aws.sdk.kotlin.services.dynamodbstreams.model.Shard
import aws.sdk.kotlin.services.dynamodbstreams.model.StreamDescription
import aws.sdk.kotlin.services.dynamodbstreams.model.StreamRecord
import aws.sdk.kotlin.services.dynamodbstreams.model.ShardIteratorType
import aws.sdk.kotlin.services.dynamodbstreams.model.TrimmedDataAccessException
import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class DynamoDbStreamsRecordFlowUnitTest {

    private val client = mockk<DynamoDbStreamsClient>(relaxed = true)

    @BeforeEach
    fun setUp() {
        clearMocks(client)
    }

    private fun record(sequenceNumber: String): Record = mockk(relaxed = true) {
        every { dynamodb } returns StreamRecord { this.sequenceNumber = sequenceNumber }
    }

    private fun iteratorResponse(iterator: String) = GetShardIteratorResponse { shardIterator = iterator }

    private fun recordsResponse(records: List<Record>, nextIterator: String? = null) = GetRecordsResponse {
        this.records = records
        nextShardIterator = nextIterator
    }

    @Test
    fun `emits records and saves checkpoint only after downstream emit returns`() = runTest {
        val eventLog = mutableListOf<String>()
        val store = object : DynamoDbStreamsCheckpointStore {
            override suspend fun load(streamArn: String, shardId: String): String? = null

            override suspend fun save(streamArn: String, shardId: String, sequenceNumber: String) {
                eventLog += "save:$sequenceNumber"
            }
        }
        val item = record("seq-1")
        coEvery { client.getShardIterator(any<GetShardIteratorRequest>()) } returns iteratorResponse("iter-1")
        coEvery { client.getRecords(any<GetRecordsRequest>()) } returns recordsResponse(listOf(item))

        client.recordFlow("stream", "shard", checkpointStore = store).collect {
            eventLog += "emit"
        }

        eventLog shouldBeEqualTo listOf("emit", "save:seq-1")
    }

    @Test
    fun `checkpoint resumes inclusively at the saved sequence`() = runTest {
        val store = InMemoryDynamoDbStreamsCheckpointStore()
        store.save("stream", "shard", "seq-7")
        val requests = mutableListOf<GetShardIteratorRequest>()
        coEvery { client.getShardIterator(any<GetShardIteratorRequest>()) } answers {
            requests += firstArg<GetShardIteratorRequest>()
            iteratorResponse("iter-1")
        }
        coEvery { client.getRecords(any<GetRecordsRequest>()) } returns recordsResponse(listOf(record("seq-7")))

        client.recordFlow("stream", "shard", checkpointStore = store).toList()

        requests.single().shardIteratorType shouldBeEqualTo ShardIteratorType.AtSequenceNumber
        requests.single().sequenceNumber shouldBeEqualTo "seq-7"
    }

    @Test
    fun `maps every supported starting position to the SDK iterator request`() = runTest {
        val cases = listOf(
            DynamoDbStreamsStartingPosition.TrimHorizon to (ShardIteratorType.TrimHorizon to null),
            DynamoDbStreamsStartingPosition.Latest to (ShardIteratorType.Latest to null),
            DynamoDbStreamsStartingPosition.AtSequenceNumber("seq-at") to
                    (ShardIteratorType.AtSequenceNumber to "seq-at"),
            DynamoDbStreamsStartingPosition.AfterSequenceNumber("seq-after") to
                    (ShardIteratorType.AfterSequenceNumber to "seq-after"),
        )

        for ((position, expected) in cases) {
            val requests = mutableListOf<GetShardIteratorRequest>()
            clearMocks(client)
            coEvery { client.getShardIterator(any<GetShardIteratorRequest>()) } answers {
                requests += firstArg<GetShardIteratorRequest>()
                iteratorResponse("iter-position")
            }
            coEvery { client.getRecords(any<GetRecordsRequest>()) } returns recordsResponse(emptyList())

            client.recordFlow("stream", "shard", position = position).toList()

            requests.single().shardIteratorType shouldBeEqualTo expected.first
            requests.single().sequenceNumber shouldBeEqualTo expected.second
        }
    }

    @Test
    fun `latest fails fast when iterator expires before first checkpoint`() = runTest {
        coEvery { client.getShardIterator(any<GetShardIteratorRequest>()) } returns iteratorResponse("iter-latest")
        coEvery { client.getRecords(any<GetRecordsRequest>()) } throws ExpiredIteratorException { message = "expired" }

        assertFailsWith<ExpiredIteratorException> {
            client.recordFlow("stream", "shard", position = DynamoDbStreamsStartingPosition.Latest).toList()
        }
        coVerify(exactly = 1) { client.getShardIterator(any<GetShardIteratorRequest>()) }
    }

    @Test
    fun `retryable service error retries and then emits`() = runTest {
        val retryable = mockk<DynamoDbStreamsException>(relaxed = true) {
            every { sdkErrorMetadata } returns mockk { every { isRetryable } returns true }
        }
        var calls = 0
        coEvery { client.getShardIterator(any<GetShardIteratorRequest>()) } returns iteratorResponse("iter-1")
        coEvery { client.getRecords(any<GetRecordsRequest>()) } answers {
            if (calls++ == 0) throw retryable else recordsResponse(listOf(record("seq-2")))
        }

        client.recordFlow("stream", "shard").toList().size shouldBeEqualTo 1
        coVerify(exactly = 2) { client.getRecords(any<GetRecordsRequest>()) }
    }

    @Test
    fun `recovers from expired iterator using the last emitted sequence`() = runTest {
        coEvery { client.getShardIterator(any<GetShardIteratorRequest>()) } returnsMany listOf(
            iteratorResponse("iter-1"),
            iteratorResponse("iter-recovered"),
        )
        var calls = 0
        coEvery { client.getRecords(any<GetRecordsRequest>()) } answers {
            when (calls++) {
                0 -> recordsResponse(listOf(record("seq-1")), "iter-expired")
                1 -> throw ExpiredIteratorException { message = "expired" }
                else -> recordsResponse(listOf(record("seq-2")))
            }
        }

        client.recordFlow("stream", "shard").toList().size shouldBeEqualTo 2
        coVerify(exactly = 2) { client.getShardIterator(any<GetShardIteratorRequest>()) }
    }

    @Test
    fun `propagates trimmed data without fallback`() = runTest {
        coEvery { client.getShardIterator(any<GetShardIteratorRequest>()) } returns iteratorResponse("iter-1")
        coEvery { client.getRecords(any<GetRecordsRequest>()) } throws
                TrimmedDataAccessException { message = "trimmed" }

        assertFailsWith<TrimmedDataAccessException> {
            client.recordFlow("stream", "shard").toList()
        }
        coVerify(exactly = 1) { client.getRecords(any<GetRecordsRequest>()) }
    }

    @Test
    fun `propagates non-retryable service errors immediately`() = runTest {
        val nonRetryable = mockk<DynamoDbStreamsException>(relaxed = true) {
            every { sdkErrorMetadata } returns mockk { every { isRetryable } returns false }
        }
        coEvery { client.getShardIterator(any<GetShardIteratorRequest>()) } returns iteratorResponse("iter-1")
        coEvery { client.getRecords(any<GetRecordsRequest>()) } throws nonRetryable

        assertFailsWith<DynamoDbStreamsException> {
            client.recordFlow("stream", "shard").toList()
        }
        coVerify(exactly = 1) { client.getRecords(any<GetRecordsRequest>()) }
    }

    @Test
    fun `fails after throttle retry budget is exhausted`() = runTest {
        val options = DynamoDbStreamsRecordFlowOptions(maxThrottleRetries = 2)
        val retryable = mockk<DynamoDbStreamsException>(relaxed = true) {
            every { sdkErrorMetadata } returns mockk { every { isRetryable } returns true }
        }
        coEvery { client.getShardIterator(any<GetShardIteratorRequest>()) } returns iteratorResponse("iter-1")
        coEvery { client.getRecords(any<GetRecordsRequest>()) } throws retryable

        assertFailsWith<DynamoDbStreamsException> {
            client.recordFlow("stream", "shard", options = options).toList()
        }
        coVerify(exactly = 3) { client.getRecords(any<GetRecordsRequest>()) }
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
        coEvery { client.getShardIterator(any<GetShardIteratorRequest>()) } returns iteratorResponse("iter-1")
        coEvery { client.getRecords(any<GetRecordsRequest>()) } returns recordsResponse(listOf(record("seq-save")))

        assertFailsWith<IllegalStateException> {
            client.recordFlow("stream", "shard", checkpointStore = failingStore).toList()
        }
        store.load("stream", "shard") shouldBeEqualTo null
    }

    @Test
    fun `cancellation during downstream collection prevents checkpoint save`() = runTest {
        val store = InMemoryDynamoDbStreamsCheckpointStore()
        coEvery { client.getShardIterator(any<GetShardIteratorRequest>()) } returns iteratorResponse("iter-1")
        coEvery { client.getRecords(any<GetRecordsRequest>()) } returns
                recordsResponse(listOf(record("seq-cancel-1"), record("seq-cancel-2")), "iter-2")

        client.recordFlow("stream", "shard", checkpointStore = store).take(1).toList()

        store.load("stream", "shard") shouldBeEqualTo null
        coVerify(exactly = 1) { client.getRecords(any<GetRecordsRequest>()) }
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
        val retryable = mockk<DynamoDbStreamsException>(relaxed = true) {
            every { sdkErrorMetadata } returns mockk { every { isRetryable } returns true }
        }
        var calls = 0
        coEvery { client.getShardIterator(any<GetShardIteratorRequest>()) } returns iteratorResponse("iter-1")
        coEvery { client.getRecords(any<GetRecordsRequest>()) } answers {
            if (calls++ == 0) throw retryable else recordsResponse(listOf(record("seq-metrics")))
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
        val parent = mockk<Shard>(relaxed = true) {
            every { shardId } returns "parent"
            every { parentShardId } returns null
        }
        val child = mockk<Shard>(relaxed = true) {
            every { shardId } returns "child"
            every { parentShardId } returns "parent"
        }
        val description = mockk<StreamDescription>(relaxed = true) {
            every { shards } returns listOf(parent, child)
            every { lastEvaluatedShardId } returns null
        }
        coEvery { client.describeStream(any()) } returns mockk<DescribeStreamResponse> {
            every { streamDescription } returns description
        }
        coEvery { client.getShardIterator(any<GetShardIteratorRequest>()) } answers {
            iteratorResponse("iter-${firstArg<GetShardIteratorRequest>().shardId}")
        }
        coEvery { client.getRecords(any<GetRecordsRequest>()) } answers {
            val iterator = firstArg<GetRecordsRequest>().shardIterator
            recordsResponse(listOf(record(iterator ?: "unknown")))
        }

        val result = client.shardRecordFlow("stream").toList()

        result.map { it.shardId } shouldBeEqualTo listOf("parent", "child")
        result.all { it.streamArn == "stream" } shouldBeEqualTo true
    }
}
