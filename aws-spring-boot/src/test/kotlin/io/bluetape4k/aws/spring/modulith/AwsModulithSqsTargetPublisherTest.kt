package io.bluetape4k.aws.spring.modulith

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldNotContain
import io.bluetape4k.aws.spring.sqs.SqsFullRequestOperations
import io.bluetape4k.aws.spring.sqs.SqsOperations
import io.bluetape4k.aws.spring.sqs.SqsSendRequest
import io.bluetape4k.codec.encodeHexString
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import software.amazon.awssdk.services.sqs.model.SendMessageResponse
import java.security.MessageDigest

class AwsModulithSqsTargetPublisherTest {

    private companion object: KLoggingChannel() {
        const val HOSTILE_MARKER = "secret-value:event-id:header-value:arn:request-response"

        val sha256Digest: MessageDigest = MessageDigest.getInstance("SHA-256")
        private fun sha256(value: String): String = sha256Digest
            .digest(value.toByteArray())
            .encodeHexString()
    }

    private val operations = mockk<SqsFullRequestOperations>(relaxed = true)

    @BeforeEach
    fun beforeEach() {
        clearMocks(operations)
    }

    @Test
    fun `markerless operations are rejected before queue lookup`() {
        val operations = mockk<SqsOperations>()

        assertFailsWith<AwsModulithConfigurationException> {
            AwsModulithSqsTargetPublisher(operations, setOf("orders"))
        }
    }

    @Test
    fun `name-only queue is resolved and full request preserves attributes and fifo fields`() = runTest {
        val request = slot<SqsSendRequest>()

        coEvery {
            operations.getQueueUrl("orders.fifo")
        } returns "https://sqs.us-east-1.amazonaws.com/000000000000/orders.fifo"

        coEvery {
            operations.send(capture(request))
        } returns SendMessageResponse.builder().messageId("provider-message").build()

        val result = publisher(operations)
            .publish(command(destination = "orders.fifo", routingKey = "customer-42"))

        result.service shouldBeEqualTo AwsModulithTargetService.SQS
        result.targetAlias shouldBeEqualTo "orders"
        result.providerMessageIdPresent.shouldBeTrue()
        request.captured.queueUrl shouldBeEqualTo "https://sqs.us-east-1.amazonaws.com/000000000000/orders.fifo"
        request.captured.body shouldBeEqualTo "encoded-body"
        request.captured.messageGroupId shouldBeEqualTo "customer-42"
        request.captured.messageDeduplicationId shouldBeEqualTo sha256("event-42")
        request.captured.messageAttributes.mapValues { it.value.stringValue() } shouldBeEqualTo
                mapOf(
                    "tenant" to "tenant-1",
                    DefaultAwsModulithEventCodec.SYSTEM_EVENT_ID to "event-42",
                    DefaultAwsModulithEventCodec.SYSTEM_EVENT_TYPE to "order.placed",
                )

        coVerify(exactly = 1) { operations.getQueueUrl("orders.fifo") }
        coVerify(exactly = 1) { operations.send(any<io.bluetape4k.aws.spring.sqs.SqsSendRequest>()) }
    }

    @Test
    fun `concurrent first resolution is single-flight and failed entry is evicted`() = runTest {
        val lookupStarted = CompletableDeferred<Unit>()
        val releaseLookup = CompletableDeferred<Unit>()

        coEvery {
            operations.getQueueUrl("orders")
        } coAnswers {
            lookupStarted.complete(Unit)
            releaseLookup.await()
            "https://sqs.us-east-1.amazonaws.com/000000000000/orders"
        }
        coEvery {
            operations.send(any<SqsSendRequest>())
        } returns SendMessageResponse.builder().build()

        val publisher = publisher(operations)
        val jobs = List(32) {
            async {
                publisher.publish(command(destination = "orders"))
            }
        }
        lookupStarted.await()

        coVerify(exactly = 1) { operations.getQueueUrl("orders") }
        releaseLookup.complete(Unit)
        jobs.awaitAll()

        coVerify(exactly = 1) { operations.getQueueUrl("orders") }

        val failing = mockk<SqsFullRequestOperations>()
        var calls = 0
        coEvery {
            failing.getQueueUrl("retryable")
        } coAnswers {
            calls += 1
            if (calls == 1) error("lookup failed")
            "https://sqs.us-east-1.amazonaws.com/000000000000/retryable"
        }
        coEvery {
            failing.send(any<SqsSendRequest>())
        } returns SendMessageResponse.builder().build()

        val retryingPublisher = publisher(failing, aliases = setOf("retryable"))

        assertFailsWith<AwsModulithTargetResolutionException> {
            retryingPublisher.publish(command(targetAlias = "retryable", destination = "retryable"))
        }
        retryingPublisher.publish(command(targetAlias = "retryable", destination = "retryable"))
        calls shouldBeEqualTo 2
    }

    @Test
    fun `raw send failure is replaced with a bounded publish exception`() = runTest {
        coEvery {
            operations.getQueueUrl("orders")
        } returns "https://sqs.us-east-1.amazonaws.com/000000000000/orders"
        coEvery {
            operations.send(any<io.bluetape4k.aws.spring.sqs.SqsSendRequest>())
        } throws IllegalStateException(HOSTILE_MARKER)

        val failure = assertFailsWith<AwsModulithPublishException> {
            publisher(operations).publish(command(destination = "orders"))
        }

        failure.cause.shouldBeNull()
        failure.message shouldNotContain HOSTILE_MARKER
    }

    @Test
    fun `cancelled resolution entry is evicted for the next publication`() = runTest {
        val lookupStarted = CompletableDeferred<Unit>()
        val cancelLookup = CompletableDeferred<Unit>()
        var calls = 0

        coEvery {
            operations.getQueueUrl("retryable")
        } coAnswers {
            calls += 1
            if (calls == 1) {
                lookupStarted.complete(Unit)
                cancelLookup.await()
            }
            "https://sqs.us-east-1.amazonaws.com/000000000000/retryable"
        }
        coEvery {
            operations.send(any<SqsSendRequest>())
        } returns SendMessageResponse.builder().build()

        val publisher = publisher(operations, aliases = setOf("retryable"))

        val first = async {
            publisher.publish(command(targetAlias = "retryable", destination = "retryable"))
        }

        lookupStarted.await()
        first.cancelAndJoin()

        publisher.publish(command(targetAlias = "retryable", destination = "retryable"))
        calls shouldBeEqualTo 2
    }

    @Test
    fun `missing queue arn url and standard routing key are rejected`() = runTest {
        val publisher = publisher(operations)

        assertFailsWith<IllegalArgumentException> {
            publisher.publish(command(destination = "arn:aws:sqs:us-east-1:000000000000:orders"))
        }
        assertFailsWith<IllegalArgumentException> {
            publisher.publish(command(destination = "https://sqs.us-east-1.amazonaws.com/000000000000/orders"))
        }
        assertFailsWith<IllegalArgumentException> {
            publisher.publish(command(destination = "orders", routingKey = "unexpected"))
        }

        coVerify(exactly = 0) { operations.getQueueUrl(any()) }
        coVerify(exactly = 0) { operations.send(any<SqsSendRequest>()) }

        coEvery {
            operations.getQueueUrl("orders")
        } returns "https://sqs.us-east-1.amazonaws.com/000000000000/orders"
        coEvery {
            operations.send(any<io.bluetape4k.aws.spring.sqs.SqsSendRequest>())
        } returns SendMessageResponse.builder().build()

        val result = publisher.publish(command(destination = "orders"))
        result.providerMessageIdPresent.shouldBeFalse()
    }

    private fun publisher(
        operations: SqsFullRequestOperations,
        aliases: Set<String> = setOf("orders"),
    ) = AwsModulithSqsTargetPublisher(operations, aliases)

    private fun command(
        targetAlias: String = "orders",
        destination: String,
        routingKey: String? = null,
    ) = AwsModulithPublishCommand(
        targetAlias = targetAlias,
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
}
