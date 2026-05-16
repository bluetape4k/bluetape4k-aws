package io.bluetape4k.aws.kotlin.kinesis

import aws.sdk.kotlin.services.kinesis.KinesisClient
import aws.sdk.kotlin.services.kinesis.model.ExpiredIteratorException
import aws.sdk.kotlin.services.kinesis.model.GetRecordsRequest
import aws.sdk.kotlin.services.kinesis.model.GetRecordsResponse
import aws.sdk.kotlin.services.kinesis.model.GetShardIteratorRequest
import aws.sdk.kotlin.services.kinesis.model.GetShardIteratorResponse
import aws.sdk.kotlin.services.kinesis.model.KinesisException
import aws.sdk.kotlin.services.kinesis.model.Record
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.logging.KLogging
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Unit tests for [recordFlow] using MockK and virtual coroutine time.
 *
 * [runTest] auto-advances virtual time so [delay] calls inside the flow complete instantly
 * without real wall-clock waiting.
 */
class KinesisRecordFlowUnitTest {

    companion object : KLogging()

    private val STREAM = "test-stream"
    private val SHARD_ID = "shardId-000000000000"

    private val client = mockk<KinesisClient>(relaxed = true)

    @BeforeEach
    fun setup() {
        clearMocks(client)
    }

    private fun makeRecord(seq: String, data: String = "data"): Record = mockk(relaxed = true) {
        every { sequenceNumber } returns seq
        every { partitionKey } returns "pk"
        every { this@mockk.data } returns data.encodeToByteArray()
    }

    private fun shardIteratorResponse(iterator: String) =
        GetShardIteratorResponse { shardIterator = iterator }

    private fun recordsResponse(records: List<Record>, nextIterator: String? = "next-iterator") =
        GetRecordsResponse {
            this.records = records
            this.nextShardIterator = nextIterator
        }

    // ─── Normal emission ──────────────────────────────────────────────────────

    @Test
    fun `emits all records from a single getRecords batch`() = runTest {
        val records = listOf(makeRecord("seq-1"), makeRecord("seq-2"), makeRecord("seq-3"))

        coEvery { client.getShardIterator(any<GetShardIteratorRequest>()) } returns
                shardIteratorResponse("iter-1")
        coEvery { client.getRecords(any<GetRecordsRequest>()) } returns
                recordsResponse(records, nextIterator = "iter-2")

        val result = client.recordFlow(STREAM, SHARD_ID).take(3).toList()

        result.size shouldBeEqualTo 3
        result[0].sequenceNumber shouldBeEqualTo "seq-1"
        result[2].sequenceNumber shouldBeEqualTo "seq-3"
    }

    @Test
    fun `flow completes when nextShardIterator is null (shard closed)`() = runTest {
        coEvery { client.getShardIterator(any<GetShardIteratorRequest>()) } returns
                shardIteratorResponse("iter-1")
        coEvery { client.getRecords(any<GetRecordsRequest>()) } returns
                recordsResponse(listOf(makeRecord("seq-1")), nextIterator = null)

        val result = client.recordFlow(STREAM, SHARD_ID).toList()

        result.size shouldBeEqualTo 1
    }

    // ─── ExpiredIteratorException recovery ───────────────────────────────────

    @Test
    fun `recovers from ExpiredIteratorException by re-fetching iterator`() = runTest {
        val record1 = makeRecord("seq-100")
        val record2 = makeRecord("seq-101")

        // Initial fetch + one recovery fetch
        coEvery { client.getShardIterator(any<GetShardIteratorRequest>()) } returnsMany listOf(
            shardIteratorResponse("iter-1"),
            shardIteratorResponse("iter-1-recovered"),
        )

        // getRecords: first returns records, second expires, third succeeds
        var getRecordsCallCount = 0
        coEvery { client.getRecords(any<GetRecordsRequest>()) } answers {
            when (getRecordsCallCount++) {
                0 -> recordsResponse(listOf(record1), "iter-expired")
                1 -> throw ExpiredIteratorException { message = "iterator expired" }
                else -> recordsResponse(listOf(record2), null)
            }
        }

        val result = client.recordFlow(STREAM, SHARD_ID).toList()

        result.size shouldBeEqualTo 2
        result[0].sequenceNumber shouldBeEqualTo "seq-100"
        result[1].sequenceNumber shouldBeEqualTo "seq-101"
        coVerify(exactly = 2) { client.getShardIterator(any()) }
    }

    @Test
    fun `throws immediately on ExpiredIteratorException with Latest and no checkpoint`() = runTest {
        coEvery { client.getShardIterator(any<GetShardIteratorRequest>()) } returns
                shardIteratorResponse("iter-latest")
        coEvery { client.getRecords(any<GetRecordsRequest>()) } throws
                ExpiredIteratorException { message = "expired — latest, no checkpoint" }

        assertFailsWith<ExpiredIteratorException> {
            client.recordFlow(
                streamName = STREAM,
                shardId = SHARD_ID,
                position = KinesisStartingPosition.Latest,
            ).toList()
        }
        // No recovery — only one getShardIterator call
        coVerify(exactly = 1) { client.getShardIterator(any()) }
    }

    @Test
    fun `throws after maxIteratorRetries exhausted`() = runTest {
        val maxRetries = 2
        val opts = KinesisRecordFlowOptions(maxIteratorRetries = maxRetries)

        coEvery { client.getShardIterator(any<GetShardIteratorRequest>()) } returns
                shardIteratorResponse("iter-1")

        // First call returns a record (so checkpoint is set, avoiding Latest fail-fast);
        // subsequent calls all expire.
        var getRecordsCount = 0
        coEvery { client.getRecords(any<GetRecordsRequest>()) } answers {
            if (getRecordsCount++ == 0) {
                recordsResponse(listOf(makeRecord("seq-1")), "iter-2")
            } else {
                throw ExpiredIteratorException { message = "expired" }
            }
        }

        assertFailsWith<ExpiredIteratorException> {
            client.recordFlow(STREAM, SHARD_ID, options = opts).toList()
        }
    }

    // ─── Throttle / retryable KinesisException ────────────────────────────────

    @Test
    fun `retries on retryable KinesisException with backoff`() = runTest {
        val retryable = mockk<KinesisException>(relaxed = true) {
            every { sdkErrorMetadata } returns mockk { every { isRetryable } returns true }
            every { message } returns "ProvisionedThroughputExceededException"
        }
        val record = makeRecord("seq-200")

        coEvery { client.getShardIterator(any<GetShardIteratorRequest>()) } returns
                shardIteratorResponse("iter-1")

        var getRecordsCount = 0
        coEvery { client.getRecords(any<GetRecordsRequest>()) } answers {
            if (getRecordsCount++ == 0) {
                throw retryable
            } else {
                recordsResponse(listOf(record), null)
            }
        }

        val result = client.recordFlow(STREAM, SHARD_ID).toList()

        result.size shouldBeEqualTo 1
        result[0].sequenceNumber shouldBeEqualTo "seq-200"
    }

    @Test
    fun `throws immediately on non-retryable KinesisException`() = runTest {
        val nonRetryable = mockk<KinesisException>(relaxed = true) {
            every { sdkErrorMetadata } returns mockk { every { isRetryable } returns false }
        }

        coEvery { client.getShardIterator(any<GetShardIteratorRequest>()) } returns
                shardIteratorResponse("iter-1")
        coEvery { client.getRecords(any<GetRecordsRequest>()) } throws nonRetryable

        assertFailsWith<KinesisException> {
            client.recordFlow(STREAM, SHARD_ID).toList()
        }
        coVerify(exactly = 1) { client.getRecords(any()) }
    }

    @Test
    fun `throws after maxThrottleRetries exhausted`() = runTest {
        val maxRetries = 2
        val opts = KinesisRecordFlowOptions(maxThrottleRetries = maxRetries)
        val retryable = mockk<KinesisException>(relaxed = true) {
            every { sdkErrorMetadata } returns mockk { every { isRetryable } returns true }
            every { message } returns "throttled"
        }

        coEvery { client.getShardIterator(any<GetShardIteratorRequest>()) } returns
                shardIteratorResponse("iter-1")
        coEvery { client.getRecords(any<GetRecordsRequest>()) } throws retryable

        assertFailsWith<KinesisException> {
            client.recordFlow(STREAM, SHARD_ID, options = opts).toList()
        }
        // 1 initial + maxRetries retries = maxRetries + 1 total calls
        coVerify(exactly = maxRetries + 1) { client.getRecords(any()) }
    }

    // ─── jitteredBackoff unit tests ───────────────────────────────────────────

    @Test
    fun `jitteredBackoff returns value in valid range for first attempt`() {
        val opts = KinesisRecordFlowOptions(
            initialThrottleBackoff = 100.milliseconds,
            maxThrottleBackoff = 30.seconds,
        )
        val backoff = jitteredBackoff(1, opts)
        (backoff >= 0.milliseconds).shouldBeTrue()
        (backoff <= 100.milliseconds).shouldBeTrue()
    }

    @Test
    fun `jitteredBackoff is capped at maxThrottleBackoff`() {
        val maxBackoff = 5.seconds
        val opts = KinesisRecordFlowOptions(
            initialThrottleBackoff = 500.milliseconds,
            maxThrottleBackoff = maxBackoff,
        )
        repeat(20) { attempt ->
            val backoff = jitteredBackoff(attempt + 1, opts)
            (backoff <= maxBackoff).shouldBeTrue()
        }
    }

    @Test
    fun `jitteredBackoff is non-negative at high attempt counts`() {
        val opts = KinesisRecordFlowOptions()
        repeat(50) { attempt ->
            val backoff = jitteredBackoff(attempt + 1, opts)
            (backoff >= 0.milliseconds).shouldBeTrue()
        }
    }

    @Test
    fun `jitteredBackoff grows with attempt count on average`() {
        val opts = KinesisRecordFlowOptions(
            initialThrottleBackoff = 100.milliseconds,
            maxThrottleBackoff = 10.seconds,
        )
        val avgAttempt1 = (1..100).map { jitteredBackoff(1, opts).inWholeMilliseconds }.average()
        val avgAttempt3 = (1..100).map { jitteredBackoff(3, opts).inWholeMilliseconds }.average()
        (avgAttempt3 > avgAttempt1).shouldBeTrue()
    }

    @Test
    fun `jitteredBackoff does not overflow at high attempt counts`() {
        // Long shl overflow guard: baseMs shl shift must never wrap to negative.
        // With attempt=31 (shift=30) and default options, 500 shl 30 = 537_395_200 > maxMs=30_000,
        // so the guard either caps via overflow check or via coerceAtMost — result must be in [0, maxMs].
        val opts = KinesisRecordFlowOptions()
        repeat(60) { attempt ->
            val backoff = jitteredBackoff(attempt + 1, opts)
            (backoff >= 0.milliseconds).shouldBeTrue()
            (backoff <= opts.maxThrottleBackoff).shouldBeTrue()
        }
    }
}
