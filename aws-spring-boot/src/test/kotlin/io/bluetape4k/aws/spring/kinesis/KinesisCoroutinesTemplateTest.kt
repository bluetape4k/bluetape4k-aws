package io.bluetape4k.aws.spring.kinesis

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldContain
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.flow.toList
import org.junit.jupiter.api.Test
import software.amazon.awssdk.core.SdkBytes
import software.amazon.awssdk.services.kinesis.KinesisAsyncClient
import software.amazon.awssdk.services.kinesis.model.CreateStreamRequest
import software.amazon.awssdk.services.kinesis.model.CreateStreamResponse
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

class KinesisCoroutinesTemplateTest {

    @Test
    fun `createConfiguredStream uses configured shard count`() = runTest {
        val client = mockk<KinesisAsyncClient>()
        val request = slot<CreateStreamRequest>()
        val response = CreateStreamResponse.builder().build()

        every { client.createStream(capture(request)) } returns CompletableFuture.completedFuture(response)

        val result = template(
            client = client,
            properties = KinesisProperties(
                region = "us-east-1",
                streams = mapOf("orders" to KinesisProperties.Stream(shardCount = 3)),
            ),
        ).createConfiguredStream("orders")

        result shouldBeEqualTo response
        request.captured.streamName() shouldBeEqualTo "orders"
        request.captured.shardCount() shouldBeEqualTo 3
        verify(exactly = 1) { client.createStream(any<CreateStreamRequest>()) }
    }

    @Test
    fun `missing configured stream fails fast`() = runTest {
        val error = assertFailsWith<IllegalArgumentException> {
            template(mockk(relaxed = true)).createConfiguredStream("missing")
        }

        error.message.orEmpty() shouldContain "not configured"
    }

    @Test
    fun `putRecord maps stream partition key and bytes`() = runTest {
        val client = mockk<KinesisAsyncClient>()
        val request = slot<PutRecordRequest>()
        val response = PutRecordResponse.builder()
            .sequenceNumber("seq-1")
            .shardId("shardId-000000000000")
            .build()

        every { client.putRecord(capture(request)) } returns CompletableFuture.completedFuture(response)

        val payload = SdkBytes.fromUtf8String("hello")
        val result = template(client).putRecord(
            KinesisPutRecordRequest(
                streamName = "orders",
                partitionKey = "order-1",
                data = payload,
            )
        )

        result.sequenceNumber() shouldBeEqualTo "seq-1"
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

        result.failedRecordCount() shouldBeEqualTo 0
        request.captured.streamName() shouldBeEqualTo "orders"
        request.captured.records().size shouldBeEqualTo 1
        request.captured.records().first().partitionKey() shouldBeEqualTo "order-1"
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

        result.shardIterator() shouldBeEqualTo "iterator-1"
        request.captured.streamName() shouldBeEqualTo "orders"
        request.captured.shardId() shouldBeEqualTo "shardId-000000000000"
        request.captured.shardIteratorType() shouldBeEqualTo ShardIteratorType.AFTER_SEQUENCE_NUMBER
        request.captured.startingSequenceNumber() shouldBeEqualTo "42"
    }

    @Test
    fun `recordFlow maps starting positions to shard iterator requests`() = runTest {
        val timestamp = Instant.parse("2026-06-30T00:00:00Z")
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

        pending.isCancelled shouldBeEqualTo true
    }

    @Test
    fun `future failures propagate without wrapping`() = runTest {
        val client = mockk<KinesisAsyncClient>()
        val failure = IllegalStateException("sdk failed")

        every { client.putRecord(any<PutRecordRequest>()) } returns CompletableFuture.failedFuture(failure)

        val error = assertFailsWith<IllegalStateException> {
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

    private fun template(
        client: KinesisAsyncClient,
        properties: KinesisProperties = KinesisProperties(region = "us-east-1"),
    ): KinesisCoroutinesTemplate =
        KinesisCoroutinesTemplate(client, properties)

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
