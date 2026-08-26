package io.bluetape4k.aws.spring.modulith

import io.bluetape4k.assertions.assertFailsWith
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CancellationException
import java.lang.reflect.Proxy
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class AwsModulithEventTypeRegistryTest {

    @ParameterizedTest
    @ValueSource(strings = ["", "Order.Placed", "-order", "order placed"])
    fun `invalid event type is rejected`(type: String) {
        assertFailsWith<AwsModulithConfigurationException> {
            AwsModulithEventTypeRegistry.of(registration(type = type))
        }
    }

    @Test
    fun `non-positive versions are rejected`() {
        assertFailsWith<AwsModulithConfigurationException> {
            AwsModulithEventTypeRegistry.of(registration(version = 0))
        }
    }

    @Test
    fun `duplicate event class is rejected even when type differs`() {
        assertFailsWith<AwsModulithConfigurationException> {
            AwsModulithEventTypeRegistry.of(
                registration(type = "order.placed"),
                registration(type = "order.cancelled"),
            )
        }
    }

    @Test
    fun `duplicate type and version is rejected`() {
        assertFailsWith<AwsModulithConfigurationException> {
            AwsModulithEventTypeRegistry.of(
                registration(eventClass = OrderPlaced::class.java),
                registration(type = "order.placed", eventClass = OrderCancelled::class.java),
            )
        }
    }

    @Test
    fun `a type cannot have multiple current versions`() {
        assertFailsWith<AwsModulithConfigurationException> {
            AwsModulithEventTypeRegistry.of(
                registration(version = 1, eventClass = OrderPlaced::class.java),
                registration(version = 2, eventClass = OrderCancelled::class.java),
            )
        }
    }

    @Test
    fun `registration count and type length boundaries are enforced`() {
        val registrations = (0 until MAX_REGISTRATIONS).map { index ->
            registration(
                type = "event.$index",
                eventClass = proxyEventClass(index + 1),
            )
        }
        AwsModulithEventTypeRegistry.of(*registrations.toTypedArray())

        assertFailsWith<AwsModulithConfigurationException> {
            AwsModulithEventTypeRegistry.of(
                *(registrations + registration(
                    type = "event.256",
                    eventClass = proxyEventClass(MAX_REGISTRATIONS + 1),
                )).toTypedArray()
            )
        }

        val maximumType = "a" + "b".repeat(MAX_EVENT_TYPE_LENGTH - 1)
        AwsModulithEventTypeRegistry.of(registration(type = maximumType))
        assertFailsWith<AwsModulithConfigurationException> {
            AwsModulithEventTypeRegistry.of(
                registration(type = maximumType + "b")
            )
        }
    }

    @Test
    fun `exact runtime class is required`() {
        val registry = AwsModulithEventTypeRegistry.of(registration())
        val event = OrderPlaced("evt-1")

        registry.registrationFor(event).eventId(event) shouldEqual "evt-1"
        assertFailsWith<AwsModulithEventRegistrationMismatchException> {
            registry.registrationFor(PreferredOrderPlaced("evt-2"))
        }
    }

    @Test
    fun `resolved registration uses a safe cast before invoking typed functions`() {
        val registry = AwsModulithEventTypeRegistry.of(registration())
        val resolved = registry.registrationFor(OrderPlaced("evt-1"))

        assertFailsWith<AwsModulithEventRegistrationMismatchException> {
            resolved.eventId(OtherEvent("evt-2"))
        }
        assertFailsWith<AwsModulithEventRegistrationMismatchException> {
            resolved.eventId(PreferredOrderPlaced("evt-3"))
        }
    }

    @Test
    fun `ordinary extractor failures are sanitized through the registry path`() {
        val registry = AwsModulithEventTypeRegistry.of(
            registration(eventId = { throw IllegalStateException(HOSTILE_MARKER) })
        )

        val failure = assertFailsWith<AwsModulithEventRegistrationMismatchException> {
            registry.registrationFor(OrderPlaced("evt-1")).eventId(OrderPlaced("evt-1"))
        }

        assertEquals("BT4K-MOD-102:SERIALIZATION", failure.message)
        assertEquals(null, failure.cause)
        assertFalse(failure.toString().contains(HOSTILE_MARKER))
    }

    @Test
    fun `cancellation and error identities survive extractor sanitization`() {
        val cancellation = CancellationException(HOSTILE_MARKER)
        val cancellationRegistry = AwsModulithEventTypeRegistry.of(
            registration(eventId = { throw cancellation })
        )
        val actualCancellation = assertFailsWith<CancellationException> {
            cancellationRegistry.registrationFor(OrderPlaced("evt-1")).eventId(OrderPlaced("evt-1"))
        }
        assertSame(cancellation, actualCancellation)

        val error = AssertionError(HOSTILE_MARKER)
        val errorRegistry = AwsModulithEventTypeRegistry.of(
            registration(eventId = { throw error })
        )
        val actualError = assertFailsWith<AssertionError> {
            errorRegistry.registrationFor(OrderPlaced("evt-1")).eventId(OrderPlaced("evt-1"))
        }
        assertSame(error, actualError)
    }

    @Test
    fun `unknown type and unsupported version are distinct`() {
        val registry = AwsModulithEventTypeRegistry.of(registration())

        assertFailsWith<AwsModulithUnknownEventTypeException> {
            registry.registrationFor("missing.event", 1)
        }
        assertFailsWith<AwsModulithUnsupportedEventVersionException> {
            registry.registrationFor("order.placed", 2)
        }
    }

    @Test
    fun `event id must be stable non blank and bounded`() {
        val blank = AwsModulithEventTypeRegistry.of(
            registration(eventId = { "   " }),
        )
        assertFailsWith<AwsModulithEventRegistrationMismatchException> {
            blank.registrationFor(OrderPlaced("evt-1")).eventId(OrderPlaced("evt-1"))
        }

        val tooLong = AwsModulithEventTypeRegistry.of(
            registration(eventId = { "x".repeat(129) }),
        )
        assertFailsWith<AwsModulithEventRegistrationMismatchException> {
            tooLong.registrationFor(OrderPlaced("evt-1")).eventId(OrderPlaced("evt-1"))
        }

        val maximumUtf8Id = "가".repeat(42) + "ab"
        val maximumUtf8 = AwsModulithEventTypeRegistry.of(
            registration(eventId = { maximumUtf8Id }),
        )
        assertEquals(
            maximumUtf8Id,
            maximumUtf8.registrationFor(OrderPlaced("evt-1")).eventId(OrderPlaced("evt-1")),
        )

        listOf(maximumUtf8Id + "c", "evt\u0000id").forEach { invalidId ->
            val invalid = AwsModulithEventTypeRegistry.of(
                registration(eventId = { invalidId }),
            )
            assertFailsWith<AwsModulithEventRegistrationMismatchException> {
                invalid.registrationFor(OrderPlaced("evt-1")).eventId(OrderPlaced("evt-1"))
            }
        }
    }

    @Test
    fun `headers must stay within the registration allowlist`() {
        val registry = AwsModulithEventTypeRegistry.of(
            registration(
                allowedHeaderNames = setOf("tenant"),
                headers = { mapOf("other" to "value") },
            )
        )

        assertFailsWith<AwsModulithEventRegistrationMismatchException> {
            registry.registrationFor(OrderPlaced("evt-1")).headers(OrderPlaced("evt-1"))
        }
    }

    @Test
    fun `registry defensively copies registration collections and returned headers`() {
        val allowedHeaders = linkedSetOf("tenant")
        val eventHeaders = linkedMapOf("tenant" to "acme")
        val registry = AwsModulithEventTypeRegistry.of(
            registration(
                allowedHeaderNames = allowedHeaders,
                headers = { eventHeaders },
            )
        )
        val headers = registry.registrationFor(OrderPlaced("evt-1")).headers(OrderPlaced("evt-1"))
        allowedHeaders += "unexpected"
        eventHeaders["unexpected"] = "not-exported"

        assertEquals(mapOf("tenant" to "acme"), headers)
        assertNotSame(eventHeaders, headers)
        assertTrue(headers is Map<*, *>)
    }

    @Test
    fun `registration lookup maps the exact class and type version`() {
        val registry = AwsModulithEventTypeRegistry.of(registration())
        val resolved = registry.registrationFor(OrderPlaced("evt-1"))

        assertEquals("order.placed", resolved.type)
        assertEquals(1, resolved.version)
        assertSame(OrderPlaced::class.java, resolved.eventClass)
        assertFalse(resolved.headers(OrderPlaced("evt-1")).containsKey("secret"))
    }

    private fun registration(
        type: String = "order.placed",
        version: Int = 1,
        eventClass: Class<out Any> = OrderPlaced::class.java,
        eventId: (OrderPlaced) -> String = { it.id },
        allowedHeaderNames: Set<String> = emptySet(),
        headers: (OrderPlaced) -> Map<String, String> = { emptyMap() },
    ): AwsModulithEventTypeRegistration<OrderPlaced> =
        AwsModulithEventTypeRegistration(
            type = type,
            version = version,
            eventClass = eventClass as Class<OrderPlaced>,
            eventId = eventId,
            allowedHeaderNames = allowedHeaderNames,
            headers = headers,
        )

    private infix fun String.shouldEqual(expected: String) {
        assertEquals(expected, this)
    }

    private open class OrderPlaced(val id: String)

    private data class OrderCancelled(val id: String)

    private class PreferredOrderPlaced(id: String) : OrderPlaced(id)

    private data class OtherEvent(val id: String)

    private fun proxyEventClass(index: Int): Class<out Any> = Proxy.newProxyInstance(
        javaClass.classLoader,
        MARKER_INTERFACES.filterIndexed { markerIndex, _ -> index and (1 shl markerIndex) != 0 }.toTypedArray(),
    ) { _, _, _ -> null }.javaClass

    private interface Marker0
    private interface Marker1
    private interface Marker2
    private interface Marker3
    private interface Marker4
    private interface Marker5
    private interface Marker6
    private interface Marker7
    private interface Marker8

    companion object {
        private const val MAX_REGISTRATIONS = 256
        private const val MAX_EVENT_TYPE_LENGTH = 128
        private val MARKER_INTERFACES = arrayOf(
            Marker0::class.java,
            Marker1::class.java,
            Marker2::class.java,
            Marker3::class.java,
            Marker4::class.java,
            Marker5::class.java,
            Marker6::class.java,
            Marker7::class.java,
            Marker8::class.java,
        )
        private const val HOSTILE_MARKER = "secret-value:event-id:header-value:arn:request-response"
    }
}
