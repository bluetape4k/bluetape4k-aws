package io.bluetape4k.aws.ktor.sns

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldBeTrue
import org.junit.jupiter.api.Test
import java.net.URI

class SnsHttpMessageParserTest {

    private val parser = SnsHttpMessageParser.default()

    @Test
    fun `parse subscription confirmation payload`() {
        val message = parser.parse(
            json = subscriptionConfirmationJson,
            messageTypeHeader = "SubscriptionConfirmation",
        )

        message.type shouldBeEqualTo SnsHttpMessageType.SUBSCRIPTION_CONFIRMATION
        message.messageId shouldBeEqualTo "165545c9-2a5c-472c-8df2-7ff2be2b3b1b"
        message.topicArn shouldBeEqualTo "arn:aws:sns:us-east-1:000000000000:orders"
        message.token shouldBeEqualTo "token-1"
        message.subscribeUrl shouldBeEqualTo
            URI.create("https://sns.us-east-1.amazonaws.com/?Action=ConfirmSubscription&Token=token-1")
        message.unsubscribeUrl.shouldBeNull()
        message.canConfirmSubscription.shouldBeTrue()
    }

    @Test
    fun `parse notification payload`() {
        val message = parser.parse(
            json = notificationJson,
            messageTypeHeader = "Notification",
        )

        message.type shouldBeEqualTo SnsHttpMessageType.NOTIFICATION
        message.subject shouldBeEqualTo "Order created"
        message.message shouldBeEqualTo """{"orderId":"order-1"}"""
        message.unsubscribeUrl shouldBeEqualTo
            URI.create("https://sns.us-east-1.amazonaws.com/?Action=Unsubscribe&SubscriptionArn=sub-1")
        message.token.shouldBeNull()
    }

    @Test
    fun `reject header and JSON type mismatch`() {
        val error = assertFailsWith<IllegalArgumentException> {
            parser.parse(
                json = notificationJson,
                messageTypeHeader = "SubscriptionConfirmation",
            )
        }

        error.message.orEmpty() shouldBeEqualTo
            "x-amz-sns-message-type 'SubscriptionConfirmation' does not match JSON Type 'Notification'."
    }

    @Test
    fun `reject missing or non-string required field`() {
        assertFailsWith<IllegalArgumentException> {
            parser.parse(subscriptionConfirmationJson.replace("""  "Token" : "token-1",""", ""))
        }
        assertFailsWith<IllegalArgumentException> {
            parser.parse(notificationJson.replace(""""MessageId" : "22b80b92-fdea-4c2c-8f9d-bdfb0c7bf324"""", """"MessageId" : 1"""))
        }
    }

    @Test
    fun `reject hostile signing certificate URLs`() {
        listOf(
            "http://sns.us-east-1.amazonaws.com/SimpleNotificationService.pem",
            "https://sns.us-east-1.amazonaws.com.evil.example/SimpleNotificationService.pem",
            "https://user@sns.us-east-1.amazonaws.com/SimpleNotificationService.pem",
            "https://sns.us-east-1.amazonaws.com:444/SimpleNotificationService.pem",
            "https://sns.us-east-1.amazonaws.com/SimpleNotificationService.pem?x=1",
            "https://sns.us-east-1.amazonaws.com/SimpleNotificationService.txt",
            "https://sns.us-west-2.amazonaws.com/SimpleNotificationService.pem",
        ).forEach { badUrl ->
            assertFailsWith<IllegalArgumentException> {
                parser.parse(
                    notificationJson.replace(
                        "https://sns.us-east-1.amazonaws.com/SimpleNotificationService.pem",
                        badUrl,
                    )
                )
            }
        }
    }

    @Test
    fun `reject non object oversized and duplicate JSON`() {
        assertFailsWith<IllegalArgumentException> {
            parser.parse("[]")
        }
        assertFailsWith<IllegalArgumentException> {
            SnsHttpMessageParser(maxMessageBytes = 8).parse(notificationJson)
        }
        assertFailsWith<Exception> {
            parser.parse(notificationJson.replaceFirst(""""Type" : "Notification",""", """"Type" : "Notification", "Type" : "Notification","""))
        }
    }
}
