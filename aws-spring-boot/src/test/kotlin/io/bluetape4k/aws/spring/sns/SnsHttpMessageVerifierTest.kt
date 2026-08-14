package io.bluetape4k.aws.spring.sns

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeSameInstanceAs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import org.junit.jupiter.api.Test
import software.amazon.awssdk.messagemanager.sns.SnsMessageManager

class SnsHttpMessageVerifierTest {

    private val manager = mockk<SnsMessageManager>()
    private val verifier = SnsHttpMessageVerifier(manager)

    @Test
    fun `verify parses supported SNS types before SDK`() {
        listOf(
            notificationJson to "Notification",
            subscriptionConfirmationJson to "SubscriptionConfirmation",
            unsubscribeConfirmationJson to "UnsubscribeConfirmation",
        ).forEach { (json, header) ->
            every { manager.parseMessage(json) } returns mockk(relaxed = true)

            verifier.verify(json, messageTypeHeader = header, expectedTopicArn = topicArn)
                .topicArn shouldBeEqualTo topicArn
        }
    }

    @Test
    fun `manager failure is the same fail closed cause`() {
        val failure = IllegalArgumentException("invalid SNS signature")
        every { manager.parseMessage(notificationJson) } throws failure

        val actual = assertFailsWith<IllegalArgumentException> {
            verifier.verify(notificationJson, messageTypeHeader = "Notification")
        }

        actual shouldBeSameInstanceAs failure
    }

    @Test
    fun `expected topic mismatch rejects before manager`() {
        every { manager.parseMessage(any<String>()) } returns mockk(relaxed = true)

        val actual = assertFailsWith<IllegalArgumentException> {
            verifier.verify(
                notificationJson,
                expectedTopicArn = "arn:aws:sns:us-west-2:123456789012:OtherTopic",
            )
        }

        actual.message.orEmpty() shouldBeEqualTo
            "SNS HTTP message TopicArn does not match expectedTopicArn."
        verify(exactly = 0) { manager.parseMessage(any<String>()) }
    }

    @Test
    fun `parser rejection happens before manager`() {
        val invalidJson = notificationJson.replace(
            "https://sns.us-west-2.amazonaws.com/SimpleNotificationService.pem",
            "http://sns.us-west-2.amazonaws.com/SimpleNotificationService.pem",
        )

        assertFailsWith<IllegalArgumentException> {
            verifier.verify(invalidJson, messageTypeHeader = "Notification")
        }

        verify(exactly = 0) { manager.parseMessage(any<String>()) }
    }

    @Test
    fun `close delegates exactly once`() {
        every { manager.close() } just runs

        verifier.close()
        verifier.close()

        verify(exactly = 1) { manager.close() }
    }

    @Test
    fun `region factory rejects blank region`() {
        SnsHttpMessageVerifier.forRegion("us-east-1").close()

        assertFailsWith<IllegalArgumentException> {
            SnsHttpMessageVerifier.forRegion(" ")
        }
    }

    private val topicArn = "arn:aws:sns:us-west-2:123456789012:MyTopic"

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

    private val unsubscribeConfirmationJson: String =
        subscriptionConfirmationJson
            .replace("SubscriptionConfirmation", "UnsubscribeConfirmation")
            .replace("token-1", "token-2")
}
