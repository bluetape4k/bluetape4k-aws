package io.bluetape4k.aws.ktor.kinesis

import aws.sdk.kotlin.services.kinesis.KinesisClient
import aws.sdk.kotlin.services.kinesis.model.ExpiredIteratorException
import aws.sdk.kotlin.services.kinesis.model.GetRecordsRequest
import aws.sdk.kotlin.services.kinesis.model.GetRecordsResponse
import aws.sdk.kotlin.services.kinesis.model.GetShardIteratorRequest
import aws.sdk.kotlin.services.kinesis.model.GetShardIteratorResponse
import aws.sdk.kotlin.services.kinesis.model.InvalidArgumentException
import aws.sdk.kotlin.services.kinesis.model.ProvisionedThroughputExceededException
import aws.sdk.kotlin.services.kinesis.model.PutRecordRequest
import aws.sdk.kotlin.services.kinesis.model.PutRecordResponse
import aws.sdk.kotlin.services.kinesis.model.PutRecordsRequest
import aws.sdk.kotlin.services.kinesis.model.PutRecordsRequestEntry
import aws.sdk.kotlin.services.kinesis.model.PutRecordsResponse
import aws.sdk.kotlin.services.kinesis.model.Record
import aws.sdk.kotlin.services.kinesis.model.ShardIteratorType
import aws.smithy.kotlin.runtime.time.Instant as SmithyInstant
import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

class KinesisKtorTemplateTest {

    companion object: KLoggingChannel()

    private val client = mockk<KinesisClient>()

    @BeforeEach
    fun beforeEach() {
        clearMocks(client)
    }

    @Test
    fun `putRecord maps stream partition key and bytes`() = runTest {
        val request = slot<PutRecordRequest>()
        val response = PutRecordResponse {
            sequenceNumber = "seq-1"
            shardId = "shardId-000000000000"
        }
        coEvery { client.putRecord(capture(request)) } returns response

        val result = template(client).putRecord(
            KinesisPutRecordRequest(
                streamName = "orders",
                partitionKey = "order-1",
                data = "hello".encodeToByteArray(),
            )
        )

        result shouldBeEqualTo response
        request.captured.streamName shouldBeEqualTo "orders"
        request.captured.partitionKey shouldBeEqualTo "order-1"
        request.captured.data?.decodeToString() shouldBeEqualTo "hello"
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
        val request = slot<PutRecordsRequest>()
        val response = PutRecordsResponse {
            failedRecordCount = 0
            records = emptyList()
        }
        val entries = listOf(
            PutRecordsRequestEntry {
                partitionKey = "order-1"
                data = "payload-1".encodeToByteArray()
            }
        )
        coEvery { client.putRecords(capture(request)) } returns response

        val result = template(client).putRecords("orders", entries)

        result shouldBeEqualTo response
        request.captured.streamName shouldBeEqualTo "orders"
        request.captured.records?.size shouldBeEqualTo 1
        request.captured.records?.first()?.partitionKey shouldBeEqualTo "order-1"
        request.captured.records?.first()?.data?.decodeToString() shouldBeEqualTo "payload-1"
    }

    @Test
    fun `stream and flow options reject invalid limits`() {
        assertFailsWith<IllegalArgumentException> { KinesisKtorStream(shardCount = 0) }
        assertFailsWith<IllegalArgumentException> { KinesisRecordFlowOptions(batchLimit = 0) }
        assertFailsWith<IllegalArgumentException> { KinesisRecordFlowOptions(maxIteratorRetries = -1) }
        assertFailsWith<IllegalArgumentException> { KinesisRecordFlowOptions(maxThrottleRetries = -1) }
        assertFailsWith<IllegalArgumentException> { KinesisRecordFlowOptions(jitterRatio = 1.1) }
    }

    @Test
    fun `getShardIterator maps explicit request and preserves unknown raw type`() = runTest {
        val request = slot<GetShardIteratorRequest>()
        val response = GetShardIteratorResponse { shardIterator = "iterator-1" }
        coEvery { client.getShardIterator(capture(request)) } returns response

        val result = template(client).getShardIterator(
            KinesisShardIteratorRequest(
                streamName = "orders",
                shardId = "shardId-000000000000",
                type = ShardIteratorType.SdkUnknown("FUTURE_ITERATOR_TYPE"),
                startingSequenceNumber = "42",
            )
        )

        result shouldBeEqualTo response
        request.captured.streamName shouldBeEqualTo "orders"
        request.captured.shardId shouldBeEqualTo "shardId-000000000000"
        request.captured.shardIteratorType?.value shouldBeEqualTo "FUTURE_ITERATOR_TYPE"
        request.captured.startingSequenceNumber shouldBeEqualTo "42"
    }

    @Test
    fun `recordFlow maps starting positions to shard iterator requests`() = runTest {
        val timestamp = Instant.parse("2026-07-01T00:00:00Z")
        val cases = listOf(
            FlowCase(KinesisStartingPosition.TrimHorizon, ShardIteratorType.TrimHorizon),
            FlowCase(KinesisStartingPosition.Latest, ShardIteratorType.Latest),
            FlowCase(KinesisStartingPosition.AtSequenceNumber("10"), ShardIteratorType.AtSequenceNumber, "10"),
            FlowCase(
                KinesisStartingPosition.AfterSequenceNumber("11"),
                ShardIteratorType.AfterSequenceNumber,
                "11",
            ),
            FlowCase(
                KinesisStartingPosition.AtTimestamp(timestamp),
                ShardIteratorType.AtTimestamp,
                timestamp = SmithyInstant.fromEpochSeconds(timestamp.epochSecond, timestamp.nano),
            ),
        )

        cases.forEach { case ->
            clearMocks(client)
            val request = slot<GetShardIteratorRequest>()
            coEvery { client.getShardIterator(capture(request)) } returns
                GetShardIteratorResponse { shardIterator = "it" }
            coEvery { client.getRecords(any<GetRecordsRequest>()) } returns GetRecordsResponse {
                records = emptyList()
            }

            template(client)
                .recordFlow(
                    KinesisRecordFlowRequest(
                        streamName = "orders",
                        shardId = "shardId-000000000000",
                        position = case.position,
                        options = flowOptions(),
                    )
                )
                .toList()

            request.captured.shardIteratorType shouldBeEqualTo case.type
            request.captured.startingSequenceNumber shouldBeEqualTo case.sequenceNumber
            request.captured.timestamp shouldBeEqualTo case.timestamp
        }
    }

    @Test
    fun `recordFlow is cold and stops when next shard iterator is absent`() = runTest {
        val record = Record {
            sequenceNumber = "seq-1"
            partitionKey = "order-1"
            data = "payload-1".encodeToByteArray()
        }
        coEvery { client.getShardIterator(any<GetShardIteratorRequest>()) } returns
            GetShardIteratorResponse { shardIterator = "it-1" }
        coEvery { client.getRecords(any<GetRecordsRequest>()) } returns GetRecordsResponse {
            records = listOf(record)
        }

        val flow = template(client)
            .recordFlow(
                KinesisRecordFlowRequest(
                    streamName = "orders",
                    shardId = "shardId-000000000000",
                    options = flowOptions(),
                )
            )

        coVerify(exactly = 0) { client.getShardIterator(any<GetShardIteratorRequest>()) }
        coVerify(exactly = 0) { client.getRecords(any<GetRecordsRequest>()) }

        val records = flow.toList()

        records.size shouldBeEqualTo 1
        records.single().sequenceNumber shouldBeEqualTo "seq-1"
        records.single().partitionKey shouldBeEqualTo "order-1"
        records.single().data?.decodeToString() shouldBeEqualTo "payload-1"
        coVerify(exactly = 1) { client.getShardIterator(any<GetShardIteratorRequest>()) }
        coVerify(exactly = 1) { client.getRecords(any<GetRecordsRequest>()) }
    }

    @Test
    fun `recordFlow can be collected twice and refetches iterator`() = runTest {
        coEvery { client.getShardIterator(any<GetShardIteratorRequest>()) } returns
            GetShardIteratorResponse { shardIterator = "it" }
        coEvery { client.getRecords(any<GetRecordsRequest>()) } returns GetRecordsResponse {
            records = emptyList()
        }

        val flow = template(client)
            .recordFlow(
                KinesisRecordFlowRequest(
                    streamName = "orders",
                    shardId = "shardId-000000000000",
                    options = flowOptions(),
                )
            )

        flow.toList()
        flow.toList()

        coVerify(exactly = 2) { client.getShardIterator(any<GetShardIteratorRequest>()) }
        coVerify(exactly = 2) { client.getRecords(any<GetRecordsRequest>()) }
    }

    @Test
    fun `recordFlow propagates cancellation to pending Kotlin request`() = runTest {
        var requestCancelled = false
        coEvery { client.getShardIterator(any<GetShardIteratorRequest>()) } returns
            GetShardIteratorResponse { shardIterator = "it" }
        coEvery { client.getRecords(any<GetRecordsRequest>()) } coAnswers {
            try {
                awaitCancellation()
            } finally {
                requestCancelled = true
            }
        }

        val job = launch {
            template(client)
                .recordFlow(
                    KinesisRecordFlowRequest(
                        streamName = "orders",
                        shardId = "shardId-000000000000",
                        options = flowOptions(),
                    )
                )
                .toList()
        }

        advanceUntilIdle()
        job.cancelAndJoin()

        requestCancelled.shouldBeTrue()
    }

    @Test
    fun `failed Kinesis request preserves original exception`() = runTest {
        val failure = InvalidArgumentException { message = "boom" }
        coEvery { client.putRecord(any<PutRecordRequest>()) } throws failure

        val error = assertFailsWith<InvalidArgumentException> {
            template(client).putRecord(
                KinesisPutRecordRequest(
                    streamName = "orders",
                    partitionKey = "order-1",
                    data = "payload".encodeToByteArray(),
                )
            )
        }

        error shouldBeEqualTo failure
    }

    @Test
    fun `recordFlow recovers expired iterator after last emitted sequence`() = runTest {
        val request = slot<GetShardIteratorRequest>()
        val record = Record {
            sequenceNumber = "seq-7"
            partitionKey = "order-7"
            data = "payload-7".encodeToByteArray()
        }
        val expired = ExpiredIteratorException { message = "iterator expired" }
        coEvery { client.getShardIterator(capture(request)) } returnsMany listOf(
            GetShardIteratorResponse { shardIterator = "it-1" },
            GetShardIteratorResponse { shardIterator = "it-3" },
        )
        var recordRequestCount = 0
        coEvery { client.getRecords(any<GetRecordsRequest>()) } coAnswers {
            when (recordRequestCount++) {
                0 -> GetRecordsResponse { records = listOf(record); nextShardIterator = "it-2" }
                1 -> throw expired
                else -> GetRecordsResponse { records = emptyList() }
            }
        }

        val records = template(client)
            .recordFlow(
                KinesisRecordFlowRequest(
                    streamName = "orders",
                    shardId = "shardId-000000000000",
                    options = flowOptions(),
                )
            )
            .toList()

        records.map { it.sequenceNumber } shouldBeEqualTo listOf("seq-7")
        request.captured.shardIteratorType shouldBeEqualTo ShardIteratorType.AfterSequenceNumber
        request.captured.startingSequenceNumber shouldBeEqualTo "seq-7"
        coVerify(exactly = 2) { client.getShardIterator(any<GetShardIteratorRequest>()) }
    }

    @Test
    fun `recordFlow fails initial latest iterator expiry without silently skipping data`() = runTest {
        val expired = ExpiredIteratorException { message = "iterator expired" }
        coEvery { client.getShardIterator(any<GetShardIteratorRequest>()) } returns
            GetShardIteratorResponse { shardIterator = "it-1" }
        coEvery { client.getRecords(any<GetRecordsRequest>()) } throws expired

        val error = assertFailsWith<ExpiredIteratorException> {
            template(client)
                .recordFlow(
                    KinesisRecordFlowRequest(
                        streamName = "orders",
                        shardId = "shardId-000000000000",
                        position = KinesisStartingPosition.Latest,
                        options = flowOptions(),
                    )
                )
                .toList()
        }

        error shouldBeEqualTo expired
        coVerify(exactly = 1) { client.getShardIterator(any<GetShardIteratorRequest>()) }
    }

    @Test
    fun `recordFlow resets throttle budget after a successful poll and caps jitter`() = runTest {
        val requestTimes = mutableListOf<Long>()
        val throttled = ProvisionedThroughputExceededException { message = "throttled" }
        coEvery { client.getShardIterator(any<GetShardIteratorRequest>()) } returns
            GetShardIteratorResponse { shardIterator = "it-1" }
        coEvery { client.getRecords(any<GetRecordsRequest>()) } coAnswers {
            requestTimes += testScheduler.currentTime
            when (requestTimes.size) {
                1, 3 -> throw throttled
                2 -> GetRecordsResponse { records = emptyList(); nextShardIterator = "it-2" }
                else -> GetRecordsResponse { records = emptyList() }
            }
        }

        template(client)
            .recordFlow(
                KinesisRecordFlowRequest(
                    streamName = "orders",
                    shardId = "shardId-000000000000",
                    options = KinesisRecordFlowOptions(
                        pollInterval = Duration.ZERO,
                        emptyBackoff = Duration.ZERO,
                        maxThrottleRetries = 1,
                        initialThrottleBackoff = Duration.ofMillis(10),
                        maxThrottleBackoff = Duration.ofMillis(100),
                        jitterRatio = 0.5,
                    ),
                )
            )
            .toList()

        requestTimes.size shouldBeEqualTo 4
        (requestTimes[1] - requestTimes[0] in 5L..10L).shouldBeTrue()
        (requestTimes[3] - requestTimes[2] in 5L..10L).shouldBeTrue()
    }

    @Test
    fun `recordFlow caps consecutive throttle retries and jitter backoff`() = runTest {
        val requestTimes = mutableListOf<Long>()
        val throttled = ProvisionedThroughputExceededException { message = "throttled" }
        coEvery { client.getShardIterator(any<GetShardIteratorRequest>()) } returns
            GetShardIteratorResponse { shardIterator = "it-1" }
        coEvery { client.getRecords(any<GetRecordsRequest>()) } coAnswers {
            requestTimes += testScheduler.currentTime
            throw throttled
        }

        val error = assertFailsWith<ProvisionedThroughputExceededException> {
            template(client)
                .recordFlow(
                    KinesisRecordFlowRequest(
                        streamName = "orders",
                        shardId = "shardId-000000000000",
                        options = KinesisRecordFlowOptions(
                            pollInterval = Duration.ZERO,
                            emptyBackoff = Duration.ZERO,
                            maxThrottleRetries = 2,
                            initialThrottleBackoff = Duration.ofMillis(10),
                            maxThrottleBackoff = Duration.ofMillis(15),
                            jitterRatio = 0.5,
                        ),
                    )
                )
                .toList()
        }

        error shouldBeEqualTo throttled
        requestTimes.size shouldBeEqualTo 3
        (requestTimes[1] - requestTimes[0] in 5L..10L).shouldBeTrue()
        (requestTimes[2] - requestTimes[1] in 7L..15L).shouldBeTrue()
    }

    @Test
    fun `recordFlow uses empty backoff and poll interval for the matching responses`() = runTest {
        val emptyResponseTimes = mutableListOf<Long>()
        coEvery { client.getShardIterator(any<GetShardIteratorRequest>()) } returns
            GetShardIteratorResponse { shardIterator = "empty-it" }
        coEvery { client.getRecords(any<GetRecordsRequest>()) } coAnswers {
            emptyResponseTimes += testScheduler.currentTime
            if (emptyResponseTimes.size == 1) {
                GetRecordsResponse { records = emptyList(); nextShardIterator = "empty-it-2" }
            } else {
                GetRecordsResponse { records = emptyList() }
            }
        }

        template(client)
            .recordFlow(
                KinesisRecordFlowRequest(
                    streamName = "orders",
                    shardId = "shardId-000000000000",
                    options = KinesisRecordFlowOptions(
                        pollInterval = Duration.ofMillis(29),
                        emptyBackoff = Duration.ofMillis(13),
                    ),
                )
            )
            .toList()

        emptyResponseTimes shouldBeEqualTo listOf(0L, 13L)

        clearMocks(client)
        val recordResponseStartTime = testScheduler.currentTime
        val recordResponseTimes = mutableListOf<Long>()
        val record = Record {
            sequenceNumber = "seq-1"
            partitionKey = "order-1"
            data = byteArrayOf()
        }
        coEvery { client.getShardIterator(any<GetShardIteratorRequest>()) } returns
            GetShardIteratorResponse { shardIterator = "record-it" }
        coEvery { client.getRecords(any<GetRecordsRequest>()) } coAnswers {
            recordResponseTimes += testScheduler.currentTime - recordResponseStartTime
            if (recordResponseTimes.size == 1) {
                GetRecordsResponse { records = listOf(record); nextShardIterator = "record-it-2" }
            } else {
                GetRecordsResponse { records = emptyList() }
            }
        }

        template(client)
            .recordFlow(
                KinesisRecordFlowRequest(
                    streamName = "orders",
                    shardId = "shardId-000000000000",
                    options = KinesisRecordFlowOptions(
                        pollInterval = Duration.ofMillis(29),
                        emptyBackoff = Duration.ofMillis(13),
                    ),
                )
            )
            .toList()

        recordResponseTimes shouldBeEqualTo listOf(0L, 29L)
    }

    private fun template(client: KinesisClient): KinesisKtorTemplate = KinesisKtorTemplate(client)

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
        val timestamp: SmithyInstant? = null,
    )
}
