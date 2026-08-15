package io.bluetape4k.aws.sns

import io.bluetape4k.aws.sns.model.subscribeRequest
import io.bluetape4k.aws.sns.model.publishBatchRequestEntryOf
import io.bluetape4k.aws.sns.model.publishBatchRequestOf
import io.bluetape4k.codec.Base58
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.support.hashOf
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldNotBeEmpty
import io.bluetape4k.assertions.assertFailsWith
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import software.amazon.awssdk.services.sns.model.SubscribeResponse
import software.amazon.awssdk.services.sns.model.MessageAttributeValue
import java.time.Duration
import software.amazon.awssdk.awscore.AwsRequestOverrideConfiguration

@Execution(ExecutionMode.SAME_THREAD)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class SnsClientTest: AbstractSnsTest() {

    companion object: KLogging() {
        private val TOPIC_NAME = Base58.randomString(8).lowercase() + ".fifo"
    }

    private lateinit var topicArn: String
    private lateinit var subscriptionArn: String
    private lateinit var token: String

    val phoneNumber = "+821088885555"

    @Test
    @Order(1)
    fun `create topic`() {
        val response = client.createFIFOTopic(TOPIC_NAME)

        topicArn = response.topicArn()
        topicArn.shouldNotBeEmpty()
        log.debug { "topic name=$TOPIC_NAME, topicArn=$topicArn" }
    }

    @Test
    @Order(2)
    fun `subscribe topic`() {
        val request = subscribeRequest {
            protocol("sms")
            endpoint(phoneNumber)
            returnSubscriptionArn(true)
            topicArn(topicArn)
        }

        val response: SubscribeResponse = client.subscribe(request)

        subscriptionArn = response.subscriptionArn()
        subscriptionArn.shouldNotBeEmpty()
        log.debug { "subscriptionArn=$subscriptionArn" }

        response.sdkHttpResponse().statusCode() shouldBeEqualTo 200
        response.responseMetadata()
    }

    @Disabled("#100 — SNS SMS token is delivered out-of-band to subscriber; no emulator support")
    @Test
    @Order(3)
    fun `confirm subscription`() {
        val response = client.confirmSubscription {
            it.token("EXAMPLE-TOKEN")
            it.topicArn(topicArn)
        }

        log.debug { "Subscription confirmed: ${response.sdkHttpResponse().statusCode()}" }
        log.debug { "SubscriptionArn: ${response.subscriptionArn()}" }
    }

    @Test
    @Order(4)
    fun `list subscriptions`() {
        val response = client.listSubscriptions {}
        response.subscriptions().forEach {
            log.debug { "subscriptionArn=${it.subscriptionArn()}" }
        }
    }

    @Test
    @Order(5)
    fun `check opt out`() {
        assumeFlociSupports("SNS CheckIfPhoneNumberIsOptedOut")

        val result = client.checkIfPhoneNumberIsOptedOut {
            it.phoneNumber(phoneNumber)
        }
        log.debug { "${result.isOptedOut} $phoneNumber has opted out of receiving sns" }
        result.sdkHttpResponse().statusCode() shouldBeEqualTo 200
    }

    @Test
    @Order(6)
    fun `send message`() {
        val response = client.publish {
            val message = "Hello, World!"
            it.subject("[TEST]")
            it.message(message)
            it.topicArn(topicArn)
            it.messageGroupId("partitionKey")
            it.messageDeduplicationId(hashOf(topicArn, message).toString())
        }

        response.messageId().shouldNotBeEmpty()
        log.debug { "response=$response" }
    }

    @Test
    @Order(7)
    fun `publish batch validates count ids and topic before sdk call`() {
        val validEntry = publishBatchRequestEntryOf("entry-1", "message-1")
        val elevenEntries = List(11) { index ->
            publishBatchRequestEntryOf("entry-$index", "message-$index")
        }

        assertFailsWith<IllegalArgumentException> { client.publishBatch(" ", listOf(validEntry)) }
        assertFailsWith<IllegalArgumentException> { client.publishBatch(topicArn, emptyList()) }
        assertFailsWith<IllegalArgumentException> { client.publishBatch(topicArn, elevenEntries) }
        assertFailsWith<IllegalArgumentException> {
            client.publishBatch(topicArn, listOf(validEntry, validEntry))
        }
        assertFailsWith<IllegalArgumentException> {
            client.publishBatch(
                topicArn,
                listOf(publishBatchRequestEntryOf(" ", "message-1")),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            publishBatchRequestEntryOf("entry-1", "message-1") { id(" ") }
        }
        assertFailsWith<IllegalArgumentException> {
            publishBatchRequestOf(topicArn, listOf(validEntry)) { topicArn(" ") }
        }
    }

    @Test
    @Order(8)
    fun `publish batch request helpers preserve entries attributes fifo and override`() {
        val attributes = mapOf(
            "trace" to MessageAttributeValue.builder().dataType("String").stringValue("trace-1").build(),
        )
        val entry = publishBatchRequestEntryOf(
            id = "entry-1",
            message = "message-1",
            messageAttributes = attributes,
            messageDeduplicationId = "dedup-1",
            messageGroupId = "group-1",
        )
        val override = AwsRequestOverrideConfiguration.builder()
            .apiCallTimeout(Duration.ofSeconds(1))
            .build()
        val request = publishBatchRequestOf(
            topicArn = topicArn,
            entries = listOf(entry),
            overrideConfiguration = override,
        )

        request.topicArn() shouldBeEqualTo topicArn
        request.publishBatchRequestEntries() shouldBeEqualTo listOf(entry)
        request.overrideConfiguration().orElseThrow() shouldBeEqualTo override
        request.publishBatchRequestEntries().single().messageAttributes() shouldBeEqualTo attributes
        request.publishBatchRequestEntries().single().messageGroupId() shouldBeEqualTo "group-1"
        request.publishBatchRequestEntries().single().messageDeduplicationId() shouldBeEqualTo "dedup-1"
    }
}
