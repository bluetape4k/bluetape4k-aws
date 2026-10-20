package io.bluetape4k.aws.ktor.sqs

import aws.sdk.kotlin.services.sqs.model.Message
import aws.sdk.kotlin.services.sqs.model.MessageSystemAttributeName
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class SqsKotlinModelContractTest {

    @Test
    fun `converter preserves unicode UTF-8 bytes and returns the original Kotlin message`() {
        val body = "주문 완료 🧾"
        val message = Message {
            messageId = "message-1"
            receiptHandle = "receipt-1"
            this.body = body
            attributes = mapOf(
                MessageSystemAttributeName.ApproximateReceiveCount to "3",
                MessageSystemAttributeName.MessageGroupId to "orders",
                MessageSystemAttributeName.MessageDeduplicationId to "event-42",
            )
        }

        StringOrByteArraySqsMessageConverter.convert(message, String::class) shouldBeEqualTo body
        StringOrByteArraySqsMessageConverter
            .convert(message, ByteArray::class)
            .contentEquals(body.toByteArray(Charsets.UTF_8))
            .shouldBeTrue()
        assertSame(message, StringOrByteArraySqsMessageConverter.convert(message, Message::class))
        SqsConsumerFailureContext(
            queueUrl = "https://sqs.local/orders.fifo",
            message = message,
            cause = IllegalStateException("handler failed"),
            phase = SqsConsumerFailurePhase.Handler,
        ).approximateReceiveCount shouldBeEqualTo 3
        message.attributes?.get(MessageSystemAttributeName.MessageGroupId) shouldBeEqualTo "orders"
        message.attributes?.get(MessageSystemAttributeName.MessageDeduplicationId) shouldBeEqualTo "event-42"
    }

    @Test
    fun `converter keeps absent body as empty string and bytes`() {
        val message = Message { messageId = "message-1" }

        StringOrByteArraySqsMessageConverter.convert(message, String::class) shouldBeEqualTo ""
        StringOrByteArraySqsMessageConverter
            .convert(message, ByteArray::class)
            .contentEquals(byteArrayOf())
            .shouldBeTrue()
    }
}
