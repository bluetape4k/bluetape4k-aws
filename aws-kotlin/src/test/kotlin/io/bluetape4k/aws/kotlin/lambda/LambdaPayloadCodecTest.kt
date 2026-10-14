package io.bluetape4k.aws.kotlin.lambda

import aws.sdk.kotlin.services.lambda.model.InvokeResponse
import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEmpty
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldBeSameInstanceAs
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldContentEqual
import io.bluetape4k.assertions.shouldNotBeEmpty
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.jackson3.Jackson
import io.bluetape4k.junit5.faker.Fakers
import io.bluetape4k.logging.KLogging
import org.junit.jupiter.api.RepeatedTest
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import java.util.*

class LambdaPayloadCodecTest {

    companion object: KLogging() {
        private const val REPEAT_SIZE = 5
    }

    private val jsonMapper = Jackson.defaultJsonMapper

    @Test
    fun `bytes codec copies input and decoded output`() {
        val input = byteArrayOf(1, 2, 3)

        val encoded = LambdaPayloadCodecs.bytes.encode(input)
        input[0] = 9
        encoded.toList() shouldBeEqualTo listOf(1.toByte(), 2, 3)
        encoded shouldContentEqual byteArrayOf(1, 2, 3)

        val decoded = LambdaPayloadCodecs.bytes.decode(encoded)
        encoded[0] = 8
        decoded shouldContentEqual byteArrayOf(1, 2, 3)
    }

    @RepeatedTest(REPEAT_SIZE)
    fun `utf8 codec preserves unicode`() {
        val value = "주문 ✅ " + Fakers.defaultFaker.lorem().paragraph()
        LambdaPayloadCodecs.utf8.decode(LambdaPayloadCodecs.utf8.encode(value)) shouldBeEqualTo value
    }

    @Test
    fun `utf8 codec preseves empty string`() {
        val value = ""
        LambdaPayloadCodecs.utf8.decode(LambdaPayloadCodecs.utf8.encode(value)) shouldBeEqualTo value
    }

    @RepeatedTest(REPEAT_SIZE)
    fun `jackson codec uses caller mapper and class`() {
        val codec = LambdaPayloadCodecs.jackson(jsonMapper, String::class.java)

        val value = Fakers.defaultFaker.lorem().paragraph()
        codec.decode(codec.encode(value)) shouldBeEqualTo value
    }

    @Test
    fun `malformed json propagates and no unsafe typing is enabled`() {
        val codec = LambdaPayloadCodecs.jackson(jsonMapper, String::class.java)

        assertFailsWith<Exception> {
            codec.decode("[".toByteArray())
        }
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

        val empty = InvokeResponse {
            payload = ByteArray(0)
        }.toLambdaInvocationResult(LambdaPayloadCodecs.bytes)

        val emptyPayload = empty.payload.shouldNotBeNull()
        val emptyValue = empty.value.shouldNotBeNull()

        emptyPayload.shouldBeEmpty()
        emptyValue.shouldBeEmpty()
    }

    @Test
    fun `invalid log result fails without wrapping`() {
        val response = InvokeResponse { logResult = "not-base64" }

        assertFailsWith<IllegalArgumentException> {
            response.toLambdaInvocationResult(LambdaPayloadCodecs.bytes)
        }
    }
}
