package io.bluetape4k.aws.spring.modulith

import io.bluetape4k.assertions.assertFailsWith
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.modulith.events.core.EventSerializer
import tools.jackson.databind.ObjectMapper
import java.nio.charset.StandardCharsets
import java.util.concurrent.CancellationException

class AwsModulithEventCodecTest {

    @Test
    fun `encode and decode round trip uses the registry and system attributes`() {
        val event = OrderPlaced("evt-1", "acme")
        val serializer = RecordingSerializer(payload = "{\"orderId\":\"42\"}", decoded = event)
        val codec = codec(serializer)

        val encoded = codec.encode(event)
        val decoded = codec.decode(encoded.body, encoded.messageAttributes)

        assertEquals(event, decoded)
        assertEquals(1, serializer.serializeCalls)
        assertEquals(
            mapOf(
                "tenant" to "acme",
                "bt4k-event-id" to "evt-1",
                "bt4k-event-type" to "order.placed",
                "bt4k-event-version" to "1",
            ),
            encoded.messageAttributes,
        )
        assertTrue(encoded.body.contains("\"payload\":\"{\\\"orderId\\\":\\\"42\\\"}\""))
    }

    @Test
    fun `serialize is called exactly once and the returned string is reused`() {
        val serializer = RecordingSerializer(payload = "{\"value\":1}", decoded = OrderPlaced("evt-1", "acme"))
        val encoded = codec(serializer).encode(OrderPlaced("evt-1", "acme"))

        assertEquals(1, serializer.serializeCalls)
        assertEquals("{\"value\":1}", serializer.lastSerialized)
        assertTrue(encoded.body.contains("value"))
    }

    @Test
    fun `non string serializer result fails closed without exposing the result`() {
        val serializer = RecordingSerializer(payload = "ignored", decoded = OrderPlaced("evt-1", "acme"))
        serializer.serializedValue = 42

        val failure = assertFailsWith<AwsModulithOutboundEnvelopeException> {
            codec(serializer).encode(OrderPlaced("evt-1", "acme"))
        }

        assertEquals(null, failure.cause)
        assertFalse(failure.toString().contains("42"))
    }

    @Test
    fun `payload byte boundary accepts 196608 and rejects 196609 before publication`() {
        val event = OrderPlaced("evt-1", "acme")
        val acceptedSerializer = RecordingSerializer(payload = "x".repeat(196_608), decoded = event)

        val accepted = codec(acceptedSerializer).encode(event)

        assertTrue(accepted.body.isNotEmpty())
        val rejectedSerializer = RecordingSerializer(payload = "x".repeat(196_609), decoded = event)
        assertFailsWith<AwsModulithOutboundEnvelopeException> {
            codec(rejectedSerializer).encode(event)
        }
    }

    @Test
    fun `envelope byte boundary accepts 262144 and rejects 262145 before publication`() {
        val event = OrderPlaced("evt-1", "acme")
        val acceptedPayload = payloadForEnvelopeSize(262_144, event)
        val acceptedSerializer = RecordingSerializer(payload = acceptedPayload, decoded = event)

        val accepted = codec(acceptedSerializer).encode(event)

        assertEquals(262_144, accepted.body.utf8Size())
        val rejectedPayload = payloadForEnvelopeSize(262_145, event)
        val rejectedSerializer = RecordingSerializer(payload = rejectedPayload, decoded = event)
        assertFailsWith<AwsModulithOutboundEnvelopeException> {
            codec(rejectedSerializer).encode(event)
        }
    }

    @Test
    fun `envelope and encoded event snapshot maps and redact sensitive toString values`() {
        val mutableHeaders = linkedMapOf("tenant" to "secret-tenant")
        val envelope = AwsModulithEventEnvelope(
            id = "evt-1",
            type = "order.placed",
            version = 1,
            payload = "secret-payload",
            headers = mutableHeaders,
        )
        val mutableAttributes = linkedMapOf("tenant" to "secret-attribute")
        val encoded = AwsModulithEncodedEvent("secret-body", mutableAttributes)

        mutableHeaders["tenant"] = "mutated"
        mutableAttributes["tenant"] = "mutated"

        assertEquals("secret-tenant", envelope.headers["tenant"])
        assertEquals("secret-attribute", encoded.messageAttributes["tenant"])
        assertFalse(envelope.toString().contains("secret-payload"))
        assertFalse(envelope.toString().contains("secret-tenant"))
        assertFalse(encoded.toString().contains("secret-body"))
        assertFalse(encoded.toString().contains("secret-attribute"))
    }

    @Test
    fun `decode passes only the registered concrete class to EventSerializer`() {
        val event = OrderPlaced("evt-1", "acme")
        val serializer = RecordingSerializer(payload = "{\"orderId\":\"42\"}", decoded = event)
        val codec = codec(serializer)
        val body = envelope(payload = serializer.payload)

        assertSame(
            event,
            codec.decode(body, emptyMap<String, String>()),
        )
        assertEquals(OrderPlaced::class.java, serializer.deserializedType)
        assertEquals(serializer.payload, serializer.deserializedInput)
    }

    @Test
    fun `ordinary serializer failures are sanitized while cancellation and error identity are preserved`() {
        val hostile = "secret-payload"
        val ordinary = RecordingSerializer(payload = "{}", decoded = OrderPlaced("evt-1", "acme"))
        ordinary.deserializeFailure = IllegalStateException(hostile)
        val ordinaryFailure = assertFailsWith<AwsModulithInboundEnvelopeException> {
            codec(ordinary).decode(envelope(payload = "{}"), emptyMap<String, String>())
        }
        assertEquals(null, ordinaryFailure.cause)
        assertFalse(ordinaryFailure.toString().contains(hostile))

        val cancellation = CancellationException(hostile)
        val cancelled = RecordingSerializer(payload = "{}", decoded = OrderPlaced("evt-1", "acme"))
        cancelled.deserializeFailure = cancellation
        val actualCancellation = assertFailsWith<CancellationException> {
            codec(cancelled).decode(envelope(payload = "{}"), emptyMap<String, String>())
        }
        assertSame(cancellation, actualCancellation)

        val fatal = AssertionError(hostile)
        val failed = RecordingSerializer(payload = "{}", decoded = OrderPlaced("evt-1", "acme"))
        failed.deserializeFailure = fatal
        val actualFatal = assertFailsWith<AssertionError> {
            codec(failed).decode(envelope(payload = "{}"), emptyMap<String, String>())
        }
        assertSame(fatal, actualFatal)
    }

    @Test
    fun `outbound serializer preserves cancellation and error identity`() {
        val event = OrderPlaced("evt-1", "acme")
        val cancellation = CancellationException("secret-payload")
        val cancelled = RecordingSerializer(payload = "{}", decoded = event)
        cancelled.serializeFailure = cancellation

        val actualCancellation = assertFailsWith<CancellationException> {
            codec(cancelled).encode(event)
        }

        assertSame(cancellation, actualCancellation)
        val fatal = AssertionError("secret-payload")
        val failed = RecordingSerializer(payload = "{}", decoded = event)
        failed.serializeFailure = fatal
        val actualFatal = assertFailsWith<AssertionError> {
            codec(failed).encode(event)
        }
        assertSame(fatal, actualFatal)
    }

    private fun codec(
        serializer: RecordingSerializer,
        maxSerializedPayloadBytes: Int = 196_608,
        maxEnvelopeBytes: Int = 262_144,
    ): DefaultAwsModulithEventCodec = DefaultAwsModulithEventCodec(
        registry = registry,
        eventSerializer = serializer,
        maxSerializedPayloadBytes = maxSerializedPayloadBytes,
        maxEnvelopeBytes = maxEnvelopeBytes,
    )

    private fun payloadForEnvelopeSize(targetBytes: Int, event: OrderPlaced): String {
        val emptySerializer = RecordingSerializer(payload = "", decoded = event)
        val baseBytes = codec(emptySerializer, maxEnvelopeBytes = Int.MAX_VALUE)
            .encode(event)
            .body
            .utf8Size()
        val encodedPayloadBytes = targetBytes - baseBytes
        require(encodedPayloadBytes >= 0)
        val escapedCount = encodedPayloadBytes / JSON_ESCAPED_NULL_BYTES
        val plainCount = encodedPayloadBytes % JSON_ESCAPED_NULL_BYTES
        return "\u0000".repeat(escapedCount) + "x".repeat(plainCount)
    }

    private companion object {
        const val JSON_ESCAPED_NULL_BYTES = 6

        val registry: AwsModulithEventTypeRegistry = AwsModulithEventTypeRegistry.of(
            AwsModulithEventTypeRegistration(
                type = "order.placed",
                version = 1,
                eventClass = OrderPlaced::class.java,
                eventId = { it.eventId },
                allowedHeaderNames = setOf("tenant"),
                headers = { mapOf("tenant" to it.tenant) },
            )
        )

        fun envelope(
            id: String = "evt-1",
            type: String = "order.placed",
            version: Int = 1,
            payload: String = "{}",
            headers: String = "{\"tenant\":\"acme\"}",
        ): String = """
            {
              "specVersion": 1,
              "id": "$id",
              "type": "$type",
              "version": $version,
              "payload": ${ObjectMapper().writeValueAsString(payload)},
              "headers": $headers
            }
        """.trimIndent()
    }
}

private fun String.utf8Size(): Int = toByteArray(StandardCharsets.UTF_8).size

private data class OrderPlaced(
    val eventId: String,
    val tenant: String,
)

private class RecordingSerializer(
    val payload: String,
    private val decoded: Any,
) : EventSerializer {
    var serializedValue: Any = payload
    var serializeCalls: Int = 0
    var lastSerialized: String? = null
    var deserializedInput: Any? = null
    var deserializedType: Class<*>? = null
    var serializeFailure: Throwable? = null
    var deserializeFailure: Throwable? = null

    override fun serialize(event: Any): Any {
        serializeCalls++
        serializeFailure?.let { throw it }
        lastSerialized = serializedValue as? String
        return serializedValue
    }

    @Suppress("UNCHECKED_CAST")
    override fun <T : Any> deserialize(event: Any, targetType: Class<T>): T {
        deserializedInput = event
        deserializedType = targetType
        deserializeFailure?.let { throw it }
        return targetType.cast(decoded) as T
    }
}
