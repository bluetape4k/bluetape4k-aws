package io.bluetape4k.aws.kotlin.lambda

import aws.sdk.kotlin.services.lambda.model.InvokeResponse
import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldBeSameInstanceAs
import io.bluetape4k.assertions.shouldBeTrue
import org.junit.jupiter.api.Test
import tools.jackson.databind.ObjectMapper
import java.util.Base64

class LambdaPayloadCodecTest {

    @Test
    fun `bytes codec copies input and decoded output`() {
        val input = byteArrayOf(1, 2, 3)

        val encoded = LambdaPayloadCodecs.bytes.encode(input)
        input[0] = 9
        encoded.toList() shouldBeEqualTo listOf(1.toByte(), 2, 3)

        val decoded = LambdaPayloadCodecs.bytes.decode(encoded)
        encoded[0] = 8
        decoded.toList() shouldBeEqualTo listOf(1.toByte(), 2, 3)
    }

    @Test
    fun `utf8 codec preserves unicode and empty string`() {
        val value = "주문 ✅"
        LambdaPayloadCodecs.utf8.decode(LambdaPayloadCodecs.utf8.encode(value)) shouldBeEqualTo value
        LambdaPayloadCodecs.utf8.decode(LambdaPayloadCodecs.utf8.encode("")) shouldBeEqualTo ""
    }

    @Test
    fun `jackson codec uses caller mapper and class`() {
        val mapper = ObjectMapper()
        val codec = LambdaPayloadCodecs.jackson(mapper, String::class.java)

        codec.decode(codec.encode("caller mapper")) shouldBeEqualTo "caller mapper"
    }

    @Test
    fun `malformed json propagates and no unsafe typing is enabled`() {
        val codec = LambdaPayloadCodecs.jackson(ObjectMapper(), String::class.java)

        assertFailsWith<Exception> { codec.decode("[".toByteArray()) }
    }

    @Test
    fun `result copies payload and decodes function error and log tail`() {
        val response = InvokeResponse {
            statusCode = 200
            payload = "ok".toByteArray()
            functionError = "Handled"
            logResult = Base64.getEncoder().encodeToString("tail 로그".toByteArray())
        }

        val result = response.toLambdaInvocationResult(LambdaPayloadCodecs.utf8)

        result.response shouldBeSameInstanceAs response
        result.statusCode shouldBeEqualTo 200
        result.functionError shouldBeEqualTo "Handled"
        result.hasFunctionError.shouldBeTrue()
        result.value shouldBeEqualTo "ok"
        result.payload?.decodeToString() shouldBeEqualTo "ok"
        result.logTail shouldBeEqualTo "tail 로그"
        val responsePayload = response.payload ?: error("response payload was lost")
        val resultPayload = result.payload ?: error("result payload was lost")
        (resultPayload !== responsePayload).shouldBeTrue()
    }

    @Test
    fun `blank function error is not treated as function error`() {
        val response = InvokeResponse { functionError = " " }

        response.toLambdaInvocationResult(LambdaPayloadCodecs.bytes).hasFunctionError.shouldBeFalse()
    }

    @Test
    fun `null payload is distinct from empty payload`() {
        val absent = InvokeResponse {}.toLambdaInvocationResult(LambdaPayloadCodecs.bytes)
        absent.payload.shouldBeNull()
        absent.value.shouldBeNull()

        val empty = InvokeResponse { payload = ByteArray(0) }
            .toLambdaInvocationResult(LambdaPayloadCodecs.bytes)
        val emptyPayload = empty.payload ?: error("empty payload was lost")
        val emptyValue = empty.value ?: error("empty value was lost")
        emptyPayload.size shouldBeEqualTo 0
        emptyValue.size shouldBeEqualTo 0
    }

    @Test
    fun `invalid log result fails without wrapping`() {
        val response = InvokeResponse { logResult = "not-base64" }

        assertFailsWith<IllegalArgumentException> {
            response.toLambdaInvocationResult(LambdaPayloadCodecs.bytes)
        }
    }
}
