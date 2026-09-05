package io.bluetape4k.aws.kotlin.kinesis

import aws.sdk.kotlin.services.kinesis.KinesisClient
import aws.sdk.kotlin.services.kinesis.model.DryRunOperationException
import aws.sdk.kotlin.services.kinesis.model.GetRecordsRequest
import aws.sdk.kotlin.services.kinesis.model.GetRecordsResponse
import aws.sdk.kotlin.services.kinesis.model.GetShardIteratorRequest
import aws.sdk.kotlin.services.kinesis.model.GetShardIteratorResponse
import aws.sdk.kotlin.services.kinesis.model.PutRecordRequest
import aws.sdk.kotlin.services.kinesis.model.PutRecordResponse
import aws.sdk.kotlin.services.kinesis.model.PutRecordsRequest
import aws.sdk.kotlin.services.kinesis.model.PutRecordsRequestEntry
import aws.sdk.kotlin.services.kinesis.model.PutRecordsResponse
import aws.sdk.kotlin.services.kinesis.model.ResourceNotFoundException
import aws.sdk.kotlin.services.kinesis.model.ShardIteratorType
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeSameInstanceAs
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import org.junit.jupiter.api.Test
import java.util.concurrent.CancellationException

class KinesisClientExtensionsMockTest {

    @Test
    fun `putRecord는 dryRun과 builder 우선순위 및 data identity를 보존한다`() = runSuspendIO {
        val client = mockk<KinesisClient>()
        val requests = mutableListOf<PutRecordRequest>()
        val data = "payload".toByteArray()
        coEvery { client.putRecord(capture(requests)) } returns PutRecordResponse {
            sequenceNumber = "1"
            shardId = "shard"
        }

        client.putRecord("stream", "partition", data)
        client.putRecord("stream", "partition", data, dryRun = true)
        client.putRecord("stream", "partition", data, dryRun = true) { dryRun = false }
        client.putRecord("stream", "partition", data, dryRun = true) { dryRun = null }

        requests.map { it.dryRun } shouldBeEqualTo listOf(false, true, false, null)
        requests.forEach { it.data shouldBeSameInstanceAs data }
        coVerify(exactly = 4) { client.putRecord(any<PutRecordRequest>()) }
    }

    @Test
    fun `putRecords는 top-level dryRun과 records identity를 보존한다`() = runSuspendIO {
        val client = mockk<KinesisClient>()
        val requests = mutableListOf<PutRecordsRequest>()
        val entries = listOf(
            PutRecordsRequestEntry {
                partitionKey = "partition"
                data = "payload".toByteArray()
            },
        )
        coEvery { client.putRecords(capture(requests)) } returns PutRecordsResponse {
            failedRecordCount = 0
            records = emptyList()
        }

        client.putRecords("stream", entries)
        client.putRecords("stream", entries, dryRun = true)
        client.putRecords("stream", entries, dryRun = true) { dryRun = false }
        client.putRecords("stream", entries, dryRun = true) { dryRun = null }

        requests.map { it.dryRun } shouldBeEqualTo listOf(false, true, false, null)
        requests.forEach { it.records shouldBeSameInstanceAs entries }
        coVerify(exactly = 4) { client.putRecords(any<PutRecordsRequest>()) }
    }

    @Test
    fun `getShardIterator는 dryRun과 builder 우선순위를 보존한다`() = runSuspendIO {
        val client = mockk<KinesisClient>()
        val requests = mutableListOf<GetShardIteratorRequest>()
        coEvery { client.getShardIterator(capture(requests)) } returns GetShardIteratorResponse {}

        client.getShardIterator("stream", "shard")
        client.getShardIterator("stream", "shard", dryRun = true)
        client.getShardIterator("stream", "shard", dryRun = true) { dryRun = false }
        client.getShardIterator("stream", "shard", dryRun = true) { dryRun = null }

        requests.map { it.dryRun } shouldBeEqualTo listOf(false, true, false, null)
        requests.map { it.shardIteratorType }.forEach { it shouldBeEqualTo ShardIteratorType.TrimHorizon }
        coVerify(exactly = 4) { client.getShardIterator(any<GetShardIteratorRequest>()) }
    }

    @Test
    fun `getRecords는 dryRun과 builder 우선순위를 보존한다`() = runSuspendIO {
        val client = mockk<KinesisClient>()
        val requests = mutableListOf<GetRecordsRequest>()
        coEvery { client.getRecords(capture(requests)) } returns GetRecordsResponse {
            records = emptyList()
        }

        client.getRecords("iterator")
        client.getRecords("iterator", dryRun = true)
        client.getRecords("iterator", dryRun = true) { dryRun = false }
        client.getRecords("iterator", dryRun = true) { dryRun = null }

        requests.map { it.dryRun } shouldBeEqualTo listOf(false, true, false, null)
        requests.map { it.limit }.forEach { it shouldBeEqualTo 100 }
        coVerify(exactly = 4) { client.getRecords(any<GetRecordsRequest>()) }
    }

    @Test
    fun `putRecord는 SDK 예외와 cancellation을 같은 instance로 전파한다`() = runSuspendIO {
        failures().forEach { expected ->
            val client = mockk<KinesisClient>()
            coEvery { client.putRecord(any<PutRecordRequest>()) } throws expected

            captureFailure { client.putRecord("stream", "partition", byteArrayOf(1), dryRun = true) }
                .shouldBeSameInstanceAs(expected)
            coVerify(exactly = 1) { client.putRecord(any<PutRecordRequest>()) }
        }
    }

    @Test
    fun `putRecords는 SDK 예외와 cancellation을 같은 instance로 전파한다`() = runSuspendIO {
        failures().forEach { expected ->
            val client = mockk<KinesisClient>()
            coEvery { client.putRecords(any<PutRecordsRequest>()) } throws expected

            captureFailure { client.putRecords("stream", emptyList(), dryRun = true) }
                .shouldBeSameInstanceAs(expected)
            coVerify(exactly = 1) { client.putRecords(any<PutRecordsRequest>()) }
        }
    }

    @Test
    fun `getShardIterator는 SDK 예외와 cancellation을 같은 instance로 전파한다`() = runSuspendIO {
        failures().forEach { expected ->
            val client = mockk<KinesisClient>()
            coEvery { client.getShardIterator(any<GetShardIteratorRequest>()) } throws expected

            captureFailure { client.getShardIterator("stream", "shard", dryRun = true) }
                .shouldBeSameInstanceAs(expected)
            coVerify(exactly = 1) { client.getShardIterator(any<GetShardIteratorRequest>()) }
        }
    }

    @Test
    fun `getRecords는 SDK 예외와 cancellation을 같은 instance로 전파한다`() = runSuspendIO {
        failures().forEach { expected ->
            val client = mockk<KinesisClient>()
            coEvery { client.getRecords(any<GetRecordsRequest>()) } throws expected

            captureFailure { client.getRecords("iterator", dryRun = true) }
                .shouldBeSameInstanceAs(expected)
            coVerify(exactly = 1) { client.getRecords(any<GetRecordsRequest>()) }
        }
    }

    private fun failures(): List<Throwable> = listOf(
        DryRunOperationException { message = "dry run accepted" },
        ResourceNotFoundException { message = "missing stream" },
        CancellationException("cancelled"),
    )

    private suspend fun captureFailure(block: suspend () -> Unit): Throwable {
        var failure: Throwable? = null
        try {
            block()
        } catch (caught: Throwable) {
            failure = caught
        }
        return requireNotNull(failure) { "operation must fail" }
    }
}
