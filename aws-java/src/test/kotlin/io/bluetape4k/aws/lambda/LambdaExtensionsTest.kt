package io.bluetape4k.aws.lambda

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeSameInstanceAs
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.aws.core.toSdkBytes
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import software.amazon.awssdk.services.lambda.LambdaAsyncClient
import software.amazon.awssdk.services.lambda.LambdaClient
import software.amazon.awssdk.services.lambda.model.InvokeRequest
import software.amazon.awssdk.services.lambda.model.InvokeResponse
import java.util.concurrent.CompletableFuture

class LambdaExtensionsTest {

    @Test
    fun `sync bytes and string preserve final request and raw response`() {
        val client = mockk<LambdaClient>()
        val expected = InvokeResponse.builder().statusCode(202).payload("ok".toSdkBytes()).build()
        every { client.invoke(any<InvokeRequest>()) } returns expected

        val bytesResult = client.invokeBytes("orders", payload = byteArrayOf(1, 2), qualifier = "live") {
            clientContext("request-context")
        }
        val stringResult = client.invokeString("orders", payload = "hello")

        bytesResult.response shouldBeSameInstanceAs expected
        bytesResult.payload?.toList() shouldBeEqualTo listOf(111.toByte(), 107)
        stringResult.value shouldBeEqualTo "ok"
        verify(exactly = 2) {
            client.invoke(match<InvokeRequest> { request ->
                request.functionName() == "orders"
            })
        }
        verify(exactly = 1) {
            client.invoke(match<InvokeRequest> { request ->
                request.qualifier() == "live" && request.clientContext() == "request-context"
            })
        }
    }

    @Test
    fun `typed invocation decodes success and function error payload`() {
        val client = mockk<LambdaClient>()
        val codec = LambdaPayloadCodecs.utf8
        val success = InvokeResponse.builder().payload("accepted".toSdkBytes()).build()
        val failure = InvokeResponse.builder()
            .functionError("Unhandled")
            .payload("failed".toSdkBytes())
            .build()
        every { client.invoke(any<InvokeRequest>()) } returnsMany listOf(success, failure)

        val successResult = client.invokeTyped("orders", "request", codec)
        val failureResult = client.invokeTyped("orders", "request", codec)

        successResult.value shouldBeEqualTo "accepted"
        successResult.hasFunctionError.shouldBeFalse()
        failureResult.value shouldBeEqualTo "failed"
        failureResult.functionError shouldBeEqualTo "Unhandled"
        failureResult.hasFunctionError.shouldBeTrue()
    }

    @Test
    fun `function error is result data and transport error is unchanged`() {
        val client = mockk<LambdaClient>()
        val transportError = IllegalStateException("transport")
        every { client.invoke(any<InvokeRequest>()) } returnsMany listOf(
            InvokeResponse.builder().functionError("Handled").payload("error".toSdkBytes()).build(),
        ) andThenThrows transportError

        val functionErrorResult = client.invokeString("orders", payload = "request")
        functionErrorResult.hasFunctionError.shouldBeTrue()
        functionErrorResult.value shouldBeEqualTo "error"

        val actual = assertFailsWith<IllegalStateException> { client.invokeString("orders", payload = "request") }
        actual shouldBeSameInstanceAs transportError
    }

    @Test
    fun `null payload yields null value while empty payload decodes`() {
        val client = mockk<LambdaClient>()
        every { client.invoke(any<InvokeRequest>()) } returnsMany listOf(
            InvokeResponse.builder().build(),
            InvokeResponse.builder().payload("".toSdkBytes()).build(),
        )

        val absent = client.invokeBytes("orders")
        val empty = client.invokeBytes("orders")

        absent.value shouldBeEqualTo null
        absent.payload shouldBeEqualTo null
        val emptyValue = empty.value ?: error("empty value was lost")
        emptyValue.size shouldBeEqualTo 0
        val emptyPayload = empty.payload ?: error("empty payload was lost")
        emptyPayload.size shouldBeEqualTo 0
    }

    @Test
    fun `invalid log tail raises decode error without fallback`() {
        val client = mockk<LambdaClient>()
        every { client.invoke(any<InvokeRequest>()) } returns
            InvokeResponse.builder().logResult("not-base64").build()

        assertFailsWith<IllegalArgumentException> { client.invokeString("orders") }
    }

    @Test
    fun `async future maps response exactly once`() {
        val client = mockk<LambdaAsyncClient>()
        val expected = InvokeResponse.builder().payload("accepted".toSdkBytes()).build()
        val sdkFuture = CompletableFuture.completedFuture(expected)
        every { client.invoke(any<InvokeRequest>()) } returns sdkFuture

        val result = client.invokeStringAsync("orders").get()

        result.response shouldBeSameInstanceAs expected
        result.value shouldBeEqualTo "accepted"
        verify(exactly = 1) { client.invoke(any<InvokeRequest>()) }
    }

    @Test
    fun `cancel before response cancels sdk future`() {
        val client = mockk<LambdaAsyncClient>()
        val sdkFuture = CompletableFuture<InvokeResponse>()
        every { client.invoke(any<InvokeRequest>()) } returns sdkFuture

        val resultFuture = client.invokeStringAsync("orders")
        resultFuture.cancel(true)

        resultFuture.isCancelled.shouldBeTrue()
        sdkFuture.isCancelled.shouldBeTrue()
    }

    @Test
    fun `response after cancellation cannot resurrect result`() {
        val client = mockk<LambdaAsyncClient>()
        var decodeCount = 0
        val codec = object : LambdaPayloadCodec<String> {
            override fun encode(value: String): ByteArray = value.toByteArray()

            override fun decode(payload: ByteArray): String {
                decodeCount += 1
                return payload.decodeToString()
            }
        }
        val sdkFuture = CompletableFuture<InvokeResponse>()
        every { client.invoke(any<InvokeRequest>()) } returns sdkFuture

        val resultFuture = client.invokeTypedAsync("orders", "request", codec)
        resultFuture.cancel(true)
        sdkFuture.complete(InvokeResponse.builder().payload("late".toSdkBytes()).build())

        resultFuture.isCancelled.shouldBeTrue()
        decodeCount shouldBeEqualTo 0
    }

    @Test
    fun `await overload propagates CancellationException`() = runTest {
        val client = mockk<LambdaAsyncClient>()
        val sdkFuture = CompletableFuture<InvokeResponse>()
        sdkFuture.completeExceptionally(CancellationException("cancelled"))
        every { client.invoke(any<InvokeRequest>()) } returns sdkFuture

        assertFailsWith<CancellationException> { client.invokeString("orders") }
    }
}
