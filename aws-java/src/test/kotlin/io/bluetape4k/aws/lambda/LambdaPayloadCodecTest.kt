package io.bluetape4k.aws.lambda

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldBeSameInstanceAs
import io.bluetape4k.assertions.shouldBeTrue
import org.junit.jupiter.api.Test
import software.amazon.awssdk.core.SdkBytes
import software.amazon.awssdk.services.lambda.model.InvokeResponse
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

        val empty = LambdaPayloadCodecs.utf8.decode(LambdaPayloadCodecs.utf8.encode(""))
        empty shouldBeEqualTo ""
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
        val response = InvokeResponse.builder()
            .statusCode(200)
            .payload(SdkBytes.fromUtf8String("ok"))
            .functionError("Handled")
            .logResult(Base64.getEncoder().encodeToString("tail 로그".toByteArray()))
            .build()

        val result = response.toLambdaInvocationResult(LambdaPayloadCodecs.utf8)

        result.response shouldBeSameInstanceAs response
        result.statusCode shouldBeEqualTo 200
        result.functionError shouldBeEqualTo "Handled"
        result.hasFunctionError.shouldBeTrue()
        result.value shouldBeEqualTo "ok"
        result.logTail shouldBeEqualTo "tail 로그"
        result.payload?.decodeToString() shouldBeEqualTo "ok"
        (result.payload !== response.payload().asByteArrayUnsafe()).shouldBeTrue()
    }

    @Test
    fun `blank function error is not treated as function error`() {
        val response = InvokeResponse.builder().functionError(" ").build()

        response.toLambdaInvocationResult(LambdaPayloadCodecs.bytes).hasFunctionError.shouldBeFalse()
    }

    @Test
    fun `null payload is distinct from empty payload`() {
        val absent = InvokeResponse.builder().build().toLambdaInvocationResult(LambdaPayloadCodecs.bytes)
        absent.payload.shouldBeNull()
        absent.value.shouldBeNull()

        val empty = InvokeResponse.builder()
            .payload(SdkBytes.fromByteArray(ByteArray(0)))
            .build()
            .toLambdaInvocationResult(LambdaPayloadCodecs.bytes)
        val emptyPayload = empty.payload ?: error("empty payload was lost")
        emptyPayload.size shouldBeEqualTo 0
        val emptyValue = empty.value ?: error("empty value was lost")
        emptyValue.size shouldBeEqualTo 0
    }

    @Test
    fun `large payload copy remains bounded to the codec boundary`() {
        val input = ByteArray(4 * 1024 * 1024) { (it % 251).toByte() }
        var decodeCount = 0
        val codec = object : LambdaPayloadCodec<ByteArray> {
            override fun encode(value: ByteArray): ByteArray = value.copyOf()

            override fun decode(payload: ByteArray): ByteArray {
                decodeCount += 1
                return payload.copyOf()
            }
        }

        val result = InvokeResponse.builder()
            .payload(SdkBytes.fromByteArray(input))
            .build()
            .toLambdaInvocationResult(codec)

        decodeCount shouldBeEqualTo 1
        val payload = result.payload ?: error("payload was lost")
        val value = result.value ?: error("decoded value was lost")
        payload.contentEquals(input).shouldBeTrue()
        value.contentEquals(input).shouldBeTrue()
        (payload !== input).shouldBeTrue()
        (value !== payload).shouldBeTrue()
    }

    @Test
    fun `invalid log result fails without wrapping`() {
        val response = InvokeResponse.builder().logResult("not-base64").build()

        assertFailsWith<IllegalArgumentException> {
            response.toLambdaInvocationResult(LambdaPayloadCodecs.bytes)
        }
    }
}
