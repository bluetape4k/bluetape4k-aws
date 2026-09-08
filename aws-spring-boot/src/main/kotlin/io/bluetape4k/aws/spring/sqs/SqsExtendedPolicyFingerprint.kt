@file:Suppress("MagicNumber", "MaxLineLength")

package io.bluetape4k.aws.spring.sqs

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64

internal object SqsExtendedPolicyFingerprint {
    private const val DOMAIN_V1 = "bluetape4k.sqs.extended.policy/v1"
    private const val DOMAIN_V2 = "bluetape4k.sqs.extended.policy/v2"
    private const val FIELD_COUNT = 16

    fun canonicalFieldCount(): Int = FIELD_COUNT

    fun calculate(queueUrl: String, policy: SqsExtendedClientProperties.Policy): String {
        val context = policy.encryption.encryptionContext
        val domain = if (context.requiresVersion2Fingerprint()) DOMAIN_V2 else DOMAIN_V1
        val bytes = ByteArrayOutputStream().apply {
            write(ByteBuffer.allocate(4).putInt(FIELD_COUNT).array())
            string(domain)
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
            string(canonicalContext(context))
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
        if (context.requiresVersion2Fingerprint()) {
            buildString {
                context.toSortedMap().forEach { (key, value) ->
                    append(key.toByteArray(StandardCharsets.UTF_8).size)
                    append(':')
                    append(key)
                    append(value.toByteArray(StandardCharsets.UTF_8).size)
                    append(':')
                    append(value)
                }
            }
        } else {
            context.toSortedMap().entries.joinToString(";") { (key, value) -> "$key=$value" }
        }

    private fun Map<String, String>.requiresVersion2Fingerprint(): Boolean = any { (key, value) ->
        key.isBlank() || key.containsFingerprintDelimiter() || value.containsFingerprintDelimiter()
    }

    private fun String.containsFingerprintDelimiter(): Boolean = any { it == ';' || it == '=' }
}
