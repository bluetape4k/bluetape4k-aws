@file:Suppress("MagicNumber", "MaxLineLength")

package io.bluetape4k.aws.spring.sqs

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64

internal object SqsExtendedPolicyFingerprint {
    private const val DOMAIN = "bluetape4k.sqs.extended.policy/v1"
    private const val FIELD_COUNT = 16

    fun canonicalFieldCount(): Int = FIELD_COUNT

    fun calculate(queueUrl: String, policy: SqsExtendedClientProperties.Policy): String {
        val bytes = ByteArrayOutputStream().apply {
            write(ByteBuffer.allocate(4).putInt(FIELD_COUNT).array())
            string(DOMAIN)
            string(queueUrl)
            string(policy.bucket)
            string(policy.normalizedKeyPrefix())
            integer(policy.offloadThresholdBytes)
            integer(policy.maxInlineBytes)
            integer(policy.maxOffloadPayloadBytes)
            boolean(policy.deleteOnAck)
            integer(policy.orphanRetentionHours)
            integer(policy.configuredSqsRetentionSeconds)
            integer(policy.configuredMaxVisibilityRetryWindowSeconds)
            integer(policy.minimumVisibilityTimeoutSeconds)
            string(policy.pointerSigningKeyRef)
            boolean(policy.encryption.enabled)
            string(canonicalContext(policy.encryption.encryptionContext))
            nullableString(policy.encryption.keyFingerprint)
        }.toByteArray()
        return Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(bytes))
    }

    private fun ByteArrayOutputStream.string(value: String?) {
        val bytes = value.orEmpty().toByteArray(StandardCharsets.UTF_8)
        write('S'.code)
        write(ByteBuffer.allocate(4).putInt(bytes.size).array())
        write(bytes)
    }

    private fun ByteArrayOutputStream.integer(value: Int?) {
        if (value == null) {
            write('N'.code)
            write(0)
            return
        }
        write('I'.code)
        write(ByteBuffer.allocate(4).putInt(4).array())
        write(ByteBuffer.allocate(4).putInt(value).array())
    }

    private fun ByteArrayOutputStream.boolean(value: Boolean) {
        write('B'.code)
        write(ByteBuffer.allocate(4).putInt(1).array())
        write(if (value) 1 else 0)
    }

    private fun ByteArrayOutputStream.nullableString(value: String?) {
        if (value == null) {
            write('N'.code)
            write(0)
        } else {
            write('N'.code)
            write(1)
            string(value)
        }
    }

    private fun canonicalContext(context: Map<String, String>): String =
        context.toSortedMap().entries.joinToString(";") { (key, value) -> "$key=$value" }
}
