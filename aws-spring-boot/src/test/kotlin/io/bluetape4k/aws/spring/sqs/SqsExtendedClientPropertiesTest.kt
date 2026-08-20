package io.bluetape4k.aws.spring.sqs

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.codec.Base58
import org.junit.jupiter.api.Test

class SqsExtendedClientPropertiesTest {

    @Test
    fun `extended client is disabled by default and keeps bounded defaults`() {
        val properties = SqsExtendedClientProperties()

        properties.enabled shouldBeEqualTo false
        properties.producerEnabled shouldBeEqualTo false
        properties.consumerEnabled shouldBeEqualTo false
        properties.shutdownDrainTimeoutSeconds shouldBeEqualTo 20
        properties.toString() shouldContain "enabled=false"
    }

    @Test
    fun `policy validates payload and retention bounds`() {
        val bucket = "bucket-${Base58.randomString(16)}"
        val valid = SqsExtendedClientProperties.Policy(
            bucket = bucket,
            offloadThresholdBytes = 262_144,
            maxInlineBytes = 1_048_576,
            maxOffloadPayloadBytes = 67_108_864,
            configuredSqsRetentionSeconds = 14 * 3_600,
            configuredMaxVisibilityRetryWindowSeconds = 7 * 3_600,
            rollbackDeadlineSeconds = 24 * 3_600,
        )

        valid.maxOffloadPayloadBytes shouldBeEqualTo 67_108_864

        assertFailsWith<IllegalArgumentException> {
            valid.copy(offloadThresholdBytes = 0)
        }
        assertFailsWith<IllegalArgumentException> {
            valid.copy(maxInlineBytes = 1_048_577)
        }
        assertFailsWith<IllegalArgumentException> {
            valid.copy(maxOffloadPayloadBytes = 67_108_865)
        }
        assertFailsWith<IllegalArgumentException> {
            valid.copy(orphanRetentionHours = 0)
        }
        assertFailsWith<IllegalArgumentException> {
            valid.copy(minimumVisibilityTimeoutSeconds = 43_201)
        }
    }

    @Test
    fun `policy rejects threshold ordering and rollback window beyond retention`() {
        val base = SqsExtendedClientProperties.Policy(
            bucket = "bucket-${Base58.randomString(16)}",
            configuredSqsRetentionSeconds = 14 * 3_600,
            configuredMaxVisibilityRetryWindowSeconds = 7 * 3_600,
            rollbackDeadlineSeconds = 24 * 3_600,
        )

        assertFailsWith<IllegalArgumentException> {
            base.copy(offloadThresholdBytes = 900_000, maxInlineBytes = 800_000)
        }
        val retention = assertFailsWith<IllegalArgumentException> {
            base.copy(orphanRetentionHours = 1, rollbackDeadlineSeconds = 8 * 3_600)
        }
        retention.message shouldContain "retention"
    }

    @Test
    fun `queue policies require canonical non duplicate exact urls`() {
        val queueUrl = "https://sqs.us-east-1.amazonaws.com/123456789012/${Base58.randomString(16)}"
        val policy = SqsExtendedClientProperties.Policy(
            bucket = "bucket-${Base58.randomString(16)}",
            configuredSqsRetentionSeconds = 14 * 3_600,
            configuredMaxVisibilityRetryWindowSeconds = 7 * 3_600,
            rollbackDeadlineSeconds = 24 * 3_600,
        )

        val configured = SqsExtendedClientProperties(
            enabled = true,
            producerEnabled = true,
            consumerEnabled = true,
            queues = mapOf("orders" to SqsExtendedClientProperties.QueuePolicy(queueUrl, policy)),
        )
        configured.queues["orders"]?.queueUrl shouldBeEqualTo queueUrl

        assertFailsWith<IllegalArgumentException> {
            SqsExtendedClientProperties.QueuePolicy(" $queueUrl", policy)
        }
        assertFailsWith<IllegalArgumentException> {
            SqsExtendedClientProperties.QueuePolicy("$queueUrl\n", policy)
        }
    }

    @Test
    fun `security holder does not expose signing material`() {
        val secret = "secret-${Base58.randomString(16)}"
        val security = SqsExtendedClientProperties.Security(mapOf("primary" to secret))

        security.toString() shouldContain "keyCount=1"
        security.toString() shouldBeEqualTo "SqsExtendedClientSecurity(keyCount=1)"
    }
}
