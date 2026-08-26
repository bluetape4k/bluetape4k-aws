package io.bluetape4k.aws.spring.modulith

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import software.amazon.awssdk.services.sns.model.PublishResponse
import java.security.MessageDigest

class AwsModulithSnsTargetPublisherTest {

    @Test
    fun `name-only destination is resolved once before publishing the complete request`() = runTest {
        val operations = mockk<io.bluetape4k.aws.spring.sns.SnsOperations>()
        val request = slot<io.bluetape4k.aws.spring.sns.SnsPublishRequest>()
        coEvery { operations.findTopicArn("orders.fifo") } returns
            "arn:aws:sns:us-east-1:000000000000:orders.fifo"
        coEvery { operations.publish(capture(request)) } returns
            PublishResponse.builder().messageId("provider-message").build()

        val result = publisher(operations).publish(command(destination = "orders.fifo", routingKey = "customer-42"))

        result.service shouldBeEqualTo AwsModulithTargetService.SNS
        result.targetAlias shouldBeEqualTo "orders"
        result.providerMessageIdPresent.shouldBeTrue()
        request.captured.topicArn shouldBeEqualTo "arn:aws:sns:us-east-1:000000000000:orders.fifo"
        request.captured.message shouldBeEqualTo "encoded-body"
        request.captured.messageGroupId shouldBeEqualTo "customer-42"
        request.captured.messageDeduplicationId shouldBeEqualTo sha256("event-42")
        request.captured.messageAttributes.mapValues { it.value.stringValue() } shouldBeEqualTo
            mapOf(
                "tenant" to "tenant-1",
                DefaultAwsModulithEventCodec.SYSTEM_EVENT_ID to "event-42",
                DefaultAwsModulithEventCodec.SYSTEM_EVENT_TYPE to "order.placed",
            )
        coVerify(exactly = 1) { operations.findTopicArn("orders.fifo") }
        coVerify(exactly = 1) { operations.publish(any()) }
    }

    @Test
    fun `missing topic fails before publish and response id is reduced to presence`() = runTest {
        val operations = mockk<io.bluetape4k.aws.spring.sns.SnsOperations>()
        coEvery { operations.findTopicArn("missing") } returns null

        assertFailsWith<AwsModulithTargetResolutionException> {
            publisher(operations).publish(command(destination = "missing"))
        }
        coVerify(exactly = 1) { operations.findTopicArn("missing") }
        coVerify(exactly = 0) { operations.publish(any()) }

        coEvery { operations.findTopicArn("orders") } returns
            "arn:aws:sns:us-east-1:000000000000:orders"
        coEvery { operations.publish(any()) } returns PublishResponse.builder().build()
        val result = publisher(operations).publish(command(destination = "orders"))
        result.providerMessageIdPresent.shouldBeFalse()
    }

    @Test
    fun `raw resolution and publish failures are replaced with bounded exceptions`() = runTest {
        val operations = mockk<io.bluetape4k.aws.spring.sns.SnsOperations>()
        coEvery { operations.findTopicArn("orders") } throws IllegalStateException(HOSTILE_MARKER)

        val resolution = assertFailsWith<AwsModulithTargetResolutionException> {
            publisher(operations).publish(command(destination = "orders"))
        }
        resolution.cause shouldBeEqualTo null
        resolution.message.orEmpty().contains(HOSTILE_MARKER).shouldBeFalse()

        coEvery { operations.findTopicArn("orders") } returns
            "arn:aws:sns:us-east-1:000000000000:orders"
        coEvery { operations.publish(any()) } throws IllegalStateException(HOSTILE_MARKER)
        val publish = assertFailsWith<AwsModulithPublishException> {
            publisher(operations).publish(command(destination = "orders"))
        }
        publish.cause shouldBeEqualTo null
        publish.message.orEmpty().contains(HOSTILE_MARKER).shouldBeFalse()
    }

    @Test
    fun `arn url and standard routing key are rejected as configuration input`() = runTest {
        val operations = mockk<io.bluetape4k.aws.spring.sns.SnsOperations>()
        val publisher = publisher(operations)

        assertFailsWith<IllegalArgumentException> {
            publisher.publish(command(destination = "arn:aws:sns:us-east-1:000000000000:orders"))
        }
        assertFailsWith<IllegalArgumentException> {
            publisher.publish(command(destination = "https://sns.us-east-1.amazonaws.com/000000000000/orders"))
        }
        assertFailsWith<IllegalArgumentException> {
            publisher.publish(command(destination = "orders", routingKey = "unexpected"))
        }
        coVerify(exactly = 0) { operations.findTopicArn(any()) }
    }

    private fun publisher(operations: io.bluetape4k.aws.spring.sns.SnsOperations) =
        AwsModulithSnsTargetPublisher(operations)

    private fun command(
        destination: String,
        routingKey: String? = null,
    ) = AwsModulithPublishCommand(
        targetAlias = "orders",
        destination = destination,
        routingKey = routingKey,
        eventId = "event-42",
        encoded = AwsModulithEncodedEvent(
            body = "encoded-body",
            messageAttributes = mapOf(
                "tenant" to "tenant-1",
                DefaultAwsModulithEventCodec.SYSTEM_EVENT_ID to "event-42",
                DefaultAwsModulithEventCodec.SYSTEM_EVENT_TYPE to "order.placed",
            ),
        ),
    )

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray())
        .joinToString(separator = "") { "%02x".format(it) }

    private companion object {
        const val HOSTILE_MARKER = "secret-value:event-id:header-value:arn:request-response"
    }
}
