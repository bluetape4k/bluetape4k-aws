package io.bluetape4k.aws.spring.sns

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeSameInstanceAs
import io.bluetape4k.assertions.shouldHaveSize
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import software.amazon.awssdk.services.sns.SnsAsyncClient
import software.amazon.awssdk.services.sns.model.PublishBatchResponse
import software.amazon.awssdk.services.sns.model.PublishBatchResultEntry
import java.util.concurrent.CompletableFuture

class SnsBatchExecutionStrategyTest {

    @Test
    fun `template keeps additive constructor descriptors`() {
        val signatures = SnsCoroutinesTemplate::class.java.constructors
            .map { it.parameterTypes.toList() }
            .toSet()

        signatures shouldBeEqualTo setOf(
            listOf(SnsAsyncClient::class.java, SnsProperties::class.java),
            listOf(
                SnsAsyncClient::class.java,
                SnsProperties::class.java,
                SnsBatchExecutionStrategy::class.java,
            ),
        )
    }

    @Test
    fun `strategy exposes typed request options and port`() = runTest {
        val request = request(3)
        val calls = mutableListOf<List<String>>()
        val strategy = SnsBatchExecutionStrategy { batch, options, port ->
            options.maxInFlightBatches shouldBeEqualTo 2
            batch shouldBeEqualTo request
            port.publishChunk(batch.entries).also {
                calls += batch.entries.map { it.id }
            }
        }

        val result = strategy.execute(
            request,
            SnsBatchExecutionOptions(maxInFlightBatches = 2),
            object : SnsBatchExecutionPort {
                override suspend fun publishChunk(entries: List<SnsPublishBatchEntry>): SnsPublishBatchResult =
                    SnsPublishBatchResult(
                        successful = entries.map { SnsPublishBatchSuccess(it.id, "message-${it.id}") },
                        failed = emptyList(),
                    )
            },
        )

        calls shouldHaveSize 1
        result.successful shouldHaveSize 3
    }

    @Test
    fun `contract exception is cause free and keeps only enum`() {
        val error = SnsBatchExecutionContractException(SnsBatchExecutionContractError.INVALID_CHUNK)

        error.error shouldBeEqualTo SnsBatchExecutionContractError.INVALID_CHUNK
        error.cause shouldBeEqualTo null
        error.toString() shouldBeEqualTo
            "io.bluetape4k.aws.spring.sns.SnsBatchExecutionContractException: " +
            "SNS batch execution contract failed: error=INVALID_CHUNK"
    }

    @Test
    fun `cancellation is not normalized by the strategy contract`() = runTest {
        val cancellation = CancellationException("caller-cancelled")
        val strategy = SnsBatchExecutionStrategy { _, _, _ -> throw cancellation }

        val actual = assertFailsWith<CancellationException> {
            strategy.execute(
                request(1),
                SnsBatchExecutionOptions(),
                object : SnsBatchExecutionPort {
                    override suspend fun publishChunk(entries: List<SnsPublishBatchEntry>): SnsPublishBatchResult =
                        error(cancellation)
                },
            )
        }

        actual shouldBeSameInstanceAs cancellation
    }

    @Test
    fun `template exposes only guarded port and closes it after return`() = runTest {
        val client = mockk<SnsAsyncClient>()
        every { client.publishBatch(any<software.amazon.awssdk.services.sns.model.PublishBatchRequest>()) } answers {
            val request = firstArg<software.amazon.awssdk.services.sns.model.PublishBatchRequest>()
            CompletableFuture.completedFuture(
                PublishBatchResponse.builder()
                    .successful(
                        request.publishBatchRequestEntries().map { entry ->
                            PublishBatchResultEntry.builder()
                                .id(entry.id())
                                .messageId("message-${entry.id()}")
                                .build()
                        },
                    )
                    .build(),
            )
        }
        lateinit var retainedPort: SnsBatchExecutionPort
        val template = SnsCoroutinesTemplate(
            client,
            SnsProperties(region = "us-east-1"),
            SnsBatchExecutionStrategy { request, options, port ->
                retainedPort = port
                DefaultSnsBatchExecutionStrategy.execute(request, options, port)
            },
        )

        template.publishBatch(request(1), SnsBatchExecutionOptions(maxInFlightBatches = 1))
        val closed = assertFailsWith<SnsBatchExecutionContractException> {
            retainedPort.publishChunk(request(1).entries)
        }

        closed.error shouldBeEqualTo SnsBatchExecutionContractError.PORT_CLOSED
        verify(exactly = 1) {
            client.publishBatch(any<software.amazon.awssdk.services.sns.model.PublishBatchRequest>())
        }
    }

    @Test
    fun `template preserves cancellation identity after guarded drain`() = runTest {
        val client = mockk<SnsAsyncClient>(relaxed = true)
        lateinit var retainedPort: SnsBatchExecutionPort
        val cancellation = CancellationException("caller-cancelled")
        val template = SnsCoroutinesTemplate(
            client,
            SnsProperties(region = "us-east-1"),
            SnsBatchExecutionStrategy { _, _, port ->
                retainedPort = port
                throw cancellation
            },
        )

        val actual = assertFailsWith<CancellationException> {
            template.publishBatch(request(1))
        }

        actual shouldBeSameInstanceAs cancellation
        val closed = assertFailsWith<SnsBatchExecutionContractException> {
            retainedPort.publishChunk(request(1).entries)
        }
        closed.error shouldBeEqualTo SnsBatchExecutionContractError.PORT_CLOSED
        verify(exactly = 0) {
            client.publishBatch(any<software.amazon.awssdk.services.sns.model.PublishBatchRequest>())
        }
    }

    private fun request(size: Int): SnsPublishBatchRequest =
        SnsPublishBatchRequest(
            topicArn = "arn:aws:sns:us-east-1:000000000000:orders",
            entries = (1..size).map { index ->
                SnsPublishBatchEntry("entry-$index", "message-$index")
            },
        )

    private suspend fun error(cause: Throwable): SnsPublishBatchResult = throw cause
}
