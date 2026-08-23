package io.bluetape4k.aws.lambda.model

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeNull
import org.junit.jupiter.api.Test
import software.amazon.awssdk.services.lambda.model.InvocationType
import software.amazon.awssdk.services.lambda.model.LogType

class LambdaRequestSupportTest {

    @Test
    fun `request maps function ARN qualifier invocation log and payload`() {
        val payload = byteArrayOf(1, 2, 3)

        val request = invokeRequestOf(
            functionName = "arn:aws:lambda:ap-northeast-2:123456789012:function:orders",
            payload = payload,
            qualifier = "live",
            invocationType = InvocationType.REQUEST_RESPONSE,
            logType = LogType.TAIL,
        )

        request.functionName() shouldBeEqualTo "arn:aws:lambda:ap-northeast-2:123456789012:function:orders"
        request.qualifier() shouldBeEqualTo "live"
        request.invocationType() shouldBeEqualTo InvocationType.REQUEST_RESPONSE
        request.logType() shouldBeEqualTo LogType.TAIL
        request.payload().asByteArray().toList() shouldBeEqualTo payload.toList()
        payload[0] = 9
        request.payload().asByteArray()[0] shouldBeEqualTo 1.toByte()
    }

    @Test
    fun `null payload is omitted and empty payload is retained`() {
        invokeRequestOf("orders").payload().shouldBeNull()

        val empty = invokeRequestOf("orders", payload = ByteArray(0)).payload()
            ?: error("empty payload was omitted")
        empty.asByteArray().size shouldBeEqualTo 0
    }

    @Test
    fun `callback payload is final and invariant is rechecked`() {
        val request = invokeRequestOf("orders", payload = byteArrayOf(1)) {
            payload(null)
        }
        request.payload().shouldBeNull()

        assertFailsWith<IllegalArgumentException> {
            invokeRequestOf("orders") {
                invocationType(InvocationType.EVENT)
                logType(LogType.TAIL)
            }
        }
    }

    @Test
    fun `blank function or qualifier fails before SDK call`() {
        assertFailsWith<IllegalArgumentException> { invokeRequestOf(" ") }
        assertFailsWith<IllegalArgumentException> { invokeRequestOf("orders", qualifier = " ") }
    }

    @Test
    fun `tail log is rejected for event and dry run`() {
        assertFailsWith<IllegalArgumentException> {
            invokeRequestOf("orders", invocationType = InvocationType.EVENT, logType = LogType.TAIL)
        }
        assertFailsWith<IllegalArgumentException> {
            invokeRequestOf("orders", invocationType = InvocationType.DRY_RUN, logType = LogType.TAIL)
        }
    }
}
