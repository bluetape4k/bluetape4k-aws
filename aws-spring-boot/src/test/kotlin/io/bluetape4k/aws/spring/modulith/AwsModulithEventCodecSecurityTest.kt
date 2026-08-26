package io.bluetape4k.aws.spring.modulith

import io.bluetape4k.assertions.assertFailsWith
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class AwsModulithEventCodecSecurityTest {

    @Test
    fun `duplicate outer keys are rejected`() {
        assertDecodeFails("""
            {"specVersion":1,"id":"evt-1","id":"evt-2","type":"order.placed","version":1,"payload":"{}","headers":{}}
        """)
    }

    @Test
    fun `duplicate payload keys and trailing JSON are rejected`() {
        assertDecodeFails(envelope(payload = "{\"value\":1,\"value\":2}"))
        assertDecodeFails(envelope(payload = "{} {}"))
    }

    @Test
    fun `codec does not expose an ObjectMapper injection path`() {
        val parameterTypes = DefaultAwsModulithEventCodec::class.java.declaredConstructors
            .flatMap { it.parameterTypes.asList() }

        assertFalse(tools.jackson.databind.ObjectMapper::class.java in parameterTypes)
    }

    @Test
    fun `unknown outer fields and invalid envelope values are rejected`() {
        assertDecodeFails(envelope(extra = ",\"unknown\":true"))
        assertDecodeFails(envelope(id = ""))
        assertDecodeFails(envelope(id = "line\nfeed"))
        decodeEnvelope(envelope(id = "가".repeat(42) + "ab"))
        assertDecodeFails(envelope(id = "가".repeat(42) + "abc"))
        assertDecodeFails(envelope(specVersion = 2))
        assertDecodeFailsAs<AwsModulithUnknownEventTypeException>(envelope(type = "missing.event"))
        assertDecodeFailsAs<AwsModulithUnsupportedEventVersionException>(envelope(version = 2))
    }

    @Test
    fun `maximum nesting depth is accepted and one deeper is rejected`() {
        decode(payload = nestedArrays(32))
        assertDecodeFails(envelope(payload = nestedArrays(33)))
    }

    @Test
    fun `token count boundary accepts 100000 and rejects 100001`() {
        val codec = testCodec(maxSerializedPayloadBytes = 262_144)

        codec.decode(envelope(payload = tokenArray(100_000)), emptyMap<String, String>())
        assertFailsWith<AwsModulithInboundEnvelopeException> {
            codec.decode(envelope(payload = tokenArray(100_001)), emptyMap<String, String>())
        }
    }

    @Test
    fun `string byte boundary accepts 196608 and rejects 196609 for ASCII and UTF8`() {
        val asciiAtLimit = jsonString("x".repeat(196_606))
        val asciiOverLimit = jsonString("x".repeat(196_607))
        val utf8AtLimit = jsonString("가".repeat(65_535) + "x")
        val utf8OverLimit = jsonString("가".repeat(65_535) + "xy")

        decode(asciiAtLimit)
        decode(utf8AtLimit)
        assertDecodeFails(envelope(payload = asciiOverLimit))
        assertDecodeFails(envelope(payload = utf8OverLimit))

        val configuredCodec = testCodec(maxSerializedPayloadBytes = 262_144)
        val configuredAsciiAtLimit = jsonString("x".repeat(196_608))
        val configuredAsciiOverLimit = jsonString("x".repeat(196_609))
        val configuredUtf8AtLimit = jsonString("가".repeat(65_536))
        val configuredUtf8OverLimit = jsonString("가".repeat(65_536) + "x")
        configuredCodec.decode(envelope(payload = configuredAsciiAtLimit), emptyMap<String, String>())
        configuredCodec.decode(envelope(payload = configuredUtf8AtLimit), emptyMap<String, String>())
        assertFailsWith<AwsModulithInboundEnvelopeException> {
            configuredCodec.decode(envelope(payload = configuredAsciiOverLimit), emptyMap<String, String>())
        }
        assertFailsWith<AwsModulithInboundEnvelopeException> {
            configuredCodec.decode(envelope(payload = configuredUtf8OverLimit), emptyMap<String, String>())
        }
    }

    @Test
    fun `number length boundary accepts 1000 and rejects 1001`() {
        decode("9".repeat(1_000))
        assertDecodeFails(envelope(payload = "9".repeat(1_001)))
    }

    @Test
    fun `nested polymorphic type ids are rejected at every depth`() {
        listOf("@class", "@type", "@c", "javaClass").forEach { key ->
            listOf(0, 7, 30).forEach { depth ->
                assertDecodeFails(envelope(payload = nestedTypeId(key, depth)))
            }
        }
    }

    @Test
    fun `body system attributes are authoritative`() {
        val codec = testCodec()
        val body = envelope()
        val mismatches = listOf(
            mapOf("bt4k-event-id" to "other-id"),
            mapOf("bt4k-event-type" to "other.type"),
            mapOf("bt4k-event-version" to "999999"),
            mapOf("tenant" to "other-tenant"),
        )

        mismatches.forEach { mismatched ->
            val failure = assertFailsWith<AwsModulithInboundEnvelopeException> {
                codec.decode(body, mismatched)
            }
            assertEquals(null, failure.cause)
            mismatched.values.forEach { value ->
                assertFalse(failure.toString().contains(value))
            }
        }
    }

    @Test
    fun `business attributes do not widen body headers`() {
        val codec = testCodec()
        val decoded = codec.decode(envelope(headers = "{}"), mapOf("not-registered" to "value"))

        assertEquals(OrderPlacedSecurity("evt-1", "acme"), decoded)
    }

    @Test
    fun `invalid inbound body headers use the inbound envelope failure`() {
        assertDecodeFails(envelope(headers = "{\"other\":\"value\"}"))
        assertDecodeFails(envelope(headers = "{\"tenant\":\"${"x".repeat(1_025)}\"}"))
    }

    @Test
    fun `header allowlist name value count sensitive and reserved policies are enforced`() {
        assertEncodeFailsAs<AwsModulithEventRegistrationMismatchException>(
            headerNames = setOf("tenant"),
            headers = mapOf("other" to "value"),
        )
        assertEncodeFails(headerNames = setOf("bad name"), headers = mapOf("bad name" to "value"))
        assertEncodeFails(headerNames = setOf("tenant"), headers = mapOf("tenant" to "x".repeat(1_025)))
        val sevenHeaders = (1..7).associate { "header$it" to "value" }
        assertEquals(10, encodeHeaders(sevenHeaders.keys, sevenHeaders).messageAttributes.size)
        val maxName = "n".repeat(128)
        assertEquals("value", encodeHeaders(setOf(maxName), mapOf(maxName to "value")).messageAttributes[maxName])
        assertEncodeFails(headerNames = setOf("n".repeat(129)), headers = mapOf("n".repeat(129) to "value"))
        val maxUtf8Value = "가".repeat(341) + "x"
        assertEquals(
            maxUtf8Value,
            encodeHeaders(setOf("tenant"), mapOf("tenant" to maxUtf8Value)).messageAttributes["tenant"],
        )
        assertEncodeFails(
            headerNames = setOf("tenant"),
            headers = mapOf("tenant" to maxUtf8Value + "y"),
        )
        assertEncodeFails(
            headerNames = (1..8).map { "header$it" }.toSet(),
            headers = (1..8).associate { "header$it" to "value" },
        )

        listOf("authorization", "cookie", "credential", "password", "secret", "token", "bt4k-custom")
            .forEach { name ->
                assertEncodeFails(headerNames = setOf(name), headers = mapOf(name to "value"))
            }
    }

    @Test
    fun `hostile serialization failures are sanitized`() {
        val serializer = TestSerializerSecurity(OrderPlacedSecurity("evt-1", "acme"))
        serializer.serializedValue = object {
            override fun toString(): String = "secret-serializer-value"
        }

        val failure = assertFailsWith<AwsModulithOutboundEnvelopeException> {
            testCodec(serializer).encode(OrderPlacedSecurity("evt-1", "acme"))
        }

        assertEquals(null, failure.cause)
        assertFalse(failure.toString().contains("secret-serializer-value"))
    }

    private fun decode(payload: String) = decodeEnvelope(envelope(payload = payload))

    private fun decodeEnvelope(body: String) = testCodec().decode(body, emptyMap<String, String>())

    private fun assertDecodeFails(body: String) {
        assertDecodeFailsAs<AwsModulithInboundEnvelopeException>(body)
    }

    private inline fun <reified T : Throwable> assertDecodeFailsAs(body: String) {
        val failure = assertFailsWith<T> {
            testCodec().decode(body, emptyMap<String, String>())
        }
        if (failure is AwsModulithEventException) {
            assertEquals(null, failure.cause)
        }
    }

    private fun assertEncodeFails(headerNames: Set<String>, headers: Map<String, String>) {
        assertEncodeFailsAs<AwsModulithOutboundEnvelopeException>(headerNames, headers)
    }

    private fun encodeHeaders(
        headerNames: Set<String>,
        headers: Map<String, String>,
    ): AwsModulithEncodedEvent {
        val event = OrderPlacedSecurity("evt-1", "acme")
        val registry = AwsModulithEventTypeRegistry.of(
            AwsModulithEventTypeRegistration(
                type = "order.placed",
                version = 1,
                eventClass = OrderPlacedSecurity::class.java,
                eventId = { it.eventId },
                allowedHeaderNames = headerNames,
                headers = { headers },
            )
        )
        return DefaultAwsModulithEventCodec(registry, TestSerializerSecurity(event)).encode(event)
    }

    private inline fun <reified T : Throwable> assertEncodeFailsAs(
        headerNames: Set<String>,
        headers: Map<String, String>,
    ) {
        val event = OrderPlacedSecurity("evt-1", "acme")
        val registry = AwsModulithEventTypeRegistry.of(
            AwsModulithEventTypeRegistration(
                type = "order.placed",
                version = 1,
                eventClass = OrderPlacedSecurity::class.java,
                eventId = { it.eventId },
                allowedHeaderNames = headerNames,
                headers = { headers },
            )
        )
        val failure = assertFailsWith<T> {
            DefaultAwsModulithEventCodec(registry, TestSerializerSecurity(event)).encode(event)
        }
        if (failure is AwsModulithEventException) {
            assertEquals(null, failure.cause)
        }
    }

    private fun testCodec(
        serializer: TestSerializerSecurity = TestSerializerSecurity(OrderPlacedSecurity("evt-1", "acme")),
        maxSerializedPayloadBytes: Int = 196_608,
    ) = DefaultAwsModulithEventCodec(
        registry = testRegistry,
        eventSerializer = serializer,
        maxSerializedPayloadBytes = maxSerializedPayloadBytes,
    )

    private companion object {
        val testRegistry: AwsModulithEventTypeRegistry = AwsModulithEventTypeRegistry.of(
            AwsModulithEventTypeRegistration(
                type = "order.placed",
                version = 1,
                eventClass = OrderPlacedSecurity::class.java,
                eventId = { it.eventId },
                allowedHeaderNames = setOf("tenant"),
                headers = { mapOf("tenant" to it.tenant) },
            )
        )

        fun envelope(
            specVersion: Int = 1,
            id: String = "evt-1",
            type: String = "order.placed",
            version: Int = 1,
            payload: String = "{}",
            headers: String = "{\"tenant\":\"acme\"}",
            extra: String = "",
        ): String = """
            {"specVersion":$specVersion,"id":"$id","type":"$type","version":$version,"payload":${jsonString(payload)},"headers":$headers$extra}
        """

        fun jsonString(value: String): String = tools.jackson.databind.ObjectMapper().writeValueAsString(value)

        fun nestedArrays(depth: Int): String = "[".repeat(depth) + "0" + "]".repeat(depth)

        fun tokenArray(tokenCount: Int): String {
            require(tokenCount >= 2)
            return (1..(tokenCount - 2)).joinToString(prefix = "[", postfix = "]", separator = ",") { "0" }
        }

        fun nestedTypeId(key: String, depth: Int): String =
            "{\"next\":".repeat(depth) + "{\"$key\":\"java.lang.String\"}" + "}".repeat(depth)
    }
}

private data class OrderPlacedSecurity(
    val eventId: String,
    val tenant: String,
)

private class TestSerializerSecurity(
    private val event: OrderPlacedSecurity,
) : org.springframework.modulith.events.core.EventSerializer {
    var serializedValue: Any = "{}"

    override fun serialize(event: Any): Any = serializedValue

    override fun <T : Any> deserialize(event: Any, targetType: Class<T>): T = targetType.cast(this.event)
}
