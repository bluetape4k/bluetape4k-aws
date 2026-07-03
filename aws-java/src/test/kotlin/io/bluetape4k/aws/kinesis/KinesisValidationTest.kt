package io.bluetape4k.aws.kinesis

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.aws.kinesis.model.createStreamRequestOf
import io.bluetape4k.aws.kinesis.model.getRecordsRequestOf
import io.bluetape4k.aws.kinesis.model.putRecordsRequestOf
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import software.amazon.awssdk.core.SdkBytes
import software.amazon.awssdk.services.kinesis.KinesisAsyncClient
import software.amazon.awssdk.services.kinesis.KinesisClient
import software.amazon.awssdk.services.kinesis.model.PutRecordsRequestEntry

class KinesisValidationTest {

    private val client = mockk<KinesisClient>()
    private val asyncClient = mockk<KinesisAsyncClient>()

    @Test
    fun `createStream validates shard count before AWS call`() = runTest {
        assertFailsWith<IllegalArgumentException> {
            client.createStream("stream", shardCount = 0)
        }
        assertFailsWith<IllegalArgumentException> {
            asyncClient.createStreamAsync("stream", shardCount = 0)
        }
        assertFailsWith<IllegalArgumentException> {
            asyncClient.createStream("stream", shardCount = 0)
        }
        assertFailsWith<IllegalArgumentException> {
            createStreamRequestOf("stream", shardCount = 0)
        }
    }

    @Test
    fun `putRecords validates entries size before AWS call`() = runTest {
        assertFailsWith<IllegalArgumentException> {
            client.putRecords("stream", emptyList())
        }
        assertFailsWith<IllegalArgumentException> {
            asyncClient.putRecordsAsync("stream", tooManyEntries())
        }
        assertFailsWith<IllegalArgumentException> {
            asyncClient.putRecords("stream", tooManyEntries())
        }
        assertFailsWith<IllegalArgumentException> {
            putRecordsRequestOf("stream", tooManyEntries())
        }
    }

    @Test
    fun `getRecords validates limit before AWS call`() = runTest {
        assertFailsWith<IllegalArgumentException> {
            client.getRecords("iterator", limit = 0)
        }
        assertFailsWith<IllegalArgumentException> {
            asyncClient.getRecordsAsync("iterator", limit = 10_001)
        }
        assertFailsWith<IllegalArgumentException> {
            asyncClient.getRecords("iterator", limit = 10_001)
        }
        assertFailsWith<IllegalArgumentException> {
            getRecordsRequestOf("iterator", limit = 0)
        }
    }

    private fun tooManyEntries(): List<PutRecordsRequestEntry> =
        (1..501).map { index ->
            PutRecordsRequestEntry.builder()
                .partitionKey("partition-$index")
                .data(SdkBytes.fromUtf8String("message-$index"))
                .build()
        }
}
