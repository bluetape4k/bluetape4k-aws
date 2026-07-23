package io.bluetape4k.aws.kotlin.bedrock

import aws.sdk.kotlin.services.bedrockruntime.BedrockRuntimeClient
import aws.sdk.kotlin.services.bedrockruntime.model.ConverseRequest
import aws.sdk.kotlin.services.bedrockruntime.model.ConverseResponse
import aws.smithy.kotlin.runtime.ServiceException
import io.bluetape4k.assertions.shouldBeSameInstanceAs
import io.bluetape4k.aws.kotlin.bedrock.model.userMessageOf
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import io.bluetape4k.assertions.assertFailsWith

class BedrockRuntimeClientExtensionsTest {

    private val client = mockk<BedrockRuntimeClient>()

    @BeforeEach
    fun setup() {
        clearMocks(client)
    }

    @Test
    fun `converse delegates once and preserves response identity`() = runTest {
        val expected = mockk<ConverseResponse>()
        coEvery { client.converse(any<ConverseRequest>()) } returns expected

        client.converse("model-id", listOf(userMessageOf("hello"))) shouldBeSameInstanceAs expected

        coVerify(exactly = 1) { client.converse(any<ConverseRequest>()) }
    }

    @Test
    fun `SDK exception reaches caller unchanged`() = runTest {
        val expected = mockk<ServiceException>()
        coEvery { client.converse(any<ConverseRequest>()) } throws expected

        val actual = assertFailsWith<ServiceException> {
            client.converse("model-id", listOf(userMessageOf("hello")))
        }

        actual shouldBeSameInstanceAs expected
    }

    @Test
    fun `coroutine cancellation reaches native suspend call`() = runTest {
        coEvery { client.converse(any<ConverseRequest>()) } coAnswers {
            awaitCancellation()
        }

        val job = launch {
            client.converse("model-id", listOf(userMessageOf("hello")))
        }
        runCurrent()
        job.cancelAndJoin()

        coVerify(exactly = 1) { client.converse(any<ConverseRequest>()) }
    }
}
