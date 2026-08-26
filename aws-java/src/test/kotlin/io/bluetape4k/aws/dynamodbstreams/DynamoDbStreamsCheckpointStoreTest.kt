package io.bluetape4k.aws.dynamodbstreams

import io.bluetape4k.assertions.shouldBeEqualTo
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class DynamoDbStreamsCheckpointStoreTest {

    @Test
    fun `in memory store isolates stream and shard keys`() = runTest {
        val store = InMemoryDynamoDbStreamsCheckpointStore()

        store.load("stream-a", "shard-1") shouldBeEqualTo null
        store.save("stream-a", "shard-1", "seq-1")
        store.save("stream-a", "shard-2", "seq-2")
        store.save("stream-b", "shard-1", "seq-3")

        store.load("stream-a", "shard-1") shouldBeEqualTo "seq-1"
        store.load("stream-a", "shard-2") shouldBeEqualTo "seq-2"
        store.load("stream-b", "shard-1") shouldBeEqualTo "seq-3"
    }

    @Test
    fun `noop store never exposes a checkpoint`() = runTest {
        NoopDynamoDbStreamsCheckpointStore.load("stream", "shard") shouldBeEqualTo null
        NoopDynamoDbStreamsCheckpointStore.save("stream", "shard", "seq")
        NoopDynamoDbStreamsCheckpointStore.load("stream", "shard") shouldBeEqualTo null
    }
}
