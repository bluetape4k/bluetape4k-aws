package io.bluetape4k.aws.ktor.kinesis

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.assertions.shouldBeTrue
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import software.amazon.awssdk.core.SdkBytes
import software.amazon.awssdk.core.exception.SdkClientException
import software.amazon.awssdk.services.kinesis.KinesisAsyncClient
import software.amazon.awssdk.services.kinesis.model.GetRecordsRequest
import software.amazon.awssdk.services.kinesis.model.GetRecordsResponse
import software.amazon.awssdk.services.kinesis.model.GetShardIteratorRequest
import software.amazon.awssdk.services.kinesis.model.GetShardIteratorResponse
import software.amazon.awssdk.services.kinesis.model.PutRecordRequest
import software.amazon.awssdk.services.kinesis.model.PutRecordResponse
import software.amazon.awssdk.services.kinesis.model.PutRecordsRequest
import software.amazon.awssdk.services.kinesis.model.PutRecordsRequestEntry
import software.amazon.awssdk.services.kinesis.model.PutRecordsResponse
import software.amazon.awssdk.services.kinesis.model.Record
import software.amazon.awssdk.services.kinesis.model.ShardIteratorType
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CompletableFuture

class KinesisKtorTemplateTest {

    @Test
    fun `putRecord maps stream partition key and bytes`() = runTest {
        val client = mockk<KinesisAsyncClient>()
        val request = slot<PutRecordRequest>()
        val response = PutRecordResponse.builder()
            .sequenceNumber("seq-1")
            .shardId("shardId-000000000000")
            .build()

        every { client.putRecord(capture(request)) } returns CompletableFuture.completedFuture(response)

        val result = template(client).putRecord(
            KinesisPutRecordRequest(
                streamName = "orders",
                partitionKey = "order-1",
                data = SdkBytes.fromUtf8String("hello"),
            )
        )

        result shouldBeEqualTo response
        request.captured.streamName() shouldBeEqualTo "orders"
        request.captured.partitionKey() shouldBeEqualTo "order-1"
        request.captured.data().asUtf8String() shouldBeEqualTo "hello"
    }

    @Test
    fun `putRecords rejects empty entry list`() = runTest {
        val error = assertFailsWith<IllegalArgumentException> {
            template(mockk(relaxed = true)).putRecords("orders", emptyList())
        }

        error.message.orEmpty() shouldContain "entries"
    }

    @Test
    fun `putRecords maps entries`() = runTest {
        val client = mockk<KinesisAsyncClient>()
        val request = slot<PutRecordsRequest>()
        val response = PutRecordsResponse.builder().failedRecordCount(0).build()
        val entries = listOf(
            PutRecordsRequestEntry.builder()
                .partitionKey("order-1")
                .data(SdkBytes.fromUtf8String("payload-1"))
                .build()
        )

        every { client.putRecords(capture(request)) } returns CompletableFuture.completedFuture(response)

        val result = template(client).putRecords("orders", entries)

        result shouldBeEqualTo response
        request.captured.streamName() shouldBeEqualTo "orders"
        request.captured.records().size shouldBeEqualTo 1
        request.captured.records().first().partitionKey() shouldBeEqualTo "order-1"
    }

    @Test
    fun `stream and flow options reject invalid limits`() {
        assertFailsWith<IllegalArgumentException> {
            KinesisKtorStream(shardCount = 0)
        }
        assertFailsWith<IllegalArgumentException> {
            KinesisRecordFlowOptions(batchLimit = 0)
        }
        assertFailsWith<IllegalArgumentException> {
            KinesisRecordFlowOptions(maxIteratorRetries = -1)
        }
        assertFailsWith<IllegalArgumentException> {
            KinesisRecordFlowOptions(maxThrottleRetries = -1)
        }
        assertFailsWith<IllegalArgumentException> {
            KinesisRecordFlowOptions(jitterRatio = 1.1)
        }
    }

    @Test
    fun `getShardIterator maps explicit iterator request`() = runTest {
        val client = mockk<KinesisAsyncClient>()
        val request = slot<GetShardIteratorRequest>()
        val response = GetShardIteratorResponse.builder().shardIterator("iterator-1").build()

        every { client.getShardIterator(capture(request)) } returns CompletableFuture.completedFuture(response)

        val result = template(client).getShardIterator(
            KinesisShardIteratorRequest(
                streamName = "orders",
                shardId = "shardId-000000000000",
                type = ShardIteratorType.AFTER_SEQUENCE_NUMBER,
                startingSequenceNumber = "42",
            )
        )

        result shouldBeEqualTo response
        request.captured.streamName() shouldBeEqualTo "orders"
        request.captured.shardId() shouldBeEqualTo "shardId-000000000000"
        request.captured.shardIteratorType() shouldBeEqualTo ShardIteratorType.AFTER_SEQUENCE_NUMBER
        request.captured.startingSequenceNumber() shouldBeEqualTo "42"
    }

    @Test
    fun `recordFlow maps starting positions to shard iterator requests`() = runTest {
        val timestamp = Instant.parse("2026-07-01T00:00:00Z")
        val cases = listOf(
            FlowCase(KinesisStartingPosition.TrimHorizon, ShardIteratorType.TRIM_HORIZON),
            FlowCase(KinesisStartingPosition.Latest, ShardIteratorType.LATEST),
            FlowCase(KinesisStartingPosition.AtSequenceNumber("10"), ShardIteratorType.AT_SEQUENCE_NUMBER, "10"),
            FlowCase(KinesisStartingPosition.AfterSequenceNumber("11"), ShardIteratorType.AFTER_SEQUENCE_NUMBER, "11"),
            FlowCase(KinesisStartingPosition.AtTimestamp(timestamp), ShardIteratorType.AT_TIMESTAMP, timestamp = timestamp),
        )

        cases.forEach { case ->
            val client = mockk<KinesisAsyncClient>()
            val request = slot<GetShardIteratorRequest>()
            every { client.getShardIterator(capture(request)) } returns
                    CompletableFuture.completedFuture(GetShardIteratorResponse.builder().shardIterator("it").build())
            every { client.getRecords(any<GetRecordsRequest>()) } returns
                    CompletableFuture.completedFuture(GetRecordsResponse.builder().records(emptyList()).build())

            template(client).recordFlow(
                KinesisRecordFlowRequest(
                    streamName = "orders",
                    shardId = "shardId-000000000000",
                    position = case.position,
                    options = flowOptions(),
                )
            ).toList()

            request.captured.shardIteratorType() shouldBeEqualTo case.type
            request.captured.startingSequenceNumber() shouldBeEqualTo case.sequenceNumber
            request.captured.timestamp() shouldBeEqualTo case.timestamp
        }
    }

    @Test
    fun `recordFlow is cold and stops when next shard iterator is absent`() = runTest {
        val client = mockk<KinesisAsyncClient>()
        val record = Record.builder()
            .sequenceNumber("seq-1")
            .partitionKey("order-1")
            .data(SdkBytes.fromUtf8String("payload-1"))
            .build()

        every { client.getShardIterator(any<GetShardIteratorRequest>()) } returns
                CompletableFuture.completedFuture(GetShardIteratorResponse.builder().shardIterator("it-1").build())
        every { client.getRecords(any<GetRecordsRequest>()) } returns
                CompletableFuture.completedFuture(GetRecordsResponse.builder().records(record).build())

        val flow = template(client).recordFlow(
            KinesisRecordFlowRequest(
                streamName = "orders",
                shardId = "shardId-000000000000",
                options = flowOptions(),
            )
        )

        verify(exactly = 0) { client.getShardIterator(any<GetShardIteratorRequest>()) }
        verify(exactly = 0) { client.getRecords(any<GetRecordsRequest>()) }

        val records = flow.toList()

        records.size shouldBeEqualTo 1
        records.first().sequenceNumber() shouldBeEqualTo "seq-1"
        verify(exactly = 1) { client.getShardIterator(any<GetShardIteratorRequest>()) }
        verify(exactly = 1) { client.getRecords(any<GetRecordsRequest>()) }
    }

    @Test
    fun `recordFlow can be collected twice and refetches iterator`() = runTest {
        val client = mockk<KinesisAsyncClient>()

        every { client.getShardIterator(any<GetShardIteratorRequest>()) } returns
                CompletableFuture.completedFuture(GetShardIteratorResponse.builder().shardIterator("it").build())
        every { client.getRecords(any<GetRecordsRequest>()) } returns
                CompletableFuture.completedFuture(GetRecordsResponse.builder().records(emptyList()).build())

        val flow = template(client).recordFlow(
            KinesisRecordFlowRequest(
                streamName = "orders",
                shardId = "shardId-000000000000",
                options = flowOptions(),
            )
        )

        flow.toList()
        flow.toList()

        verify(exactly = 2) { client.getShardIterator(any<GetShardIteratorRequest>()) }
        verify(exactly = 2) { client.getRecords(any<GetRecordsRequest>()) }
    }

    @Test
    fun `recordFlow propagates cancellation to pending AWS future`() = runTest {
        val client = mockk<KinesisAsyncClient>()
        val pending = CompletableFuture<GetRecordsResponse>()

        every { client.getShardIterator(any<GetShardIteratorRequest>()) } returns
                CompletableFuture.completedFuture(GetShardIteratorResponse.builder().shardIterator("it").build())
        every { client.getRecords(any<GetRecordsRequest>()) } returns pending

        val job = launch {
            template(client).recordFlow(
                KinesisRecordFlowRequest(
                    streamName = "orders",
                    shardId = "shardId-000000000000",
                    options = flowOptions(),
                )
            ).toList()
        }

        advanceUntilIdle()
        job.cancelAndJoin()

        pending.isCancelled.shouldBeTrue()
    }

    @Test
    fun `failed Kinesis future preserves original exception`() = runTest {
        val client = mockk<KinesisAsyncClient>()
        val failure = SdkClientException.create("boom")

        every { client.putRecord(any<PutRecordRequest>()) } returns CompletableFuture.failedFuture(failure)

        val error = assertFailsWith<SdkClientException> {
            template(client).putRecord(
                KinesisPutRecordRequest(
                    streamName = "orders",
                    partitionKey = "order-1",
                    data = SdkBytes.fromUtf8String("payload"),
                )
            )
        }

        error shouldBeEqualTo failure
    }

    private fun template(client: KinesisAsyncClient): KinesisKtorTemplate =
        KinesisKtorTemplate(client)

    private fun flowOptions(): KinesisRecordFlowOptions =
        KinesisRecordFlowOptions(
            pollInterval = Duration.ZERO,
            emptyBackoff = Duration.ZERO,
            maxIteratorRetries = 1,
            maxThrottleRetries = 1,
            initialThrottleBackoff = Duration.ZERO,
            maxThrottleBackoff = Duration.ZERO,
        )

    private data class FlowCase(
        val position: KinesisStartingPosition,
        val type: ShardIteratorType,
        val sequenceNumber: String? = null,
        val timestamp: Instant? = null,
    )
}
