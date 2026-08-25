package io.bluetape4k.aws.spring.sns

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeSameInstanceAs
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldHaveSize
import io.bluetape4k.assertions.shouldNotContain
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.springframework.messaging.Message
import org.springframework.messaging.MessageHeaders
import org.springframework.messaging.support.GenericMessage
import software.amazon.awssdk.services.sns.model.MessageAttributeValue
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

class SnsBatchMessageConverterTest {

    @Test
    fun `default converter maps string payload and UUID fallback`() = runTest {
        val id = UUID.fromString("00000000-0000-0000-0000-000000000001")
        val entry = SnsBatchMessageConverter().convert(message("hello", MessageHeaders.ID to id))

        entry.id shouldBeEqualTo id.toString()
        entry.message shouldBeEqualTo "hello"
    }

    @Test
    fun `explicit serializer handles structured payload without Jackson`() = runTest {
        val converter = SnsBatchMessageConverter(SnsPayloadSerializer { payload -> "encoded:$payload" })
        val entry = converter.convert(message(42, MessageHeaders.ID to UUID.randomUUID()))

        entry.message shouldBeEqualTo "encoded:42"
    }

    @Test
    fun `explicit message id wins and wrong type is rejected`() = runTest {
        val fallback = UUID.randomUUID()
        val explicit = UUID.randomUUID()
        val entry = SnsBatchMessageConverter().convert(
            message(
                "hello",
                MessageHeaders.ID to fallback,
                SnsBatchMessageHeaders.MESSAGE_ID to explicit,
            )
        )
        entry.id shouldBeEqualTo explicit.toString()

        val error = assertFailsWith<SnsBatchMessageConversionException> {
            SnsBatchMessageConverter().convert(
                message(
                    "hello",
                    MessageHeaders.ID to fallback,
                    SnsBatchMessageHeaders.MESSAGE_ID to "not-a-uuid",
                )
            )
        }
        error.error shouldBeEqualTo SnsBatchMessageConversionError.INVALID_ID_TYPE
        error.field shouldBeEqualTo SnsBatchMessageConversionField.MESSAGE_ID
    }

    @Test
    fun `subject and typed attributes are mapped defensively`() = runTest {
        val attributes = mutableMapOf(
            "trace" to MessageAttributeValue.builder().dataType("String").stringValue("one").build(),
        )
        val entry = SnsBatchMessageConverter().convert(
            message(
                "hello",
                MessageHeaders.ID to UUID.randomUUID(),
                SnsBatchMessageHeaders.SUBJECT to "subject",
                SnsBatchMessageHeaders.MESSAGE_ATTRIBUTES to attributes,
            )
        )
        attributes["later"] = MessageAttributeValue.builder().dataType("String").stringValue("two").build()

        entry.subject shouldBeEqualTo "subject"
        entry.messageAttributes.keys shouldBeEqualTo setOf("trace")
    }

    @Test
    fun `FIFO headers are accepted only for FIFO topic`() = runTest {
        val headers = mapOf(
            MessageHeaders.ID to UUID.randomUUID(),
            SnsBatchMessageHeaders.MESSAGE_GROUP_ID to "orders",
            SnsBatchMessageHeaders.MESSAGE_DEDUPLICATION_ID to "dedup",
        )
        val request = SnsBatchMessageConverter().convertAll(
            "arn:aws:sns:us-east-1:000000000000:orders.fifo",
            listOf(message("hello", *headers.toList().toTypedArray())),
        )
        request.entries.single().messageGroupId shouldBeEqualTo "orders"
        request.entries.single().messageDeduplicationId shouldBeEqualTo "dedup"

        val error = assertFailsWith<SnsBatchMessageConversionException> {
            SnsBatchMessageConverter().convertAll(
                "arn:aws:sns:us-east-1:000000000000:orders",
                listOf(message("hello", *headers.toList().toTypedArray())),
            )
        }
        error.error shouldBeEqualTo SnsBatchMessageConversionError.INVALID_FIFO
    }

    @Test
    fun `preflight rejects oversized collection before serializer`() = runTest {
        val calls = AtomicInteger()
        val converter = SnsBatchMessageConverter(SnsPayloadSerializer {
            calls.incrementAndGet()
            "hello"
        })
        val messages = (1..3).map { message("$it", MessageHeaders.ID to UUID.randomUUID()) }

        val error = assertFailsWith<SnsBatchMessageConversionException> {
            converter.convertAll(
                "arn:aws:sns:us-east-1:000000000000:orders",
                messages,
                SnsBatchMessageConversionOptions(maxMessages = 2),
            )
        }
        error.error shouldBeEqualTo SnsBatchMessageConversionError.TOO_MANY_MESSAGES
        calls.get() shouldBeEqualTo 0
    }

    @Test
    fun `duplicate IDs and serializer errors are safe`() = runTest {
        val id = UUID.randomUUID()
        val duplicate = assertFailsWith<SnsBatchMessageConversionException> {
            SnsBatchMessageConverter().convertAll(
                "arn:aws:sns:us-east-1:000000000000:orders",
                listOf(
                    message("secret-payload", MessageHeaders.ID to id),
                    message("secret-payload", MessageHeaders.ID to id),
                ),
            )
        }
        duplicate.error shouldBeEqualTo SnsBatchMessageConversionError.DUPLICATE_ID
        duplicate.toString() shouldNotContain "secret-payload"
        duplicate.cause shouldBeEqualTo null

        val serializerError = assertFailsWith<SnsBatchMessageConversionException> {
            SnsBatchMessageConverter(SnsPayloadSerializer { throw IllegalStateException("payload-secret") })
                .convert(message("secret-payload", MessageHeaders.ID to UUID.randomUUID()))
        }
        serializerError.error shouldBeEqualTo SnsBatchMessageConversionError.SERIALIZATION_FAILED
        serializerError.toString() shouldNotContain "payload-secret"
        serializerError.toString() shouldNotContain "secret-payload"
    }

    @Test
    fun `cancellation keeps identity and converter has no network surface`() = runTest {
        val cancellation = CancellationException("caller-cancelled")
        val actual = assertFailsWith<CancellationException> {
            SnsBatchMessageConverter(SnsPayloadSerializer { throw cancellation })
                .convert(message("hello", MessageHeaders.ID to UUID.randomUUID()))
        }
        actual shouldBeSameInstanceAs cancellation

        val request = SnsBatchMessageConverter().convertAll(
            "arn:aws:sns:us-east-1:000000000000:orders",
            listOf(message("hello", MessageHeaders.ID to UUID.randomUUID())),
        )
        request.entries shouldHaveSize 1
        request.topicArn.endsWith(":orders").shouldBeTrue()
    }

    private fun message(payload: Any, vararg headers: Pair<String, Any>): Message<Any> {
        val values = headers.toMap()
        val id = values[MessageHeaders.ID] as? UUID
        return if (id != null) {
            GenericMessage(payload, FixedHeaders(id, values - MessageHeaders.ID))
        } else {
            GenericMessage(payload, MessageHeaders(values))
        }
    }

    private class FixedHeaders(
        id: UUID,
        headers: Map<String, Any>,
    ) : MessageHeaders(headers, id, null)
}
