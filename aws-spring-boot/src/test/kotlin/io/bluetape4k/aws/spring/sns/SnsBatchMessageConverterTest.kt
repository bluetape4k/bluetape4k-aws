package io.bluetape4k.aws.spring.sns

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeSameInstanceAs
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldHaveSize
import io.bluetape4k.assertions.shouldNotContain
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.debug
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.springframework.messaging.Message
import org.springframework.messaging.MessageHeaders
import org.springframework.messaging.support.GenericMessage
import software.amazon.awssdk.services.sns.model.MessageAttributeValue
import java.util.*
import java.util.concurrent.atomic.AtomicInteger

class SnsBatchMessageConverterTest: AbstractSnsTest() {

    companion object: KLoggingChannel()

    @Test
    fun `default converter maps string payload and UUID fallback`() = runTest {
        val id = UUID.fromString("00000000-0000-0000-0000-000000000001")
        val entry = SnsBatchMessageConverter()
            .convert(message("hello", MessageHeaders.ID to id))

        log.debug { "entry=$entry" }
        entry.id shouldBeEqualTo id.toString()
        entry.message shouldBeEqualTo "hello"
    }

    @Test
    fun `explicit serializer handles structured payload without Jackson`() = runTest {
        val converter = SnsBatchMessageConverter { payload -> "encoded:$payload" }
        val entry = converter
            .convert(message(42, MessageHeaders.ID to UUID.randomUUID()))

        log.debug { "entry=$entry" }
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
        log.debug { "entry=$entry" }
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
        log.debug { "error=$error" }
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
        log.debug { "entry=$entry" }
        attributes["later"] = MessageAttributeValue.builder()
            .dataType("String")
            .stringValue("two")
            .build()

        log.debug { "entry=$entry" }
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
        log.debug { "request=$request" }
        request.entries.single().messageGroupId shouldBeEqualTo "orders"
        request.entries.single().messageDeduplicationId shouldBeEqualTo "dedup"

        val error = assertFailsWith<SnsBatchMessageConversionException> {
            SnsBatchMessageConverter().convertAll(
                "arn:aws:sns:us-east-1:000000000000:orders",
                listOf(message("hello", *headers.toList().toTypedArray())),
            )
        }
        log.debug { "error=$error" }
        error.error shouldBeEqualTo SnsBatchMessageConversionError.INVALID_FIFO
    }

    @Test
    fun `preflight rejects oversized collection before serializer`() = runTest {
        val calls = AtomicInteger()
        val converter = SnsBatchMessageConverter {
            calls.incrementAndGet()
            "hello"
        }
        val messages = List(3) { message("$it", MessageHeaders.ID to UUID.randomUUID()) }

        val error = assertFailsWith<SnsBatchMessageConversionException> {
            converter.convertAll(
                "arn:aws:sns:us-east-1:000000000000:orders",
                messages,
                SnsBatchMessageConversionOptions(maxMessages = 2),
            )
        }
        log.debug { "error=$error" }
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
        log.debug { "duplicate=$duplicate" }
        duplicate.error shouldBeEqualTo SnsBatchMessageConversionError.DUPLICATE_ID
        duplicate.toString() shouldNotContain "secret-payload"
        duplicate.cause shouldBeEqualTo null

        val serializerError = assertFailsWith<SnsBatchMessageConversionException> {
            SnsBatchMessageConverter {
                throw IllegalStateException("payload-secret")
            }.convert(
                message(
                    "secret-payload",
                    MessageHeaders.ID to UUID.randomUUID()
                )
            )
        }
        log.debug { "serializerError=$serializerError" }
        serializerError.error shouldBeEqualTo SnsBatchMessageConversionError.SERIALIZATION_FAILED
        serializerError.toString() shouldNotContain "payload-secret"
        serializerError.toString() shouldNotContain "secret-payload"
    }

    @Test
    fun `cancellation keeps identity and converter has no network surface`() = runTest {
        val cancellation = CancellationException("caller-cancelled")
        val actual = assertFailsWith<CancellationException> {
            SnsBatchMessageConverter { throw cancellation }
                .convert(message("hello", MessageHeaders.ID to UUID.randomUUID()))
        }
        actual shouldBeSameInstanceAs cancellation

        val request = SnsBatchMessageConverter().convertAll(
            "arn:aws:sns:us-east-1:000000000000:orders",
            listOf(message("hello", MessageHeaders.ID to UUID.randomUUID())),
        )
        log.debug { "request=$request" }
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
    ): MessageHeaders(headers, id, null)
}
