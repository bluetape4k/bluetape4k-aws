package io.bluetape4k.aws.spring.sns

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldHaveSize
import io.bluetape4k.codec.Base58
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import software.amazon.awssdk.services.sns.model.PublishBatchResultEntry
import software.amazon.awssdk.services.sns.model.PublishBatchResponse
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

class SnsBatchExecutorTest {

    @Test
    fun `executor chunks matrix into exact ten-entry calls`() = runTest {
        listOf(0, 1, 9, 10, 11, 20, 21, 100).forEach { size ->
            val publisher = RecordingPublisher()
            val result = SnsBatchExecutor(publisher::publish).execute(request(size), SnsBatchExecutionOptions(4))

            publisher.chunks.size shouldBeEqualTo if (size == 0) 0 else (size + 9) / 10
            result.successful.size shouldBeEqualTo size
            result.failed shouldHaveSize 0
        }
    }

    @Test
    fun `executor restores input order and preserves mixed result fields`() = runTest {
        val batchRequest = request(2)
        val publisher = RecordingPublisher { entries ->
            PublishBatchResponse.builder()
                .successful(
                    PublishBatchResultEntry.builder()
                        .id(entries[1].id)
                        .messageId("message-2")
                        .sequenceNumber("sequence-2")
                        .build(),
                )
                .failed(
                    software.amazon.awssdk.services.sns.model.BatchResultErrorEntry.builder()
                        .id(entries[0].id)
                        .code("AccessDenied")
                        .message("safe failure")
                        .senderFault(true)
                        .build(),
                )
                .build()
        }

        val result = SnsBatchExecutor(publisher::publish).execute(batchRequest, SnsBatchExecutionOptions())

        result.successful.single().entryId shouldBeEqualTo batchRequest.entries[1].id
        result.successful.single().messageId shouldBeEqualTo "message-2"
        result.successful.single().sequenceNumber shouldBeEqualTo "sequence-2"
        result.failed.single().entryId shouldBeEqualTo batchRequest.entries[0].id
        result.failed.single().code shouldBeEqualTo "AccessDenied"
        result.failed.single().message shouldBeEqualTo "safe failure"
        result.failed.single().senderFault shouldBeEqualTo true
    }

    @Test
    fun `executor bounds active workers and does not retry transport failures`() = runTest {
        val publisher = RecordingPublisher { entries ->
            if (entries.first().id.startsWith("entry-11-")) {
                throw IllegalStateException("payload-secret")
            }
            successResponseFor(entries)
        }

        val error = assertFailsWith<SnsBatchTransportException> {
            SnsBatchExecutor(publisher::publish).execute(request(100), SnsBatchExecutionOptions(4))
        }

        check(publisher.maxActive <= 4)
        check(publisher.chunks.size <= 10)
        check(publisher.chunks.flatten().distinct().size == publisher.chunks.flatten().size)
        check("payload-secret" !in error.toString())
    }

    @Test
    fun `executor preserves cancellation identity and rejects protocol ids`() = runTest {
        val cancellation = CancellationException("cancelled")
        val canceled = RecordingPublisher { throw cancellation }
        val actual = assertFailsWith<CancellationException> {
            SnsBatchExecutor(canceled::publish).execute(request(1), SnsBatchExecutionOptions())
        }
        check(actual === cancellation)

        val protocol = RecordingPublisher {
            PublishBatchResponse.builder()
                .successful(PublishBatchResultEntry.builder().id("unknown-entry").messageId("message").build())
                .build()
        }
        val protocolError = assertFailsWith<SnsBatchProtocolException> {
            SnsBatchExecutor(protocol::publish).execute(request(1), SnsBatchExecutionOptions())
        }
        protocolError.unknownEntryCount shouldBeEqualTo 1
    }

    @Test
    fun `executor propagates caller cancellation through an active publisher`() = runTest {
        val started = CompletableDeferred<Unit>()
        val stopped = CompletableDeferred<Unit>()
        val publisher = SnsBatchExecutor { _, _ ->
            started.complete(Unit)
            try {
                awaitCancellation()
            } finally {
                stopped.complete(Unit)
            }
        }
        val call = launch {
            assertFailsWith<CancellationException> {
                publisher.execute(request(11), SnsBatchExecutionOptions(maxInFlightBatches = 2))
            }
        }

        started.await()
        val cancellation = CancellationException("caller-cancelled")
        call.cancel(cancellation)
        call.join()

        stopped.await()
        check(call.isCompleted)
    }

    @Test
    fun `executor cancels sibling publisher after transport failure without retry`() = runTest {
        val batchRequest = request(20)
        val firstEntryId = batchRequest.entries.first().id
        val firstStarted = CompletableDeferred<Unit>()
        val firstCancelled = CompletableDeferred<Unit>()
        val calls = CopyOnWriteArrayList<List<String>>()
        val publisher = SnsBatchExecutor { _, entries ->
            calls += entries.map { it.id }
            if (entries.first().id == firstEntryId) {
                firstStarted.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    firstCancelled.complete(Unit)
                }
            } else {
                firstStarted.await()
                throw IllegalStateException("transport-secret")
            }
        }

        val error = assertFailsWith<SnsBatchTransportException> {
            publisher.execute(batchRequest, SnsBatchExecutionOptions(maxInFlightBatches = 2))
        }

        firstCancelled.await()
        error.completedEntryIds shouldHaveSize 0
        check(calls.distinct().size == calls.size)
        check("transport-secret" !in error.toString())
    }

    @Test
    fun `executor rejects non-positive concurrency before launching work`() = runTest {
        assertFailsWith<IllegalArgumentException> { SnsBatchExecutionOptions(0) }
        assertFailsWith<IllegalArgumentException> { SnsBatchExecutionOptions(-1) }
    }

    private fun request(size: Int): SnsPublishBatchRequest =
        SnsPublishBatchRequest(
            topicArn = "arn:aws:sns:us-east-1:000000000000:batch-topic",
            entries = (1..size).map { index ->
                SnsPublishBatchEntry(
                    id = "entry-$index-${Base58.randomString(16)}",
                    message = "message-$index-${Base58.randomString(16)}",
                )
            },
        )

    private class RecordingPublisher(
        private val responseFactory: suspend (List<SnsPublishBatchEntry>) -> PublishBatchResponse =
            ::successResponseFor,
    ) {
        val chunks = CopyOnWriteArrayList<List<String>>()
        var maxActive: Int = 0
            private set
        private val active = AtomicInteger()

        suspend fun publish(
            @Suppress("UNUSED_PARAMETER") topicArn: String,
            entries: List<SnsPublishBatchEntry>,
        ): PublishBatchResponse {
            chunks += entries.map { it.id }
            val current = active.incrementAndGet()
            maxActive = maxOf(maxActive, current)
            return try {
                delay(1)
                responseFactory(entries)
            } finally {
                active.decrementAndGet()
            }
        }
    }
}

private fun successResponseFor(entries: List<SnsPublishBatchEntry>): PublishBatchResponse =
    PublishBatchResponse.builder()
        .successful(
            entries.map { entry ->
                PublishBatchResultEntry.builder()
                    .id(entry.id)
                    .messageId("message-${entry.id}")
                    .build()
            },
        )
        .build()
