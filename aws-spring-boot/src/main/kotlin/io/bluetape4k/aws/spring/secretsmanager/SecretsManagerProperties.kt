package io.bluetape4k.aws.spring.secretsmanager

import io.bluetape4k.aws.spring.env.requireOptionalName
import io.bluetape4k.aws.spring.env.requireRegionWhenEndpointOverride
import org.springframework.boot.context.properties.ConfigurationProperties
import java.io.Serializable
import java.net.URI
import java.time.Duration

/**
 * Configuration properties for Secrets Manager Environment sources.
 *
 * ## Contract
 *
 * Binds `bluetape4k.aws.secrets-manager` and defines the remote secret sources
 * loaded during Spring Environment post-processing. No AWS request is made when
 * [sources] is empty.
 */
@ConfigurationProperties(prefix = "bluetape4k.aws.secrets-manager")
data class SecretsManagerProperties(
    val enabled: Boolean = true,
    val region: String? = null,
    val endpointOverride: URI? = null,
    val failFast: Boolean = true,
    val refreshInterval: Duration? = null,
    val sources: List<Source> = emptyList(),
): Serializable {
    init {
        requireRegionWhenEndpointOverride(endpointOverride, region, "bluetape4k.aws.secrets-manager")
        refreshInterval?.let {
            require(!it.isZero && !it.isNegative) { "refreshInterval must be positive." }
        }
    }

    /**
     * Single Secrets Manager secret source.
     */
    data class Source(
        val name: String? = null,
        val secretId: String = "",
        val prefix: String? = null,
        val optional: Boolean = false,
        val format: SecretFormat = SecretFormat.JSON,
    ): Serializable {
        init {
            requireOptionalName(name, "name")
            requireOptionalName(prefix, "prefix")
            require(secretId.isNotBlank()) { "secretId must not be blank." }
        }

        val propertySourceName: String
            get() = "bluetape4k.aws.secrets-manager.${name ?: secretId}"

        companion object {
            private const val serialVersionUID: Long = 1L
        }
    }

    companion object {
        private const val serialVersionUID: Long = 1L
    }
}
