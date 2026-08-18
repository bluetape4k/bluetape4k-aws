package io.bluetape4k.aws.spring.sqs

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.assertions.shouldHaveSize
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.codec.Base58
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.mockk.mockk
import org.junit.jupiter.api.Test
import software.amazon.awssdk.services.sqs.model.Message
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue
import software.amazon.awssdk.services.sqs.model.MessageSystemAttributeName
import tools.jackson.databind.ObjectMapper

class SqsSnsMessageConverterTest {

    private val objectMapper = ObjectMapper()

    @Test
    fun `SNS notification converts typed payload and preserves SNS and SQS metadata`() {
        val message = snsMessage(
            body = notificationJson(
                message = """{"orderId":"order-${Base58.randomString(16)}"}""",
            ),
        )

        val notification = SnsMessageConverter(objectMapper)
            .convertNotification(message, OrderPayload::class.java)

        notification.type shouldBeEqualTo "Notification"
        notification.message.orderId shouldContain "order-"
        notification.messageId shouldBeEqualTo "sns-message-1"
        notification.topicArn shouldBeEqualTo "arn:aws:sns:us-east-1:000000000000:orders"
        notification.subject shouldBeEqualTo "Order created"
        notification.timestamp shouldBeEqualTo "2026-08-18T00:00:00Z"
        notification.signatureVersion shouldBeEqualTo "1"
        notification.signature shouldBeEqualTo "signature-1"
        notification.signingCertUrl shouldBeEqualTo "https://sns.us-east-1.amazonaws.com/cert.pem"
        notification.messageAttributes shouldHaveSize 1
        notification.messageAttributes.getValue("trace").value shouldBeEqualTo "trace-value"
        notification.sqs.queueUrl shouldBeEqualTo "https://sqs.local/orders.fifo"
        notification.sqs.messageGroupId shouldBeEqualTo "orders"
        notification.sqs.messageDeduplicationId shouldBeEqualTo "dedup-1"
        notification.sqs.messageAttributes.getValue("tenant").stringValue() shouldBeEqualTo "tenant-1"
        notification.headers.getValue(SnsNotificationHeaders.MESSAGE_ID) shouldBeEqualTo "sns-message-1"
        notification.headers.getValue(SnsNotificationHeaders.TOPIC_ARN) shouldBeEqualTo
            "arn:aws:sns:us-east-1:000000000000:orders"
        notification.headers.getValue(SnsNotificationHeaders.MESSAGE_ATTRIBUTES) shouldBeEqualTo
            notification.messageAttributes
        notification.rawEnvelope shouldBeEqualTo message.body
    }

    @Test
    fun `SNS notification keeps plain text payload as a typed string`() {
        val message = snsMessage(notificationJson(message = "plain-text"))

        val notification = SnsMessageConverter(objectMapper)
            .convertNotification(message, String::class.java)

        notification.message shouldBeEqualTo "plain-text"
    }

    @Test
    fun `ordinary SQS message falls back to the existing body conversion`() {
        val message = snsMessage("ordinary-sqs-body")

        SnsMessageConverter(objectMapper).convert(message, String::class.java) shouldBeEqualTo "ordinary-sqs-body"
    }

    @Test
    fun `malformed SNS notification falls back without losing the original body`() {
        val message = snsMessage(
            """
            {"Type":"Notification","MessageId":"sns-message-1","Message":"missing topic"}
            """.trimIndent(),
        )

        SnsMessageConverter(
            objectMapper,
            malformedEnvelopeStrategy = SnsMalformedEnvelopeStrategy.FALLBACK_TO_SQS,
        ).convert(message, String::class.java) shouldBeEqualTo message.body
    }

    @Test
    fun `strict malformed SNS notification exposes the original body to the error handler`() {
        val body = """{"Type":"Notification","MessageId":"sns-message-1"}"""
        val error = assertFailsWith<SnsMessageConversionException> {
            SnsMessageConverter(
                objectMapper,
                malformedEnvelopeStrategy = SnsMalformedEnvelopeStrategy.THROW,
            ).convert(snsMessage(body), String::class.java)
        }

        error.rawEnvelope shouldBeEqualTo body
        error.message.orEmpty() shouldContain "SNS notification envelope"
    }

    @Test
    fun `raw SNS envelope retention can be disabled`() {
        val message = snsMessage(notificationJson(message = "plain-text"))

        val notification = SnsMessageConverter(
            objectMapper,
            preserveRawEnvelope = false,
        ).convertNotification(message, String::class.java)

        notification.rawEnvelope.shouldBeNull()
    }

    @Test
    fun `listener receives a typed SNS notification argument`() = runSuspendIO {
        val probe = NotificationListenerProbe()
        val method = NotificationListenerProbe::class.java.declaredMethods.single { it.name == "handle" }
        val invoker = SqsListenerMethodInvoker(probe, method, JacksonSqsMessageConverter(objectMapper))

        invoker.invoke(
            snsMessage(notificationJson(message = """{"orderId":"order-1"}""")),
            mockk(),
        )

        val notification = probe.notification.shouldNotBeNull()
        notification.message.orderId shouldBeEqualTo "order-1"
        notification.sqs.messageGroupId shouldBeEqualTo "orders"
    }

    private fun snsMessage(body: String): SqsReceivedMessage =
        SqsReceivedMessage(
            queueUrl = "https://sqs.local/orders.fifo",
            message = Message.builder()
                .messageId("sqs-message-${Base58.randomString(16)}")
                .receiptHandle("receipt-${Base58.randomString(16)}")
                .body(body)
                .messageAttributes(
                    mapOf(
                        "tenant" to MessageAttributeValue.builder()
                            .dataType("String")
                            .stringValue("tenant-1")
                            .build(),
                    ),
                )
                .attributes(
                    mapOf(
                        MessageSystemAttributeName.MESSAGE_GROUP_ID to "orders",
                        MessageSystemAttributeName.MESSAGE_DEDUPLICATION_ID to "dedup-1",
                    ),
                )
                .build(),
        )

    private fun notificationJson(message: String): String =
        """
        {
          "Type":"Notification",
          "MessageId":"sns-message-1",
          "TopicArn":"arn:aws:sns:us-east-1:000000000000:orders",
          "Subject":"Order created",
          "Message":${objectMapper.writeValueAsString(message)},
          "Timestamp":"2026-08-18T00:00:00Z",
          "SignatureVersion":"1",
          "Signature":"signature-1",
          "SigningCertURL":"https://sns.us-east-1.amazonaws.com/cert.pem",
          "MessageAttributes":{
            "trace":{"Type":"String","Value":"trace-value"}
          }
        }
        """.trimIndent()

    class OrderPayload() {
        var orderId: String = ""

        constructor(orderId: String) : this() {
            this.orderId = orderId
        }
    }

    class NotificationListenerProbe {
        var notification: SnsNotification<OrderPayload>? = null

        suspend fun handle(notification: SnsNotification<OrderPayload>) {
            this.notification = notification
        }
    }
}
