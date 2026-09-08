package io.bluetape4k.aws.spring.sqs

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.assertions.shouldNotBeEqualTo
import io.bluetape4k.codec.Base58
import org.junit.jupiter.api.Test

class SqsExtendedPolicyFingerprintTest {

    @Test
    fun `policy fingerprint is deterministic across map order and mutates on bound changes`() {
        val queueUrl = "https://sqs.us-east-1.amazonaws.com/123456789012/${Base58.randomString(16)}"
        val base = SqsExtendedClientProperties.Policy(
            bucket = "bucket-${Base58.randomString(16)}",
            keyPrefix = "extended/payloads",
            configuredSqsRetentionSeconds = 14 * 3_600,
            configuredMaxVisibilityRetryWindowSeconds = 7 * 3_600,
            rollbackDeadlineSeconds = 24 * 3_600,
            encryption = SqsExtendedClientProperties.Encryption(
                enabled = true,
                encryptionContext = linkedMapOf("z" to "last", "a" to "first"),
                keyFingerprint = "kms-fingerprint",
            ),
        )
        val reordered = base.copy(
            encryption = base.encryption.copy(
                encryptionContext = linkedMapOf("a" to "first", "z" to "last"),
            ),
        )

        val first = SqsExtendedPolicyFingerprint.calculate(queueUrl, base)
        val second = SqsExtendedPolicyFingerprint.calculate(queueUrl, reordered)
        first shouldBeEqualTo second
        SqsExtendedPolicyFingerprint.calculate(queueUrl, base.copy(deleteOnAck = !base.deleteOnAck))
            .shouldNotBeEqualTo(first)
        SqsExtendedPolicyFingerprint.calculate("$queueUrl/foreign", base)
            .shouldNotBeEqualTo(first)
    }

    @Test
    fun `policy fingerprint is order independent and delimiter safe`() {
        val queueUrl = "https://sqs.us-east-1.amazonaws.com/123456789012/orders"
        val ordered = policy(
            linkedMapOf(
                "언어" to "한;글=값",
                "empty" to "",
            ),
        )
        val reordered = policy(
            linkedMapOf(
                "empty" to "",
                "언어" to "한;글=값",
            ),
        )

        SqsExtendedPolicyFingerprint.calculate(queueUrl, ordered) shouldBeEqualTo
            SqsExtendedPolicyFingerprint.calculate(queueUrl, reordered)

        val delimiterBearing = policy(mapOf("a" to "b;c=d"))
        val splitEntries = policy(
            linkedMapOf(
                "a" to "b",
                "c" to "d",
            ),
        )
        SqsExtendedPolicyFingerprint.calculate(queueUrl, delimiterBearing)
            .shouldNotBeEqualTo(SqsExtendedPolicyFingerprint.calculate(queueUrl, splitEntries))

        SqsExtendedPolicyFingerprint.calculate(queueUrl, policy(mapOf("a=b" to "c")))
            .shouldNotBeEqualTo(SqsExtendedPolicyFingerprint.calculate(queueUrl, policy(mapOf("a" to "b=c"))))

        SqsExtendedPolicyFingerprint.calculate(queueUrl, policy(emptyMap()))
            .shouldNotBeEqualTo(SqsExtendedPolicyFingerprint.calculate(queueUrl, policy(mapOf("empty" to ""))))
    }

    @Test
    fun `policy encryption context rejects empty keys`() {
        val error = assertFailsWith<IllegalArgumentException> {
            SqsExtendedClientProperties.Encryption(encryptionContext = mapOf("" to "value"))
        }

        error.message.orEmpty() shouldContain "encryption context keys"
    }

    @Test
    fun `policy fingerprint uses a fixed canonical golden vector`() {
        val policy = SqsExtendedClientProperties.Policy(
            bucket = "bucket",
            keyPrefix = "prefix",
            offloadThresholdBytes = 262_144,
            maxInlineBytes = 1_048_576,
            maxOffloadPayloadBytes = 67_108_864,
            deleteOnAck = true,
            orphanRetentionHours = 168,
            configuredSqsRetentionSeconds = 14 * 3_600,
            configuredMaxVisibilityRetryWindowSeconds = 7 * 3_600,
            rollbackDeadlineSeconds = 24 * 3_600,
            pointerSigningKeyRef = "primary",
            encryption = SqsExtendedClientProperties.Encryption(
                enabled = false,
                encryptionContext = emptyMap(),
                keyFingerprint = null,
            ),
        )

        SqsExtendedPolicyFingerprint.canonicalFieldCount() shouldBeEqualTo 16
        SqsExtendedPolicyFingerprint.calculate(
            "https://sqs.us-east-1.amazonaws.com/123456789012/orders",
            policy,
        ) shouldBeEqualTo "bZqrauU1-KxHeZK_XePaP3vipFx2g6ULYABHTayMXJI"
    }

    private fun policy(context: Map<String, String>): SqsExtendedClientProperties.Policy =
        SqsExtendedClientProperties.Policy(
            bucket = "bucket",
            encryption = SqsExtendedClientProperties.Encryption(
                encryptionContext = context,
            ),
        )
}
