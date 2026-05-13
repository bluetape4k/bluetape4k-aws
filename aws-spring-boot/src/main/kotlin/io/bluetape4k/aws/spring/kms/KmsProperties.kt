package io.bluetape4k.aws.spring.kms

import org.springframework.boot.context.properties.ConfigurationProperties
import software.amazon.awssdk.services.kms.model.DataKeySpec
import software.amazon.awssdk.services.kms.model.EncryptionAlgorithmSpec
import java.net.URI
import java.time.Duration

/**
 * Configuration properties for AWS KMS Spring Boot support.
 *
 * ## Contract
 * - `keyId` is required for encryption and data-key generation unless the caller supplies one per operation.
 * - `endpointOverride` requires an explicit `region` so LocalStack and custom endpoints are deterministic.
 * - `dataKeyCache` stores plaintext data keys in memory and should use conservative TTL and size limits.
 *
 * ```yaml
 * bluetape4k:
 *   aws:
 *     kms:
 *       region: ap-northeast-2
 *       key-id: alias/app
 *       encryption-context:
 *         service: order-api
 * ```
 */
@ConfigurationProperties(prefix = "bluetape4k.aws.kms")
data class KmsProperties(
    val enabled: Boolean = true,
    val region: String? = null,
    val endpointOverride: URI? = null,
    val keyId: String? = null,
    val encryptionContext: Map<String, String> = emptyMap(),
    val encryptionAlgorithm: EncryptionAlgorithmSpec? = null,
    val dataKey: DataKey = DataKey(),
    val dataKeyCache: DataKeyCache = DataKeyCache(),
    val textEncryptor: TextEncryptor = TextEncryptor(),
) {
    init {
        require(endpointOverride == null || !region.isNullOrBlank()) {
            "bluetape4k.aws.kms.region is required when endpointOverride is configured."
        }
        keyId?.let { require(it.isNotBlank()) { "bluetape4k.aws.kms.key-id must not be blank." } }
    }

    data class DataKey(
        val keySpec: DataKeySpec = DataKeySpec.AES_256,
    )

    data class DataKeyCache(
        val enabled: Boolean = true,
        val maxSize: Int = 64,
        val ttl: Duration = Duration.ofMinutes(5),
    ) {
        init {
            require(maxSize > 0) { "bluetape4k.aws.kms.data-key-cache.max-size must be greater than 0." }
            require(!ttl.isNegative && !ttl.isZero) {
                "bluetape4k.aws.kms.data-key-cache.ttl must be greater than zero."
            }
        }
    }

    data class TextEncryptor(
        val enabled: Boolean = true,
    )
}
