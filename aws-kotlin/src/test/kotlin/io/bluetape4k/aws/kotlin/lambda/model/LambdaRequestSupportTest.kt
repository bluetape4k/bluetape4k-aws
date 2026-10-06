package io.bluetape4k.aws.kotlin.lambda.model

import aws.sdk.kotlin.services.lambda.model.InvocationType
import aws.sdk.kotlin.services.lambda.model.LogType
import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEmpty
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldContentEqual
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.logging.KLogging
import org.junit.jupiter.api.Test

class LambdaRequestSupportTest {

    companion object: KLogging()

    @Test
    fun `request maps function ARN qualifier invocation log and payload`() {
        val payload = byteArrayOf(1, 2, 3)

        val request = invokeRequestOf(
            functionName = "arn:aws:lambda:ap-northeast-2:123456789012:function:orders",
            payload = payload,
            qualifier = "live",
            invocationType = InvocationType.RequestResponse,
            logType = LogType.Tail,
        )

        request.functionName shouldBeEqualTo "arn:aws:lambda:ap-northeast-2:123456789012:function:orders"
        request.qualifier shouldBeEqualTo "live"
        request.invocationType shouldBeEqualTo InvocationType.RequestResponse
        request.logType shouldBeEqualTo LogType.Tail
        request.payload shouldContentEqual payload

        payload[0] = 9
        request.payload?.get(0) shouldBeEqualTo 1.toByte()
    }

    @Test
    fun `null payload is omitted and empty payload is retained`() {
        invokeRequestOf("orders").payload.shouldBeNull()

        val empty = invokeRequestOf("orders", payload = ByteArray(0)).payload
        empty.shouldNotBeNull().shouldBeEmpty()
    }

    @Test
    fun `callback payload is final and invariant is rechecked`() {
        val request = invokeRequestOf("orders", payload = byteArrayOf(1)) {
            payload = null
        }
        request.payload.shouldBeNull()

        assertFailsWith<IllegalArgumentException> {
            invokeRequestOf("orders") {
                invocationType = InvocationType.Event
                logType = LogType.Tail
            }
        }
    }

    @Test
    fun `blank function or qualifier fails before SDK call`() {
        assertFailsWith<IllegalArgumentException> {
            invokeRequestOf(" ")
        }
        assertFailsWith<IllegalArgumentException> {
            invokeRequestOf("orders", qualifier = " ")
        }
    }

    @Test
    fun `tail log is rejected for event and dry run`() {
        assertFailsWith<IllegalArgumentException> {
            invokeRequestOf("orders", invocationType = InvocationType.Event, logType = LogType.Tail)
        }
        assertFailsWith<IllegalArgumentException> {
            invokeRequestOf("orders", invocationType = InvocationType.DryRun, logType = LogType.Tail)
        }
    }
}
