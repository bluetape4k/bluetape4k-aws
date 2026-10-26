package io.bluetape4k.aws.ktor.sns

import aws.sdk.kotlin.services.sns.SnsClient
import aws.sdk.kotlin.services.sns.model.ConfirmSubscriptionRequest
import aws.sdk.kotlin.services.sns.model.ConfirmSubscriptionResponse
import aws.sdk.kotlin.services.sns.model.CreateTopicRequest
import aws.sdk.kotlin.services.sns.model.ListTopicsRequest
import aws.sdk.kotlin.services.sns.model.ListTopicsResponse
import aws.sdk.kotlin.services.sns.model.PublishRequest
import aws.sdk.kotlin.services.sns.model.PublishResponse
import aws.sdk.kotlin.services.sns.model.Topic
import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeSameInstanceAs
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldNotContain
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.URI

class SnsKtorTemplateTest {

    private val client = mockk<SnsClient>()

    @BeforeEach
    fun beforeEach() {
        clearMocks(client)
    }

    @Test
    fun `findTopicArn scans pages and carries the next token`() = runTest {
        val requests = mutableListOf<ListTopicsRequest>()
        val responses = ArrayDeque(
            listOf(
                ListTopicsResponse {
                    nextToken = "next-page"
                    topics = listOf(Topic { topicArn = "arn:aws:sns:us-east-1:123456789012:other" })
                },
                ListTopicsResponse {
                    topics = listOf(Topic { topicArn = "arn:aws:sns:us-east-1:123456789012:orders" })
                },
            )
        )
        coEvery { client.listTopics(any<ListTopicsRequest>()) } coAnswers {
            requests += firstArg<ListTopicsRequest>()
            responses.removeFirst()
        }

        val result = SnsKtorTemplate(client).findTopicArn("orders")

        result shouldBeEqualTo "arn:aws:sns:us-east-1:123456789012:orders"
        requests.map { it.nextToken } shouldBeEqualTo listOf(null, "next-page")
        coVerify(exactly = 2) { client.listTopics(any<ListTopicsRequest>()) }
    }

    @Test
    fun `publish maps full string and binary message attributes`() = runTest {
        val request = slot<PublishRequest>()
        val response = PublishResponse { messageId = "published-1" }
        val sourceBytes = byteArrayOf(1, 2, 3)
        val binaryAttribute = SnsMessageAttribute(dataType = "Binary.custom", binaryValue = sourceBytes)
        sourceBytes[0] = 9
        val returnedBytes = requireNotNull(binaryAttribute.binaryValue)
        returnedBytes[1] = 9
        coEvery { client.publish(capture(request)) } returns response

        val result = SnsKtorTemplate(client).publish(
            SnsPublishRequest(
                topicArn = "arn:aws:sns:us-east-1:123456789012:orders.fifo",
                message = "hello",
                subject = "subject",
                messageAttributes = mapOf(
                    "kind" to SnsMessageAttribute(dataType = "String.custom", stringValue = "created"),
                    "payload" to binaryAttribute,
                ),
                messageGroupId = "orders",
                messageDeduplicationId = "event-1",
            )
        )

        result shouldBeSameInstanceAs response
        request.captured.topicArn shouldBeEqualTo "arn:aws:sns:us-east-1:123456789012:orders.fifo"
        request.captured.messageGroupId shouldBeEqualTo "orders"
        request.captured.messageDeduplicationId shouldBeEqualTo "event-1"
        request.captured.messageAttributes?.get("kind")?.dataType shouldBeEqualTo "String.custom"
        request.captured.messageAttributes?.get("kind")?.stringValue shouldBeEqualTo "created"
        request.captured.messageAttributes?.get("payload")?.dataType shouldBeEqualTo "Binary.custom"
        request.captured.messageAttributes?.get("payload")?.binaryValue
            ?.contentEquals(byteArrayOf(1, 2, 3)) shouldBeEqualTo true
    }

    @Test
    fun `publishSms standard options override custom values and retain binary attributes`() = runTest {
        val request = slot<PublishRequest>()
        coEvery { client.publish(capture(request)) } returns PublishResponse { messageId = "sms-1" }

        SnsKtorTemplate(client).publishSms(
            SnsSmsRequest(
                phoneNumber = "+15550100000",
                message = "hello sms",
                smsType = SnsSmsType.TRANSACTIONAL,
                senderId = "Bluetape",
                messageAttributes = mapOf(
                    SnsSmsRequest.SMS_TYPE_ATTRIBUTE to SnsMessageAttribute("String", "Promotional"),
                    "binary" to SnsMessageAttribute("Binary", binaryValue = byteArrayOf(4, 5)),
                ),
            )
        )

        request.captured.phoneNumber shouldBeEqualTo "+15550100000"
        request.captured.messageAttributes?.get(SnsSmsRequest.SMS_TYPE_ATTRIBUTE)?.stringValue shouldBeEqualTo
            "Transactional"
        request.captured.messageAttributes?.get(SnsSmsRequest.SENDER_ID_ATTRIBUTE)?.stringValue shouldBeEqualTo
            "Bluetape"
        request.captured.messageAttributes?.get("binary")?.binaryValue
            ?.contentEquals(byteArrayOf(4, 5)) shouldBeEqualTo true
    }

    @Test
    fun `create topic preserves configured FIFO attributes`() = runTest {
        val request = slot<CreateTopicRequest>()
        coEvery { client.createTopic(capture(request)) } returns
                aws.sdk.kotlin.services.sns.model.CreateTopicResponse { topicArn = "arn:topic:orders.fifo" }

        val arn = SnsKtorTemplate(client).createFifoTopic(
            topicName = "orders.fifo",
            contentBasedDeduplication = false,
            fifoThroughputScope = SnsFifoThroughputScope.MESSAGE_GROUP,
            attributes = mapOf("Environment" to "test", "FifoTopic" to "false"),
        )

        arn shouldBeEqualTo "arn:topic:orders.fifo"
        request.captured.attributes shouldBeEqualTo mapOf(
            "Environment" to "test",
            "FifoTopic" to "true",
            "ContentBasedDeduplication" to "false",
            "FifoThroughputScope" to "MessageGroup",
        )
    }

    @Test
    fun `confirmSubscription maps explicit token and trusted message`() = runTest {
        val requests = mutableListOf<ConfirmSubscriptionRequest>()
        coEvery { client.confirmSubscription(any<ConfirmSubscriptionRequest>()) } coAnswers {
            requests += firstArg<ConfirmSubscriptionRequest>()
            ConfirmSubscriptionResponse { subscriptionArn = "arn:subscription:1" }
        }

        val operations = SnsKtorTemplate(client)
        operations.confirmSubscription("arn:aws:sns:us-east-1:123456789012:orders", "token-1")
        operations.confirmSubscription(
            TrustedSnsHttpMessage.fromVerified(
                SnsHttpMessage(
                    type = SnsHttpMessageType.SUBSCRIPTION_CONFIRMATION,
                    messageId = "message-1",
                    topicArn = "arn:aws:sns:us-east-1:123456789012:orders",
                    message = "confirm",
                    timestamp = "2026-10-20T00:00:00Z",
                    signatureVersion = "1",
                    signature = "signature",
                    signingCertUrl = URI("https://sns.us-east-1.amazonaws.com/cert.pem"),
                    token = "token-2",
                )
            ),
            authenticateOnUnsubscribe = false,
        )

        requests.map { it.token } shouldBeEqualTo listOf("token-1", "token-2")
        requests.map { it.authenticateOnUnsubscribe } shouldBeEqualTo listOf("true", "false")
    }

    @Test
    fun `request constraints reject invalid topic and SMS shapes`() = runTest {
        assertFailsWith<IllegalArgumentException> {
            SnsPublishRequest(topicArn = " ", message = "hello")
        }
        assertFailsWith<IllegalArgumentException> {
            SnsPublishRequest(
                topicArn = "arn:aws:sns:us-east-1:123456789012:standard",
                message = "hello",
                messageGroupId = "g",
            )
        }
        assertFailsWith<IllegalArgumentException> {
            SnsPublishRequest(topicArn = "arn:aws:sns:us-east-1:123456789012:orders.fifo", message = "hello")
        }
        assertFailsWith<IllegalArgumentException> { SnsSmsRequest(phoneNumber = " ", message = "hello") }
        assertFailsWith<IllegalArgumentException> {
            SnsKtorTemplate(mockk(relaxed = true)).createFifoTopic("orders", attributes = emptyMap())
        }
    }

    @Test
    fun `publish cancellation propagates to the suspend client call`() = runTest {
        coEvery { client.publish(any<PublishRequest>()) } coAnswers { awaitCancellation() }
        val job = launch {
            SnsKtorTemplate(client).publish(SnsPublishRequest("arn:aws:sns:us-east-1:123456789012:orders", "hello"))
        }
        runCurrent()
        job.cancel()
        job.isCancelled.shouldBeTrue()
    }

    @Test
    fun `failed SNS call preserves original exception`() = runTest {
        val failure = IllegalStateException("boom")
        coEvery { client.publish(any<PublishRequest>()) } throws failure

        val error = assertFailsWith<IllegalStateException> {
            SnsKtorTemplate(client).publish(SnsPublishRequest("arn:aws:sns:us-east-1:123456789012:orders", "hello"))
        }
        error shouldBeSameInstanceAs failure
    }

    @Test
    fun `message attribute string and binary content are redacted in diagnostics`() {
        val attribute = SnsMessageAttribute("Binary", binaryValue = "secret".encodeToByteArray())
        attribute.toString() shouldNotContain "secret"
    }
}
