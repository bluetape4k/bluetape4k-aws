package io.bluetape4k.aws.spring.sns

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEmpty
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldBeSameInstanceAs
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldHaveSize
import io.bluetape4k.codec.Base58
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.junit.jupiter.api.Test
import software.amazon.awssdk.services.sns.SnsAsyncClient
import software.amazon.awssdk.services.sns.model.ConfirmSubscriptionRequest
import software.amazon.awssdk.services.sns.model.ConfirmSubscriptionResponse
import software.amazon.awssdk.services.sns.model.CreateTopicRequest
import software.amazon.awssdk.services.sns.model.CreateTopicResponse
import software.amazon.awssdk.services.sns.model.MessageAttributeValue
import software.amazon.awssdk.services.sns.model.PublishBatchRequest
import software.amazon.awssdk.services.sns.model.PublishBatchResponse
import software.amazon.awssdk.services.sns.model.PublishRequest
import software.amazon.awssdk.services.sns.model.PublishResponse
import software.amazon.awssdk.services.sns.model.PublishBatchResultEntry
import java.util.concurrent.CompletableFuture
import java.util.function.Consumer

class SnsCoroutinesTemplateTest {

    @Test
    fun `publishBatch uses SDK batch calls and preserves entry fields`() = runTest {
        val client = mockk<SnsAsyncClient>()
        val capturedRequests = mutableListOf<PublishBatchRequest>()
        every { client.publishBatch(any<PublishBatchRequest>()) } answers {
            val request = firstArg<PublishBatchRequest>()
            capturedRequests += request
            CompletableFuture.completedFuture(
                PublishBatchResponse.builder()
                    .successful(
                        request.publishBatchRequestEntries().map { entry ->
                            PublishBatchResultEntry.builder()
                                .id(entry.id())
                                .messageId("message-${entry.id()}")
                                .sequenceNumber(entry.messageGroupId())
                                .build()
                        },
                    )
                    .build(),
            )
        }

        val attributes = mapOf(
            "trace" to MessageAttributeValue.builder()
                .dataType("String")
                .stringValue("trace-${Base58.randomString(16)}")
                .build(),
        )
        val request = SnsPublishBatchRequest(
            topicArn = "arn:aws:sns:us-east-1:000000000000:orders.fifo",
            entries = (1..11).map { index ->
                SnsPublishBatchEntry(
                    id = "entry-$index-${Base58.randomString(16)}",
                    message = "message-$index-${Base58.randomString(16)}",
                    subject = "subject-$index",
                    messageAttributes = attributes,
                    messageGroupId = "group-$index",
                    messageDeduplicationId = "dedup-$index",
                )
            },
        )

        val result = template(client).publishBatch(request, SnsBatchExecutionOptions(maxInFlightBatches = 2))

        capturedRequests shouldHaveSize 2
        capturedRequests.flatMap { it.publishBatchRequestEntries() }.map { it.id() } shouldBeEqualTo
            request.entries.map { it.id }
        val firstEntry = capturedRequests.first().publishBatchRequestEntries().first()
        firstEntry.subject() shouldBeEqualTo "subject-1"
        firstEntry.messageAttributes()["trace"]?.stringValue() shouldBeEqualTo
            attributes.getValue("trace").stringValue()
        firstEntry.messageGroupId() shouldBeEqualTo "group-1"
        firstEntry.messageDeduplicationId() shouldBeEqualTo "dedup-1"
        result.successful.map { it.entryId } shouldBeEqualTo request.entries.map { it.id }
        verify(exactly = 2) { client.publishBatch(any<PublishBatchRequest>()) }
    }

    @Test
    fun `publishBatch empty request avoids SDK call`() = runTest {
        val client = mockk<SnsAsyncClient>()
        val request = SnsPublishBatchRequest(
            topicArn = "arn:aws:sns:us-east-1:000000000000:orders",
            entries = emptyList(),
        )

        val result = template(client).publishBatch(request)

        result.successful.shouldBeEmpty()
        result.failed.shouldBeEmpty()
        verify(exactly = 0) { client.publishBatch(any<PublishBatchRequest>()) }
        verify(exactly = 0) { client.publish(any<PublishRequest>()) }
    }

    @Test
    fun `publishBatch caller cancellation preserves identity and cancels SDK future`() = runTest {
        val client = mockk<SnsAsyncClient>()
        val futureStarted = CompletableDeferred<Unit>()
        val sdkFuture = TrackingPublishBatchFuture()
        every { client.publishBatch(any<PublishBatchRequest>()) } answers {
            futureStarted.complete(Unit)
            sdkFuture
        }
        val request = SnsPublishBatchRequest(
            topicArn = "arn:aws:sns:us-east-1:000000000000:orders",
            entries = listOf(
                SnsPublishBatchEntry(
                    id = "entry-${Base58.randomString(16)}",
                    message = "message-${Base58.randomString(16)}",
                ),
            ),
        )
        val cancellation = CancellationException("caller-${Base58.randomString(16)}")
        val observed = CompletableDeferred<Throwable>()
        val call = launch {
            try {
                template(client).publishBatch(request)
            } catch (cause: Throwable) {
                observed.complete(cause)
            }
        }

        futureStarted.await()
        call.cancel(cancellation)
        call.join()

        val actual = observed.await()
        actual.cause shouldBeSameInstanceAs cancellation
        sdkFuture.cancelled.shouldBeTrue()
        sdkFuture.cancelArgument shouldBeEqualTo false
        sdkFuture.isCancelled.shouldBeTrue()
        verify(exactly = 1) { client.publishBatch(any<PublishBatchRequest>()) }
    }

    @Test
    fun `publishSms maps phone number and explicit SMS attributes`() = runTest {
        val client = mockk<SnsAsyncClient>()
        lateinit var capturedConsumer: Consumer<PublishRequest.Builder>
        val response = PublishResponse.builder().messageId("sms-1").build()

        every {
            client.publish(any<Consumer<PublishRequest.Builder>>())
        } answers {
            capturedConsumer = firstArg()
            CompletableFuture.completedFuture(response)
        }

        val result = template(client).publishSms(
            SnsSmsRequest(
                phoneNumber = "+15550100000",
                message = "hello sms",
                smsType = SnsSmsType.TRANSACTIONAL,
                senderId = "BLUETAPE",
                maxPrice = "0.50",
                originationNumber = "+15550100001",
                entityId = "entity-1",
                templateId = "template-1",
                messageAttributes = mapOf(
                    "custom.attribute" to MessageAttributeValue.builder()
                        .dataType("String")
                        .stringValue("custom")
                        .build()
                ),
            )
        )

        val requestBuilder = PublishRequest.builder()
        capturedConsumer.accept(requestBuilder)
        val request = requestBuilder.build()

        result.messageId() shouldBeEqualTo "sms-1"
        request.topicArn().shouldBeNull()
        request.phoneNumber() shouldBeEqualTo "+15550100000"
        request.message() shouldBeEqualTo "hello sms"
        request.messageAttributes().asStrings() shouldBeEqualTo mapOf(
            "custom.attribute" to "custom",
            SnsSmsRequest.SMS_TYPE_ATTRIBUTE to "Transactional",
            SnsSmsRequest.SENDER_ID_ATTRIBUTE to "BLUETAPE",
            SnsSmsRequest.MAX_PRICE_ATTRIBUTE to "0.50",
            SnsSmsRequest.ORIGINATION_NUMBER_ATTRIBUTE to "+15550100001",
            SnsSmsRequest.ENTITY_ID_ATTRIBUTE to "entity-1",
            SnsSmsRequest.TEMPLATE_ID_ATTRIBUTE to "template-1",
        )
        verify(exactly = 1) { client.publish(any<Consumer<PublishRequest.Builder>>()) }
    }

    @Test
    fun `confirmSubscription maps token and authenticate flag`() = runTest {
        val client = mockk<SnsAsyncClient>()
        lateinit var capturedConsumer: Consumer<ConfirmSubscriptionRequest.Builder>
        val response = ConfirmSubscriptionResponse.builder()
            .subscriptionArn("arn:aws:sns:us-east-1:000000000000:orders:sub")
            .build()

        every {
            client.confirmSubscription(any<Consumer<ConfirmSubscriptionRequest.Builder>>())
        } answers {
            capturedConsumer = firstArg()
            CompletableFuture.completedFuture(response)
        }

        val result = template(client).confirmSubscription(
            topicArn = "arn:aws:sns:us-east-1:000000000000:orders",
            token = "token-1",
            authenticateOnUnsubscribe = false,
        )

        val requestBuilder = ConfirmSubscriptionRequest.builder()
        capturedConsumer.accept(requestBuilder)
        val request = requestBuilder.build()

        result.subscriptionArn() shouldBeEqualTo "arn:aws:sns:us-east-1:000000000000:orders:sub"
        request.topicArn() shouldBeEqualTo "arn:aws:sns:us-east-1:000000000000:orders"
        request.token() shouldBeEqualTo "token-1"
        request.authenticateOnUnsubscribe() shouldBeEqualTo "false"
        verify(exactly = 1) {
            client.confirmSubscription(any<Consumer<ConfirmSubscriptionRequest.Builder>>())
        }
    }

    @Test
    fun `confirmSubscription from HTTP message delegates topic and token`() = runTest {
        val client = mockk<SnsAsyncClient>()
        lateinit var capturedConsumer: Consumer<ConfirmSubscriptionRequest.Builder>
        val response = ConfirmSubscriptionResponse.builder()
            .subscriptionArn("arn:aws:sns:us-east-1:000000000000:orders:sub")
            .build()

        every {
            client.confirmSubscription(any<Consumer<ConfirmSubscriptionRequest.Builder>>())
        } answers {
            capturedConsumer = firstArg()
            CompletableFuture.completedFuture(response)
        }

        val message = SnsHttpMessageParser.parse(subscriptionConfirmationJson)

        template(client).confirmSubscription(message)

        val requestBuilder = ConfirmSubscriptionRequest.builder()
        capturedConsumer.accept(requestBuilder)
        val request = requestBuilder.build()

        request.topicArn() shouldBeEqualTo "arn:aws:sns:us-east-1:000000000000:orders"
        request.token() shouldBeEqualTo "token-1"
        request.authenticateOnUnsubscribe() shouldBeEqualTo "true"
    }

    @Test
    fun `confirmSubscription from notification message fails fast`() = runTest {
        val error = assertFailsWith<IllegalArgumentException> {
            template(mockk(relaxed = true)).confirmSubscription(SnsHttpMessageParser.parse(notificationJson))
        }

        error.message.orEmpty() shouldBeEqualTo
            "SNS HTTP message type Notification cannot confirm a subscription."
    }

    @Test
    fun `findTopicArn delegates to injected resolver`() = runTest {
        val client = mockk<SnsAsyncClient>()
        val resolver = mockk<SnsTopicArnResolver>()
        val arn = "arn:aws:sns:us-east-1:000000000000:orders"
        coEvery { resolver.findTopicArn("orders") } returns arn

        val result = SnsCoroutinesTemplate(
            snsAsyncClient = client,
            properties = SnsProperties(region = "us-east-1"),
            topicArnResolver = resolver,
            batchExecutionStrategy = DefaultSnsBatchExecutionStrategy,
        ).findTopicArn("orders")

        result shouldBeEqualTo arn
        coVerify(exactly = 1) { resolver.findTopicArn("orders") }
        verify(exactly = 0) {
            client.listTopics(any<Consumer<software.amazon.awssdk.services.sns.model.ListTopicsRequest.Builder>>())
        }
    }

    @Test
    fun `findTopicArn accepts explicit arn through resolver`() = runTest {
        val client = mockk<SnsAsyncClient>()
        val resolver = mockk<SnsTopicArnResolver>()
        val arn = "arn:aws:sns:us-east-1:000000000000:orders"
        coEvery { resolver.resolve(arn) } returns arn

        val result = SnsCoroutinesTemplate(
            snsAsyncClient = client,
            properties = SnsProperties(region = "us-east-1"),
            topicArnResolver = resolver,
            batchExecutionStrategy = DefaultSnsBatchExecutionStrategy,
        ).findTopicArn(arn)

        result shouldBeEqualTo arn
        coVerify(exactly = 1) { resolver.resolve(arn) }
        verify(exactly = 0) {
            client.listTopics(any<Consumer<software.amazon.awssdk.services.sns.model.ListTopicsRequest.Builder>>())
        }
    }

    @Test
    fun `createTopic invalidates resolver after successful create`() = runTest {
        val client = mockk<SnsAsyncClient>()
        val resolver = mockk<SnsTopicArnResolver>(relaxed = true)
        val arn = "arn:aws:sns:us-east-1:000000000000:orders"
        every { client.createTopic(any<Consumer<CreateTopicRequest.Builder>>()) } returns
            CompletableFuture.completedFuture(CreateTopicResponse.builder().topicArn(arn).build())

        val result = SnsCoroutinesTemplate(
            snsAsyncClient = client,
            properties = SnsProperties(region = "us-east-1"),
            topicArnResolver = resolver,
            batchExecutionStrategy = DefaultSnsBatchExecutionStrategy,
        ).createTopic("orders")

        result shouldBeEqualTo arn
        verify(exactly = 1) { resolver.invalidate("orders") }
    }

    private fun template(client: SnsAsyncClient): SnsCoroutinesTemplate =
        SnsCoroutinesTemplate(
            snsAsyncClient = client,
            properties = SnsProperties(region = "us-east-1"),
        )

    private val subscriptionConfirmationJson: String =
        """
        {
          "Type" : "SubscriptionConfirmation",
          "MessageId" : "165545c9-2a5c-472c-8df2-7ff2be2b3b1b",
          "Token" : "token-1",
          "TopicArn" : "arn:aws:sns:us-east-1:000000000000:orders",
          "Message" : "Confirm this subscription.",
          "SubscribeURL" : "https://sns.us-east-1.amazonaws.com/?Action=ConfirmSubscription&Token=token-1",
          "Timestamp" : "2012-04-26T20:45:04.751Z",
          "SignatureVersion" : "2",
          "Signature" : "signature-1",
          "SigningCertURL" : "https://sns.us-east-1.amazonaws.com/SimpleNotificationService.pem"
        }
        """.trimIndent()

    private val notificationJson: String =
        """
        {
          "Type" : "Notification",
          "MessageId" : "22b80b92-fdea-4c2c-8f9d-bdfb0c7bf324",
          "TopicArn" : "arn:aws:sns:us-east-1:000000000000:orders",
          "Message" : "notification",
          "Timestamp" : "2012-05-02T00:54:06.655Z",
          "SignatureVersion" : "2",
          "Signature" : "signature-2",
          "SigningCertURL" : "https://sns.us-east-1.amazonaws.com/SimpleNotificationService.pem",
          "UnsubscribeURL" : "https://sns.us-east-1.amazonaws.com/?Action=Unsubscribe&SubscriptionArn=sub-1"
        }
        """.trimIndent()

    private fun Map<String, MessageAttributeValue>.asStrings(): Map<String, String> =
        mapValues { (_, value) ->
            value.dataType() shouldBeEqualTo "String"
            value.stringValue()
        }

    private class TrackingPublishBatchFuture : CompletableFuture<PublishBatchResponse>() {
        var cancelled: Boolean = false
            private set
        var cancelArgument: Boolean? = null
            private set

        override fun cancel(mayInterruptIfRunning: Boolean): Boolean {
            cancelled = true
            cancelArgument = mayInterruptIfRunning
            return super.cancel(mayInterruptIfRunning)
        }
    }
}
