package io.bluetape4k.aws.ktor.sns

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.concurrent.completableFutureOf
import io.bluetape4k.concurrent.failedCompletableFutureOf
import io.bluetape4k.coroutines.support.log
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.debug
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import software.amazon.awssdk.core.exception.SdkClientException
import software.amazon.awssdk.services.sns.SnsAsyncClient
import software.amazon.awssdk.services.sns.model.ConfirmSubscriptionRequest
import software.amazon.awssdk.services.sns.model.ConfirmSubscriptionResponse
import software.amazon.awssdk.services.sns.model.CreateTopicRequest
import software.amazon.awssdk.services.sns.model.CreateTopicResponse
import software.amazon.awssdk.services.sns.model.ListTopicsRequest
import software.amazon.awssdk.services.sns.model.ListTopicsResponse
import software.amazon.awssdk.services.sns.model.MessageAttributeValue
import software.amazon.awssdk.services.sns.model.PublishRequest
import software.amazon.awssdk.services.sns.model.PublishResponse
import software.amazon.awssdk.services.sns.model.Topic
import java.util.concurrent.CompletableFuture
import java.util.function.Consumer

class SnsKtorTemplateTest {

    companion object: KLoggingChannel()

    private val client = mockk<SnsAsyncClient>()

    @BeforeEach
    fun beforeEach() {
        clearMocks(client)
    }

    @Test
    fun `findTopicArn scans paged topics`() = runTest {
        val listRequests = mutableListOf<ListTopicsRequest>()
        every {
            client.listTopics(any<Consumer<ListTopicsRequest.Builder>>())
        } answers {
            val builder = ListTopicsRequest.builder()
            firstArg<Consumer<ListTopicsRequest.Builder>>().accept(builder)
            listRequests += builder.build()
            val response = if (listRequests.size == 1) {
                ListTopicsResponse.builder()
                    .topics(Topic.builder().topicArn("arn:aws:sns:us-east-1:000000000000:other").build())
                    .nextToken("next")
                    .build()
            } else {
                ListTopicsResponse.builder()
                    .topics(Topic.builder().topicArn("arn:aws:sns:us-east-1:000000000000:orders").build())
                    .build()
            }
            completableFutureOf(response)
        }

        template(client).findTopicArn("orders") shouldBeEqualTo "arn:aws:sns:us-east-1:000000000000:orders"
        listRequests.map { it.nextToken() } shouldBeEqualTo listOf(null, "next")
    }

    @Test
    fun `publish maps topic request`() = runTest {
        lateinit var capturedConsumer: Consumer<PublishRequest.Builder>
        every {
            client.publish(any<Consumer<PublishRequest.Builder>>())
        } answers {
            capturedConsumer = firstArg()
            completableFutureOf(PublishResponse.builder().messageId("message-1").build())
        }

        val result = template(client).publish(
            SnsPublishRequest(
                topicArn = "arn:aws:sns:us-east-1:000000000000:orders",
                subject = "Order accepted",
                message = "order-json",
            )
        )
        log.debug { "result=$result" }
        result.messageId() shouldBeEqualTo "message-1"

        val requestBuilder = PublishRequest.builder()
        capturedConsumer.accept(requestBuilder)

        val request = requestBuilder.build()
        log.debug { "request=$request" }
        request.topicArn() shouldBeEqualTo "arn:aws:sns:us-east-1:000000000000:orders"
        request.subject() shouldBeEqualTo "Order accepted"
        request.message() shouldBeEqualTo "order-json"
    }

    @Test
    fun `publishSms maps phone number and explicit SMS attributes`() = runTest {
        lateinit var capturedConsumer: Consumer<PublishRequest.Builder>
        every {
            client.publish(any<Consumer<PublishRequest.Builder>>())
        } answers {
            capturedConsumer = firstArg()
            completableFutureOf(PublishResponse.builder().messageId("sms-1").build())
        }

        val result = template(client).publishSms(
            SnsSmsRequest(
                phoneNumber = "+15550100000",
                message = "hello sms",
                smsType = SnsSmsType.TRANSACTIONAL,
                senderId = "BLUETAPE",
                maxPrice = "0.50",
                messageAttributes = mapOf(
                    "custom.attribute" to MessageAttributeValue.builder()
                        .dataType("String")
                        .stringValue("custom")
                        .build()
                ),
            )
        )

        log.debug { "result=$result" }
        result.messageId() shouldBeEqualTo "sms-1"

        val requestBuilder = PublishRequest.builder()
        capturedConsumer.accept(requestBuilder)

        val request = requestBuilder.build()

        log.debug { "request=$request" }
        request.topicArn().shouldBeNull()
        request.phoneNumber() shouldBeEqualTo "+15550100000"
        request.messageAttributes().asStrings() shouldBeEqualTo mapOf(
            "custom.attribute" to "custom",
            SnsSmsRequest.SMS_TYPE_ATTRIBUTE to "Transactional",
            SnsSmsRequest.SENDER_ID_ATTRIBUTE to "BLUETAPE",
            SnsSmsRequest.MAX_PRICE_ATTRIBUTE to "0.50",
        )
    }

    @Test
    fun `confirmSubscription maps explicit token and trusted message`() = runTest {
        val requests = mutableListOf<ConfirmSubscriptionRequest>()
        every {
            client.confirmSubscription(any<Consumer<ConfirmSubscriptionRequest.Builder>>())
        } answers {
            val builder = ConfirmSubscriptionRequest.builder()
            firstArg<Consumer<ConfirmSubscriptionRequest.Builder>>().accept(builder)
            requests += builder.build()
            completableFutureOf(ConfirmSubscriptionResponse.builder().subscriptionArn("sub").build())
        }
        val message =
            TrustedSnsHttpMessage.fromVerified(SnsHttpMessageParser.default().parse(subscriptionConfirmationJson))

        template(client).confirmSubscription(
            topicArn = "arn:aws:sns:us-east-1:000000000000:orders",
            token = "token-1",
            authenticateOnUnsubscribe = false,
        )
        template(client).confirmSubscription(message)

        requests.forEachIndexed { idx, request -> log.debug { "request[$idx]=$request" } }
        requests[0].authenticateOnUnsubscribe() shouldBeEqualTo "false"
        requests[1].topicArn() shouldBeEqualTo "arn:aws:sns:us-east-1:000000000000:orders"
        requests[1].token() shouldBeEqualTo "token-1"

        verify(exactly = 2) {
            client.confirmSubscription(any<Consumer<ConfirmSubscriptionRequest.Builder>>())
        }
    }

    @Test
    fun `request constraints reject invalid topic and SMS shapes`() {
        assertFailsWith<IllegalArgumentException> {
            SnsPublishRequest(
                topicArn = "arn:aws:sns:us-east-1:000000000000:standard",
                message = "m",
                messageGroupId = "g"
            )
        }
        assertFailsWith<IllegalArgumentException> {
            SnsPublishRequest(topicArn = "arn:aws:sns:us-east-1:000000000000:orders.fifo", message = "m")
        }
        assertFailsWith<IllegalArgumentException> {
            SnsSmsRequest(phoneNumber = "", message = "m")
        }
    }

    @Test
    fun `publish cancels the backing future when coroutine is cancelled`() = runTest {
        val future = CompletableFuture<PublishResponse>()
        every { client.publish(any<Consumer<PublishRequest.Builder>>()) } returns future

        val job = launch {
            template(client).publish(
                SnsPublishRequest(
                    topicArn = "arn:aws:sns:us-east-1:000000000000:orders",
                    message = "order",
                )
            )
        }.log("Template")

        runCurrent()

        job.cancel()

        future.isCancelled.shouldBeTrue()
    }

    @Test
    fun `createTopic cancels the backing future when coroutine is cancelled`() = runTest {
        val future = CompletableFuture<CreateTopicResponse>()
        every { client.createTopic(any<Consumer<CreateTopicRequest.Builder>>()) } returns future

        val job = launch {
            template(client).createTopic("orders")
        }.log("Template")

        runCurrent()

        job.cancel()

        future.isCancelled.shouldBeTrue()
    }

    @Test
    fun `createFifoTopic cancels the backing future when coroutine is cancelled`() = runTest {
        val future = CompletableFuture<CreateTopicResponse>()
        every { client.createTopic(any<Consumer<CreateTopicRequest.Builder>>()) } returns future

        val job = launch {
            template(client).createFifoTopic("orders.fifo")
        }.log("Template")

        runCurrent()

        job.cancel()

        future.isCancelled.shouldBeTrue()
    }

    @Test
    fun `findTopicArn cancels paged listing when coroutine is cancelled`() = runTest {
        val future = CompletableFuture<ListTopicsResponse>()
        every { client.listTopics(any<Consumer<ListTopicsRequest.Builder>>()) } returns future

        val job = launch {
            template(client).findTopicArn("orders")
        }.log("Template")

        runCurrent()

        job.cancel()

        future.isCancelled.shouldBeTrue()
    }

    @Test
    fun `publishSms cancels the backing future when coroutine is cancelled`() = runTest {
        val future = CompletableFuture<PublishResponse>()
        every { client.publish(any<Consumer<PublishRequest.Builder>>()) } returns future

        val job = launch {
            template(client).publishSms(SnsSmsRequest(phoneNumber = "+15550100000", message = "sms"))
        }.log("Template")

        runCurrent()

        job.cancel()

        future.isCancelled.shouldBeTrue()
    }

    @Test
    fun `confirmSubscription cancels the backing future when coroutine is cancelled`() = runTest {
        val future = CompletableFuture<ConfirmSubscriptionResponse>()
        every {
            client.confirmSubscription(any<Consumer<ConfirmSubscriptionRequest.Builder>>())
        } returns future

        val job = launch {
            template(client).confirmSubscription(
                topicArn = "arn:aws:sns:us-east-1:000000000000:orders",
                token = "token-1",
            )
        }.log("Template")

        runCurrent()

        job.cancel()

        future.isCancelled.shouldBeTrue()
    }

    @Test
    fun `failed SNS future preserves original exception`() = runTest {
        val failure = SdkClientException.create("boom")
        every {
            client.publish(any<Consumer<PublishRequest.Builder>>())
        } returns failedCompletableFutureOf(failure)

        val error = assertFailsWith<SdkClientException> {
            template(client).publish(
                SnsPublishRequest(
                    topicArn = "arn:aws:sns:us-east-1:000000000000:orders",
                    message = "order",
                )
            )
        }

        error shouldBeEqualTo failure
    }

    private fun template(client: SnsAsyncClient): SnsKtorTemplate =
        SnsKtorTemplate(client)

    private fun Map<String, MessageAttributeValue>.asStrings(): Map<String, String> =
        mapValues { (_, value) ->
            value.dataType() shouldBeEqualTo "String"
            value.stringValue()
        }
}
