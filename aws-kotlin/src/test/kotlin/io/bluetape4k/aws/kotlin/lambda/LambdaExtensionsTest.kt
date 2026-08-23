package io.bluetape4k.aws.kotlin.lambda

import aws.sdk.kotlin.services.lambda.LambdaClient
import aws.sdk.kotlin.services.lambda.model.InvokeRequest
import aws.sdk.kotlin.services.lambda.model.InvokeResponse
import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeSameInstanceAs
import io.bluetape4k.assertions.shouldBeTrue
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class LambdaExtensionsTest {

    private val client = mockk<LambdaClient>()

    @BeforeEach
    fun resetMocks() {
        clearMocks(client)
    }

    @Test
    fun `bytes and string invocation preserve raw response and request`() = runTest {
        val expected = InvokeResponse {
            statusCode = 202
            payload = "ok".toByteArray()
        }
        coEvery { client.invoke(any<InvokeRequest>()) } returns expected

        val bytesResult = client.invokeBytes("orders", payload = byteArrayOf(1, 2), qualifier = "live")
        val stringResult = client.invokeString("orders", payload = "hello")

        bytesResult.response shouldBeSameInstanceAs expected
        bytesResult.value?.toList() shouldBeEqualTo listOf(111.toByte(), 107)
        stringResult.value shouldBeEqualTo "ok"
        coVerify(exactly = 2) { client.invoke(any<InvokeRequest>()) }
    }

    @Test
    fun `typed invocation decodes function error payload`() = runTest {
        val expected = InvokeResponse {
            functionError = "Handled"
            payload = "failed".toByteArray()
        }
        coEvery { client.invoke(any<InvokeRequest>()) } returns expected

        val result = client.invokeTyped("orders", "request", LambdaPayloadCodecs.utf8)

        result.value shouldBeEqualTo "failed"
        result.functionError shouldBeEqualTo "Handled"
        result.hasFunctionError.shouldBeTrue()
    }

    @Test
    fun `null payload yields null value while empty payload decodes`() = runTest {
        coEvery { client.invoke(any<InvokeRequest>()) } returnsMany listOf(
            InvokeResponse {},
            InvokeResponse { payload = ByteArray(0) },
        )

        val absent = client.invokeBytes("orders")
        val empty = client.invokeBytes("orders")

        absent.value shouldBeEqualTo null
        val emptyValue = empty.value ?: error("empty value was lost")
        emptyValue.size shouldBeEqualTo 0
    }

    @Test
    fun `invalid log tail raises codec error without fallback`() = runTest {
        coEvery { client.invoke(any<InvokeRequest>()) } returns InvokeResponse { logResult = "not-base64" }

        assertFailsWith<IllegalArgumentException> { client.invokeString("orders") }
    }

    @Test
    fun `suspend cancellation propagates and does not call after cancellation`() = runTest {
        coEvery { client.invoke(any<InvokeRequest>()) } coAnswers { awaitCancellation() }
        val job = launch { client.invokeString("orders") }
        runCurrent()

        job.cancelAndJoin()

        job.isCancelled.shouldBeTrue()
        coVerify(exactly = 1) { client.invoke(any<InvokeRequest>()) }
    }

    @Test
    fun `sdk cancellation exception is preserved`() = runTest {
        coEvery { client.invoke(any<InvokeRequest>()) } throws CancellationException("cancelled")

        assertFailsWith<CancellationException> { client.invokeString("orders") }
        coVerify(exactly = 1) { client.invoke(any<InvokeRequest>()) }
    }
}
