package io.bluetape4k.aws.spring.sns

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldBeTrue
import org.junit.jupiter.api.Test
import java.net.URI

class SnsHttpMessageParserTest {

    @Test
    fun `parse subscription confirmation payload`() {
        val message = SnsHttpMessageParser.parse(
            json = subscriptionConfirmationJson,
            messageTypeHeader = "SubscriptionConfirmation",
        )

        message.type shouldBeEqualTo SnsHttpMessageType.SUBSCRIPTION_CONFIRMATION
        message.messageId shouldBeEqualTo "165545c9-2a5c-472c-8df2-7ff2be2b3b1b"
        message.topicArn shouldBeEqualTo "arn:aws:sns:us-west-2:123456789012:MyTopic"
        message.token shouldBeEqualTo "token-1"
        message.subscribeUrl shouldBeEqualTo
            URI.create("https://sns.us-west-2.amazonaws.com/?Action=ConfirmSubscription&Token=token-1")
        message.unsubscribeUrl.shouldBeNull()
        message.canConfirmSubscription.shouldBeTrue()
    }

    @Test
    fun `parse notification payload`() {
        val message = SnsHttpMessageParser.parse(
            json = notificationJson,
            messageTypeHeader = "Notification",
        )

        message.type shouldBeEqualTo SnsHttpMessageType.NOTIFICATION
        message.subject shouldBeEqualTo "Order created"
        message.message shouldBeEqualTo """{"orderId":"order-1"}"""
        message.unsubscribeUrl shouldBeEqualTo
            URI.create("https://sns.us-west-2.amazonaws.com/?Action=Unsubscribe&SubscriptionArn=sub-1")
        message.token.shouldBeNull()
    }

    @Test
    fun `parse unsubscribe confirmation payload`() {
        val message = SnsHttpMessageParser.parse(
            json = unsubscribeConfirmationJson,
            messageTypeHeader = "UnsubscribeConfirmation",
        )

        message.type shouldBeEqualTo SnsHttpMessageType.UNSUBSCRIBE_CONFIRMATION
        message.token shouldBeEqualTo "token-2"
        message.canConfirmSubscription.shouldBeTrue()
    }

    @Test
    fun `reject header and JSON type mismatch`() {
        val error = assertFailsWith<IllegalArgumentException> {
            SnsHttpMessageParser.parse(
                json = notificationJson,
                messageTypeHeader = "SubscriptionConfirmation",
            )
        }

        error.message.orEmpty() shouldBeEqualTo
            "x-amz-sns-message-type 'SubscriptionConfirmation' does not match JSON Type 'Notification'."
    }

    @Test
    fun `reject confirmation payload without token`() {
        val error = assertFailsWith<IllegalArgumentException> {
            SnsHttpMessageParser.parse(subscriptionConfirmationJson.replace("""  "Token" : "token-1",""", ""))
        }

        error.message.orEmpty() shouldBeEqualTo "SubscriptionConfirmation message requires Token."
    }

    @Test
    fun `reject non HTTPS signing certificate URL`() {
        val error = assertFailsWith<IllegalArgumentException> {
            SnsHttpMessageParser.parse(
                notificationJson.replace(
                    "https://sns.us-west-2.amazonaws.com/SimpleNotificationService.pem",
                    "http://sns.us-west-2.amazonaws.com/SimpleNotificationService.pem",
                )
            )
        }

        error.message.orEmpty() shouldBeEqualTo "SNS HTTP message SigningCertURL must use https."
    }

    @Test
    fun `reject non SNS signing certificate host`() {
        val error = assertFailsWith<IllegalArgumentException> {
            SnsHttpMessageParser.parse(
                notificationJson.replace(
                    "https://sns.us-west-2.amazonaws.com/SimpleNotificationService.pem",
                    "https://example.com/SimpleNotificationService.pem",
                )
            )
        }

        error.message.orEmpty() shouldBeEqualTo
            "SNS HTTP message SigningCertURL must use an Amazon SNS host."
    }

    @Test
    fun `reject non string required fields`() {
        assertFailsWith<IllegalArgumentException> {
            SnsHttpMessageParser.parse(
                notificationJson.replace(
                    "\"MessageId\" : \"22b80b92-fdea-4c2c-8f9d-bdfb0c7bf324\"",
                    "\"MessageId\" : 1",
                )
            )
        }
    }

    @Test
    fun `reject hostile signing certificate URL variants`() {
        listOf(
            "https://user@sns.us-west-2.amazonaws.com/SimpleNotificationService.pem",
            "https://sns.us-west-2.amazonaws.com:444/SimpleNotificationService.pem",
            "https://sns.us-west-2.amazonaws.com/SimpleNotificationService.pem?x=1",
            "https://sns.us-west-2.amazonaws.com/SimpleNotificationService.pem#fragment",
            "https://sns.us-west-2.amazonaws.com/SimpleNotificationService.txt",
            "https://sns.us-east-1.amazonaws.com/SimpleNotificationService.pem",
            "https://sns.us-west-2.amazonaws.com.cn/SimpleNotificationService.pem",
        ).forEach { badUrl ->
            assertFailsWith<IllegalArgumentException> {
                SnsHttpMessageParser.parse(
                    notificationJson.replace(
                        "https://sns.us-west-2.amazonaws.com/SimpleNotificationService.pem",
                        badUrl,
                    )
                )
            }
        }
    }

    @Test
    fun `reject oversized payload`() {
        assertFailsWith<IllegalArgumentException> {
            SnsHttpMessageParser.parse(notificationJson + " ".repeat(256 * 1024))
        }
    }

    private val subscriptionConfirmationJson: String =
        """
        {
          "Type" : "SubscriptionConfirmation",
          "MessageId" : "165545c9-2a5c-472c-8df2-7ff2be2b3b1b",
          "Token" : "token-1",
          "TopicArn" : "arn:aws:sns:us-west-2:123456789012:MyTopic",
          "Message" : "Confirm this subscription.",
          "SubscribeURL" : "https://sns.us-west-2.amazonaws.com/?Action=ConfirmSubscription&Token=token-1",
          "Timestamp" : "2012-04-26T20:45:04.751Z",
          "SignatureVersion" : "2",
          "Signature" : "signature-1",
          "SigningCertURL" : "https://sns.us-west-2.amazonaws.com/SimpleNotificationService.pem"
        }
        """.trimIndent()

    private val notificationJson: String =
        """
        {
          "Type" : "Notification",
          "MessageId" : "22b80b92-fdea-4c2c-8f9d-bdfb0c7bf324",
          "TopicArn" : "arn:aws:sns:us-west-2:123456789012:MyTopic",
          "Subject" : "Order created",
          "Message" : "{\"orderId\":\"order-1\"}",
          "Timestamp" : "2012-05-02T00:54:06.655Z",
          "SignatureVersion" : "2",
          "Signature" : "signature-2",
          "SigningCertURL" : "https://sns.us-west-2.amazonaws.com/SimpleNotificationService.pem",
          "UnsubscribeURL" : "https://sns.us-west-2.amazonaws.com/?Action=Unsubscribe&SubscriptionArn=sub-1"
        }
        """.trimIndent()

    private val unsubscribeConfirmationJson: String =
        """
        {
          "Type" : "UnsubscribeConfirmation",
          "MessageId" : "47138184-6831-46b8-8f7c-afc488602d7d",
          "Token" : "token-2",
          "TopicArn" : "arn:aws:sns:us-west-2:123456789012:MyTopic",
          "Message" : "Re-confirm this subscription.",
          "SubscribeURL" : "https://sns.us-west-2.amazonaws.com/?Action=ConfirmSubscription&Token=token-2",
          "Timestamp" : "2012-04-26T20:06:41.581Z",
          "SignatureVersion" : "2",
          "Signature" : "signature-3",
          "SigningCertURL" : "https://sns.us-west-2.amazonaws.com/SimpleNotificationService.pem"
        }
        """.trimIndent()
}
