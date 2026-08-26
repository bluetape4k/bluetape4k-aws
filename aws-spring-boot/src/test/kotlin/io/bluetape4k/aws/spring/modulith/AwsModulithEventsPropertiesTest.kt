package io.bluetape4k.aws.spring.modulith

import io.bluetape4k.assertions.assertFailsWith
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.boot.context.properties.bind.Bindable
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.time.Duration
import java.util.stream.Stream

class AwsModulithEventsPropertiesTest {

    @Test
    fun `root producer and consumer are opt in by default`() {
        val properties = AwsModulithEventsProperties()

        assertEquals(false, properties.enabled)
        assertEquals(false, properties.producer.enabled)
        assertEquals(false, properties.consumer.enabled)
        assertEquals(64, properties.producer.maxInFlight)
        assertEquals(196_608, properties.producer.maxSerializedPayloadBytes)
        assertEquals(262_144, properties.producer.maxEnvelopeBytes)
        assertEquals(Duration.ofSeconds(25), properties.producer.shutdownTimeout)
        assertEquals(null, properties.consumer.queue)
        assertEquals(null, properties.consumer.sourceMode)
        assertEquals(emptySet<String>(), properties.consumer.expectedTopicArns)
        assertEquals(true, properties.consumer.redriveRequired)
        assertEquals(10_000, properties.consumer.idempotency.maxEntries)
        assertEquals(256, properties.consumer.idempotency.maxInProgress)
        assertEquals(2_097_152, properties.consumer.idempotency.maxKeyBytes)
        assertEquals(Duration.ofHours(24), properties.consumer.idempotency.retention)
        assertEquals(Duration.ofMinutes(2), properties.consumer.idempotency.leaseDuration)
    }

    @ParameterizedTest
    @MethodSource("producerIntegerBoundaries")
    fun `producer integer boundaries are enforced`(property: String, value: Int, valid: Boolean) {
        if (valid) {
            when (property) {
                "maxInFlight" -> AwsModulithEventsProperties.Producer(maxInFlight = value)
                "maxSerializedPayloadBytes" ->
                    AwsModulithEventsProperties.Producer(maxSerializedPayloadBytes = value)
                else -> AwsModulithEventsProperties.Producer(maxEnvelopeBytes = value)
            }
        } else {
            assertFailsWith<IllegalArgumentException> {
                when (property) {
                    "maxInFlight" -> AwsModulithEventsProperties.Producer(maxInFlight = value)
                    "maxSerializedPayloadBytes" ->
                        AwsModulithEventsProperties.Producer(maxSerializedPayloadBytes = value)
                    else -> AwsModulithEventsProperties.Producer(maxEnvelopeBytes = value)
                }
            }
        }
    }

    @Test
    fun `producer duration boundaries are inclusive`() {
        AwsModulithEventsProperties.Producer(shutdownTimeout = Duration.ofSeconds(1))
        AwsModulithEventsProperties.Producer(shutdownTimeout = Duration.ofMinutes(5))
        assertFailsWith<IllegalArgumentException> {
            AwsModulithEventsProperties.Producer(shutdownTimeout = Duration.ofMillis(999))
        }
        assertFailsWith<IllegalArgumentException> {
            AwsModulithEventsProperties.Producer(shutdownTimeout = Duration.ofMinutes(5).plusNanos(1))
        }
    }

    @Test
    fun `producer enablement requires at least one target`() {
        assertFailsWith<AwsModulithConfigurationException> {
            AwsModulithEventsProperties(
                producer = AwsModulithEventsProperties.Producer(enabled = true),
            )
        }
    }

    @Test
    fun `consumer enablement requires queue and source mode`() {
        assertFailsWith<AwsModulithConfigurationException> {
            AwsModulithEventsProperties(
                consumer = AwsModulithEventsProperties.Consumer(enabled = true),
            )
        }
        assertFailsWith<AwsModulithConfigurationException> {
            AwsModulithEventsProperties(
                consumer = AwsModulithEventsProperties.Consumer(
                    enabled = true,
                    queue = "events",
                ),
            )
        }
    }

    @Test
    fun `SNS consumer requires expected topic ARN allowlist`() {
        assertFailsWith<AwsModulithConfigurationException> {
            AwsModulithEventsProperties(
                consumer = AwsModulithEventsProperties.Consumer(
                    enabled = true,
                    queue = "events",
                    sourceMode = AwsModulithSourceMode.SNS,
                ),
            )
        }

        AwsModulithEventsProperties(
            consumer = AwsModulithEventsProperties.Consumer(
                enabled = true,
                queue = "events",
                sourceMode = AwsModulithSourceMode.SNS,
                expectedTopicArns = setOf(VALID_TOPIC_ARN),
            ),
        )
    }

    @Test
    fun `DIRECT consumer rejects SNS source constraints`() {
        assertFailsWith<AwsModulithConfigurationException> {
            AwsModulithEventsProperties(
                consumer = AwsModulithEventsProperties.Consumer(
                    enabled = true,
                    queue = "events",
                    sourceMode = AwsModulithSourceMode.DIRECT,
                    expectedTopicArns = setOf(VALID_TOPIC_ARN),
                ),
            )
        }
    }

    @Test
    fun `idempotency boundaries are enforced`() {
        AwsModulithEventsProperties.Idempotency(maxInProgress = 1)
        AwsModulithEventsProperties.Idempotency(maxInProgress = 10_000, maxEntries = 10_000)
        AwsModulithEventsProperties.Idempotency(retention = Duration.ofMinutes(1))
        AwsModulithEventsProperties.Idempotency(retention = Duration.ofDays(7))
        AwsModulithEventsProperties.Idempotency(leaseDuration = Duration.ofSeconds(30))
        AwsModulithEventsProperties.Idempotency(leaseDuration = Duration.ofMinutes(30))

        assertFailsWith<IllegalArgumentException> {
            AwsModulithEventsProperties.Idempotency(maxEntries = 0)
        }
        assertFailsWith<IllegalArgumentException> {
            AwsModulithEventsProperties.Idempotency(maxInProgress = 10_001)
        }
        assertFailsWith<IllegalArgumentException> {
            AwsModulithEventsProperties.Idempotency(maxKeyBytes = 0)
        }
        assertFailsWith<IllegalArgumentException> {
            AwsModulithEventsProperties.Idempotency(retention = Duration.ofSeconds(59))
        }
        assertFailsWith<IllegalArgumentException> {
            AwsModulithEventsProperties.Idempotency(retention = Duration.ofDays(7).plusNanos(1))
        }
        assertFailsWith<IllegalArgumentException> {
            AwsModulithEventsProperties.Idempotency(leaseDuration = Duration.ofSeconds(29))
        }
        assertFailsWith<IllegalArgumentException> {
            AwsModulithEventsProperties.Idempotency(leaseDuration = Duration.ofMinutes(30).plusNanos(1))
        }
        assertFailsWith<IllegalArgumentException> {
            AwsModulithEventsProperties.Idempotency(maxEntries = 1, maxInProgress = 2)
        }
    }

    @Test
    fun `target map is bounded and target names are logical names`() {
        val valid = AwsModulithEventsProperties.Target(
            service = AwsModulithTargetService.SNS,
            destination = "orders.fifo",
        )
        AwsModulithEventsProperties(
            producer = AwsModulithEventsProperties.Producer(enabled = true),
            targets = mapOf("orders" to valid),
        )

        assertFailsWith<IllegalArgumentException> {
            AwsModulithEventsProperties(
                targets = mapOf("" to valid),
            )
        }
        listOf(
            "arn:aws:sns:us-east-1:123456789012:orders",
            "http://localhost/queue",
            "https://sqs.us-east-1.amazonaws.com/123/orders",
        )
            .forEach { invalid ->
                assertFailsWith<IllegalArgumentException> {
                    AwsModulithEventsProperties(
                        targets = mapOf(
                            "orders" to AwsModulithEventsProperties.Target(AwsModulithTargetService.SNS, invalid)
                        ),
                    )
                }
            }

        val hundredTargets = (0 until 100).associate { "target-$it" to valid }
        AwsModulithEventsProperties(targets = hundredTargets)
        assertFailsWith<IllegalArgumentException> {
            AwsModulithEventsProperties(
                targets = (0..100).associate { "target-$it" to valid },
            )
        }
    }

    @Test
    fun `SNS and SQS destination and queue length boundaries are enforced`() {
        AwsModulithEventsProperties.Target(
            service = AwsModulithTargetService.SNS,
            destination = "a".repeat(MAX_TOPIC_NAME_LENGTH),
        )
        AwsModulithEventsProperties.Target(
            service = AwsModulithTargetService.SQS,
            destination = "a".repeat(MAX_QUEUE_NAME_LENGTH),
        )
        assertFailsWith<IllegalArgumentException> {
            AwsModulithEventsProperties.Target(
                service = AwsModulithTargetService.SNS,
                destination = "a".repeat(MAX_TOPIC_NAME_LENGTH + 1),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            AwsModulithEventsProperties.Target(
                service = AwsModulithTargetService.SQS,
                destination = "a".repeat(MAX_QUEUE_NAME_LENGTH + 1),
            )
        }

        AwsModulithEventsProperties.Consumer(queue = "a".repeat(MAX_QUEUE_NAME_LENGTH))
        assertFailsWith<IllegalArgumentException> {
            AwsModulithEventsProperties.Consumer(queue = "a".repeat(MAX_QUEUE_NAME_LENGTH + 1))
        }
        listOf(
            "arn:aws:sqs:us-east-1:123456789012:events",
            "https://sqs.us-east-1.amazonaws.com/123456789012/events",
            "events/child",
            "events queue",
        ).forEach { invalidQueue ->
            assertFailsWith<IllegalArgumentException> {
                AwsModulithEventsProperties.Consumer(queue = invalidQueue)
            }
        }
    }

    @Test
    fun `routing key validation distinguishes standard and FIFO destinations`() {
        assertEquals(null, validateAwsModulithRoutingKey("orders", null))
        assertFailsWith<IllegalArgumentException> {
            validateAwsModulithRoutingKey("orders", "partition-1")
        }
        listOf(null, "", "  ", "\u0000").forEach { invalidKey ->
            assertFailsWith<IllegalArgumentException> {
                validateAwsModulithRoutingKey("orders.fifo", invalidKey)
            }
        }

        val maximumKey = "가".repeat(42) + "ab"
        assertEquals(maximumKey, validateAwsModulithRoutingKey("orders.fifo", maximumKey))
        assertFailsWith<IllegalArgumentException> {
            validateAwsModulithRoutingKey("orders.fifo", maximumKey + "c")
        }
    }

    @Test
    fun `Spring Binder binds kebab case nested and target map properties`() {
        val properties = AwsModulithEventsProperties()
        Binder(
            MapConfigurationPropertySource(
                mapOf(
                    "bluetape4k.aws.modulith.events.producer.enabled" to "true",
                    "bluetape4k.aws.modulith.events.producer.max-in-flight" to "8",
                    "bluetape4k.aws.modulith.events.targets.orders.service" to "SNS",
                    "bluetape4k.aws.modulith.events.targets.orders.destination" to "orders",
                )
            )
        ).bind(
            "bluetape4k.aws.modulith.events",
            Bindable.ofInstance(properties),
        )

        properties.validate()
        assertTrue(properties.producer.enabled)
        assertEquals(8, properties.producer.maxInFlight)
        assertEquals(AwsModulithTargetService.SNS, properties.targets["orders"]?.service)
        assertEquals("orders", properties.targets["orders"]?.destination)
    }

    @Test
    fun `Spring Binder bound producer enablement still rejects empty targets`() {
        val properties = AwsModulithEventsProperties()
        Binder(
            MapConfigurationPropertySource(
                mapOf("bluetape4k.aws.modulith.events.producer.enabled" to "true")
            )
        ).bind(
            "bluetape4k.aws.modulith.events",
            Bindable.ofInstance(properties),
        )

        assertFailsWith<AwsModulithConfigurationException> { properties.validate() }
    }

    @Test
    fun `consumer collections are defensively copied`() {
        val expectedArns = linkedSetOf(VALID_TOPIC_ARN)
        val targets = linkedMapOf(
            "orders" to AwsModulithEventsProperties.Target(AwsModulithTargetService.SNS, "orders"),
        )
        val properties = AwsModulithEventsProperties(
            consumer = AwsModulithEventsProperties.Consumer(
                enabled = true,
                queue = "events",
                sourceMode = AwsModulithSourceMode.SNS,
                expectedTopicArns = expectedArns,
            ),
            targets = targets,
        )
        expectedArns += "arn:aws:sns:us-east-1:123456789012:unexpected"
        targets["unexpected"] = AwsModulithEventsProperties.Target(AwsModulithTargetService.SQS, "unexpected")

        assertEquals(setOf(VALID_TOPIC_ARN), properties.consumer.expectedTopicArns)
        assertEquals(1, properties.targets.size)

        val reboundExpectedArns = linkedSetOf(VALID_TOPIC_ARN)
        val reboundTargets = linkedMapOf(
            "orders" to AwsModulithEventsProperties.Target(AwsModulithTargetService.SNS, "orders"),
        )
        properties.consumer.expectedTopicArns = reboundExpectedArns
        properties.targets = reboundTargets
        properties.validate()

        reboundExpectedArns += "arn:aws:sns:us-east-1:123456789012:rebound"
        reboundTargets["rebound"] = AwsModulithEventsProperties.Target(AwsModulithTargetService.SQS, "rebound")

        assertEquals(setOf(VALID_TOPIC_ARN), properties.consumer.expectedTopicArns)
        assertEquals(setOf("orders"), properties.targets.keys)
    }

    companion object {
        private const val VALID_TOPIC_ARN = "arn:aws:sns:us-east-1:123456789012:orders"
        private const val MAX_TOPIC_NAME_LENGTH = 256
        private const val MAX_QUEUE_NAME_LENGTH = 80

        @JvmStatic
        fun producerIntegerBoundaries(): Stream<Arguments> = Stream.of(
            Arguments.of("maxInFlight", 1, true),
            Arguments.of("maxInFlight", 1_024, true),
            Arguments.of("maxInFlight", 0, false),
            Arguments.of("maxInFlight", 1_025, false),
            Arguments.of("maxSerializedPayloadBytes", 1, true),
            Arguments.of("maxSerializedPayloadBytes", 262_144, true),
            Arguments.of("maxSerializedPayloadBytes", 0, false),
            Arguments.of("maxSerializedPayloadBytes", 262_145, false),
            Arguments.of("maxEnvelopeBytes", 1, true),
            Arguments.of("maxEnvelopeBytes", 262_144, true),
            Arguments.of("maxEnvelopeBytes", 0, false),
            Arguments.of("maxEnvelopeBytes", 262_145, false),
        )
    }
}
