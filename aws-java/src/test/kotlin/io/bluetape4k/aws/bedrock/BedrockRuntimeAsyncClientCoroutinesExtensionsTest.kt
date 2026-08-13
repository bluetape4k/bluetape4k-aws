package io.bluetape4k.aws.bedrock

import io.bluetape4k.assertions.shouldBeSameInstanceAs
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.aws.bedrock.model.userMessageOf
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeAsyncClient
import software.amazon.awssdk.services.bedrockruntime.model.ConverseRequest
import software.amazon.awssdk.services.bedrockruntime.model.ConverseResponse
import software.amazon.awssdk.services.bedrockruntime.model.ValidationException
import java.util.concurrent.CompletableFuture
import io.bluetape4k.assertions.assertFailsWith

class BedrockRuntimeAsyncClientCoroutinesExtensionsTest {

    private val client = mockk<BedrockRuntimeAsyncClient>()

    @BeforeEach
    fun setup() {
        clearMocks(client)
    }

    @Test
    fun `suspend converse awaits the SDK future`() = runTest {
        val expected = ConverseResponse.builder().build()
        every { client.converse(any<ConverseRequest>()) } returns CompletableFuture.completedFuture(expected)

        client.converse("model-id", listOf(userMessageOf("hello"))) shouldBeSameInstanceAs expected

        verify(exactly = 1) { client.converse(any<ConverseRequest>()) }
    }

    @Test
    fun `cancelling coroutine cancels the future`() = runTest {
        val future = CompletableFuture<ConverseResponse>()
        every { client.converse(any<ConverseRequest>()) } returns future

        val job = launch {
            client.converse("model-id", listOf(userMessageOf("hello")))
        }
        runCurrent()
        job.cancelAndJoin()

        future.isCancelled.shouldBeTrue()
    }

    @Test
    fun `SDK exception reaches suspend caller unchanged`() = runTest {
        val expected = ValidationException.builder().message("invalid request").build()
        val future = CompletableFuture<ConverseResponse>()
        future.completeExceptionally(expected)
        every { client.converse(any<ConverseRequest>()) } returns future

        val actual = assertFailsWith<ValidationException> {
            client.converse("model-id", listOf(userMessageOf("hello")))
        }

        actual shouldBeSameInstanceAs expected
    }
}
