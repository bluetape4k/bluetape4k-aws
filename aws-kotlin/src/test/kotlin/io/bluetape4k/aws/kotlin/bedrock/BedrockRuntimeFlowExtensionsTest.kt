package io.bluetape4k.aws.kotlin.bedrock

import aws.sdk.kotlin.services.bedrockruntime.BedrockRuntimeClient
import aws.sdk.kotlin.services.bedrockruntime.model.ContentBlockDelta
import aws.sdk.kotlin.services.bedrockruntime.model.ContentBlockDeltaEvent
import aws.sdk.kotlin.services.bedrockruntime.model.ConverseStreamRequest
import aws.sdk.kotlin.services.bedrockruntime.model.ConverseStreamOutput
import aws.sdk.kotlin.services.bedrockruntime.model.ConverseStreamResponse
import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeSameInstanceAs
import io.bluetape4k.aws.kotlin.bedrock.model.userMessageOf
import io.bluetape4k.coroutines.flow.extensions.takeUntil
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class BedrockRuntimeFlowExtensionsTest {

    private val client = mockk<BedrockRuntimeClient>()

    @BeforeEach
    fun setup() {
        clearMocks(client)
    }

    @Test
    fun `Flow is cold and invokes native stream once per collection`() = runTest {
        val response = ConverseStreamResponse { stream = flowOf(textDelta("a")) }
        coEvery {
            client.converseStream<Unit>(any<ConverseStreamRequest>(), any())
        } coAnswers {
            secondArg<suspend (ConverseStreamResponse) -> Unit>()(response)
        }

        val flow = client.converseStreamFlow(
            "model-id",
            listOf(userMessageOf("hello")),
        )
        coVerify(exactly = 0) {
            client.converseStream<Unit>(any<ConverseStreamRequest>(), any())
        }

        flow.toList()
        flow.toList()

        coVerify(exactly = 2) {
            client.converseStream<Unit>(any<ConverseStreamRequest>(), any())
        }
    }

    @Test
    fun `native event order and error identity are preserved`() = runTest {
        val first = textDelta("a")
        val second = ConverseStreamOutput.SdkUnknown
        val expected = IllegalStateException("boom")
        val response = ConverseStreamResponse {
            stream = flow {
                emit(first)
                emit(second)
                throw expected
            }
        }
        coEvery {
            client.converseStream<Unit>(any<ConverseStreamRequest>(), any())
        } coAnswers {
            secondArg<suspend (ConverseStreamResponse) -> Unit>()(response)
        }
        val emitted = mutableListOf<ConverseStreamOutput>()

        val actual = assertFailsWith<IllegalStateException> {
            client.converseStreamFlow(
                "model-id",
                listOf(userMessageOf("hello")),
            ).toList(emitted)
        }

        emitted shouldBeEqualTo listOf(first, second)
        actual shouldBeSameInstanceAs expected
    }

    @Test
    fun `null native stream completes empty after successful operation`() = runTest {
        val response = ConverseStreamResponse {}
        coEvery {
            client.converseStream<Unit>(any<ConverseStreamRequest>(), any())
        } coAnswers {
            secondArg<suspend (ConverseStreamResponse) -> Unit>()(response)
        }

        client.converseStreamFlow(
            "model-id",
            listOf(userMessageOf("hello")),
        ).toList() shouldBeEqualTo emptyList()
    }

    @Test
    fun `null native stream does not hide operation failure`() = runTest {
        val response = ConverseStreamResponse {}
        val expected = IllegalStateException("operation failed")
        coEvery {
            client.converseStream<Unit>(any<ConverseStreamRequest>(), any())
        } coAnswers {
            secondArg<suspend (ConverseStreamResponse) -> Unit>()(response)
            throw expected
        }

        val actual = assertFailsWith<IllegalStateException> {
            client.converseStreamFlow(
                "model-id",
                listOf(userMessageOf("hello")),
            ).toList()
        }

        actual shouldBeSameInstanceAs expected
    }

    @Test
    fun `scoped cancellation finishes stream before closing client`() = runTest {
        val events = mutableListOf<String>()
        val scopedClient = mockk<BedrockRuntimeClient>(relaxed = true)
        val response = ConverseStreamResponse {
            stream = flow {
                try {
                    awaitCancellation()
                } finally {
                    events += "stream-finally"
                }
            }
        }
        coEvery {
            scopedClient.converseStream<Unit>(any<ConverseStreamRequest>(), any())
        } coAnswers {
            secondArg<suspend (ConverseStreamResponse) -> Unit>()(response)
        }
        every { scopedClient.close() } answers {
            events += "client-close"
        }

        val job = launch {
            withBedrockRuntimeClient(clientFactory = { scopedClient }) {
                it.converseStreamFlow(
                    "model-id",
                    listOf(userMessageOf("hello")),
                ).collect {}
            }
        }
        runCurrent()
        job.cancelAndJoin()
        events += "caller-cancelled"

        events shouldBeEqualTo listOf("stream-finally", "client-close", "caller-cancelled")
        verify(exactly = 1) { scopedClient.close() }
    }

    @Test
    fun `text delta Flow preserves empty text and filters non-text events`() = runTest {
        flowOf(
            textDelta("a"),
            ConverseStreamOutput.SdkUnknown,
            textDelta(""),
            textDelta("b"),
        ).textDeltaFlow().toList() shouldBeEqualTo listOf("a", "", "b")
    }

    @Test
    fun `takeUntil stops on the next upstream event after signal`() = runTest {
        val stop = MutableSharedFlow<Unit>()
        val source = flow {
            emit("a")
            stop.emit(Unit)
            emit("b")
            emit("c")
        }

        source.takeUntil(stop).toList() shouldBeEqualTo listOf("a")
    }

    private fun textDelta(text: String): ConverseStreamOutput =
        ConverseStreamOutput.ContentBlockDelta(
            ContentBlockDeltaEvent {
                contentBlockIndex = 0
                delta = ContentBlockDelta.Text(text)
            },
        )
}
