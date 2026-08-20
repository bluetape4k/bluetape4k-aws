@file:Suppress("MagicNumber")

package io.bluetape4k.aws.spring.sqs

import org.springframework.boot.context.properties.ConfigurationProperties
import java.util.Base64

@ConfigurationProperties(prefix = "bluetape4k.aws.sqs.extended")
data class SqsExtendedClientProperties(
    val enabled: Boolean = false,
    val producerEnabled: Boolean = false,
    val consumerEnabled: Boolean = false,
    val shutdownDrainTimeoutSeconds: Int = 20,
    val defaultPolicy: Policy? = null,
    val defaultQueueUrls: Set<String> = emptySet(),
    val queues: Map<String, QueuePolicy> = emptyMap(),
    val security: Security = Security(),
) {
    init {
        require(shutdownDrainTimeoutSeconds in 1..25) {
            "shutdownDrainTimeoutSeconds must be between 1 and 25."
        }
        require(!producerEnabled || consumerEnabled) {
            "producerEnabled requires consumerEnabled."
        }
        defaultQueueUrls.forEach(::requireCanonicalQueueUrl)
        require(defaultQueueUrls.size == defaultQueueUrls.toList().distinct().size) {
            "defaultQueueUrls must not contain duplicates."
        }
        if (defaultPolicy != null) {
            require(defaultQueueUrls.isNotEmpty()) {
                "defaultQueueUrls must not be empty when defaultPolicy is configured."
            }
        } else {
            require(defaultQueueUrls.isEmpty()) {
                "defaultQueueUrls requires defaultPolicy."
            }
        }
        queues.keys.forEach { name ->
            require(name.isNotBlank() && name.none { it == '\r' || it == '\n' || it == '\u0000' }) {
                "queue logical name must be non-blank and control-free."
            }
        }
        val queueUrls = queues.values.map { it.queueUrl }
        require(queueUrls.distinct().size == queueUrls.size) {
            "queue policy URLs must be unique."
        }
    }

    fun resolvePolicy(queueUrl: String): Policy? {
        requireCanonicalQueueUrl(queueUrl)
        queues.values.firstOrNull { it.queueUrl == queueUrl }?.let { return it.policy }
        return defaultPolicy?.takeIf { queueUrl in defaultQueueUrls }
    }

    override fun toString(): String =
        "SqsExtendedClientProperties(enabled=$enabled, producerEnabled=$producerEnabled, consumerEnabled=$consumerEnabled, queueCount=${queues.size}, defaultQueueCount=${defaultQueueUrls.size})"

    data class Policy(
        val bucket: String,
        val keyPrefix: String = "bluetape4k/sqs",
        val offloadThresholdBytes: Int = 262_144,
        val maxInlineBytes: Int = 1_048_576,
        val maxOffloadPayloadBytes: Int = 67_108_864,
        val deleteOnAck: Boolean = false,
        val orphanRetentionHours: Int = 168,
        val configuredSqsRetentionSeconds: Int? = null,
        val configuredMaxVisibilityRetryWindowSeconds: Int? = null,
        val rollbackDeadlineSeconds: Int? = null,
        val minimumVisibilityTimeoutSeconds: Int = 30,
        val pointerSigningKeyRef: String = "default",
        val encryption: Encryption = Encryption(),
    ) {
        init {
            require(bucket.isNotBlank() && bucket.none(::isControl)) { "bucket must be non-blank and control-free." }
            require(keyPrefix.isNotBlank() && keyPrefix.none(::isControl)) {
                "keyPrefix must be non-blank and control-free."
            }
            require(maxInlineBytes in 1..1_048_576) { "maxInlineBytes must be between 1 and 1048576." }
            require(offloadThresholdBytes in 1..maxInlineBytes) {
                "offloadThresholdBytes must be within maxInlineBytes."
            }
            require(maxOffloadPayloadBytes in maxInlineBytes..67_108_864) {
                "maxOffloadPayloadBytes must be within the extended payload bound."
            }
            require(orphanRetentionHours in 1..336) { "orphanRetentionHours must be between 1 and 336." }
            require(minimumVisibilityTimeoutSeconds in 1..43_200) {
                "minimumVisibilityTimeoutSeconds must be between 1 and 43200."
            }
            require(pointerSigningKeyRef.isNotBlank() && pointerSigningKeyRef.none(::isControl)) {
                "pointerSigningKeyRef must be non-blank and control-free."
            }
            require(configuredSqsRetentionSeconds == null || configuredSqsRetentionSeconds > 0) {
                "configuredSqsRetentionSeconds must be positive when provided."
            }
            require(
                configuredMaxVisibilityRetryWindowSeconds == null ||
                    configuredMaxVisibilityRetryWindowSeconds in 1..604_740,
            ) {
                "configuredMaxVisibilityRetryWindowSeconds must be between 1 and 604740."
            }
            if (configuredMaxVisibilityRetryWindowSeconds != null) {
                val effectiveRollbackDeadline = rollbackDeadlineSeconds
                    ?: (configuredMaxVisibilityRetryWindowSeconds + 60).coerceAtMost(604_800)
                require(effectiveRollbackDeadline >= configuredMaxVisibilityRetryWindowSeconds) {
                    "rollback deadline must cover the visibility retry window."
                }
            }
            rollbackDeadlineSeconds?.let { deadline ->
                require(deadline in 1..604_800) { "rollbackDeadlineSeconds must be between 1 and 604800." }
                configuredMaxVisibilityRetryWindowSeconds?.let { retryWindow ->
                    require(deadline >= retryWindow) { "rollback deadline must cover the visibility retry window." }
                }
                require(deadline < orphanRetentionHours * 3_600L) {
                    "rollback deadline must be shorter than orphan retention."
                }
            }
            configuredMaxVisibilityRetryWindowSeconds?.let { retryWindow ->
                val effectiveRollbackDeadline = rollbackDeadlineSeconds
                    ?: (retryWindow + 60).coerceAtMost(604_800)
                require(effectiveRollbackDeadline < orphanRetentionHours * 3_600L) {
                    "rollback deadline must be shorter than orphan retention."
                }
            }
        }

        internal fun normalizedKeyPrefix(): String = keyPrefix.trimEnd('/') + "/"

        internal fun effectiveRollbackDeadlineSeconds(): Long =
            (rollbackDeadlineSeconds
                ?: configuredMaxVisibilityRetryWindowSeconds?.plus(60)
                ?: (orphanRetentionHours * 3_600 - 1)).toLong()

        override fun toString(): String =
            "SqsExtendedPolicy(offloadThresholdBytes=$offloadThresholdBytes, maxInlineBytes=$maxInlineBytes, maxOffloadPayloadBytes=$maxOffloadPayloadBytes, deleteOnAck=$deleteOnAck, encryptionEnabled=${encryption.enabled})"
    }

    data class QueuePolicy(
        val queueUrl: String,
        val policy: Policy,
    ) {
        init {
            requireCanonicalQueueUrl(queueUrl)
        }

        override fun toString(): String = "SqsExtendedQueuePolicy(queueConfigured=true)"
    }

    data class Encryption(
        val enabled: Boolean = false,
        val encryptionContext: Map<String, String> = emptyMap(),
        val keyFingerprint: String? = null,
    ) {
        init {
            encryptionContext.forEach { (name, value) ->
                require(name.isNotBlank() && name.none(::isControl)) {
                    "encryption context keys must be non-blank and control-free."
                }
                require(value.none(::isControl)) { "encryption context values must be control-free." }
            }
            if (enabled) {
                require(!keyFingerprint.isNullOrBlank()) {
                    "encryption.keyFingerprint is required when encryption is enabled."
                }
            }
        }

        override fun toString(): String =
            "SqsExtendedEncryption(enabled=$enabled, contextEntryCount=${encryptionContext.size}, keyFingerprintPresent=${keyFingerprint != null})"
    }

    class Security(
        private val pointerSigningKeysBase64Url: Map<String, String> = emptyMap(),
    ) {
        init {
            pointerSigningKeysBase64Url.keys.forEach { ref ->
                require(ref.isNotBlank() && ref.none(::isControl)) {
                    "signing key reference must be non-blank and control-free."
                }
            }
        }

        override fun toString(): String =
            "SqsExtendedClientSecurity(keyCount=${pointerSigningKeysBase64Url.size})"

        internal fun resolveSigningKey(ref: String): ByteArray {
            val encoded = requireNotNull(pointerSigningKeysBase64Url[ref]) {
                "configured signing key is missing."
            }
            val decoded = runCatching {
                Base64.getUrlDecoder().decode(encoded)
            }.getOrElse { throw IllegalArgumentException("configured signing key is not canonical base64url.") }
            require(decoded.size >= 32) { "configured signing key must be at least 32 bytes." }
            require(Base64.getUrlEncoder().withoutPadding().encodeToString(decoded) == encoded) {
                "configured signing key must use unpadded base64url."
            }
            return decoded.clone()
        }
    }

    private companion object {
        fun requireCanonicalQueueUrl(queueUrl: String) {
            require(
                queueUrl.startsWith("https://") &&
                    queueUrl.none(::isControl) &&
                    queueUrl == queueUrl.trim() &&
                    !queueUrl.contains('?') && !queueUrl.contains('#'),
            ) { "queueUrl must be a canonical HTTPS URL without query or fragment." }
        }

        fun isControl(value: Char): Boolean = value == '\r' || value == '\n' || value == '\u0000'
    }
}

private fun requireCanonicalQueueUrl(queueUrl: String) {
    require(
        queueUrl.startsWith("https://") &&
            queueUrl.none { it == '\r' || it == '\n' || it == '\u0000' } &&
            queueUrl == queueUrl.trim() &&
            !queueUrl.contains('?') && !queueUrl.contains('#'),
    ) { "queueUrl must be a canonical HTTPS URL without query or fragment." }
}
