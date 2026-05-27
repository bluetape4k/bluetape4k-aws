package io.bluetape4k.aws.spring.s3

import io.bluetape4k.aws.spring.env.requireOptionalName
import io.bluetape4k.aws.spring.env.requireRegionWhenEndpointOverride
import org.springframework.boot.context.properties.ConfigurationProperties
import java.io.Serializable
import java.net.URI
import java.time.Duration

/**
 * Configuration properties for S3-backed Spring Environment sources.
 *
 * ## Contract
 *
 * Binds `bluetape4k.aws.s3.config` and defines S3 objects loaded during Spring
 * Environment post-processing. No AWS request is made when [sources] is empty.
 */
@ConfigurationProperties(prefix = "bluetape4k.aws.s3.config")
data class S3ConfigProperties(
    val enabled: Boolean = true,
    val region: String? = null,
    val endpointOverride: URI? = null,
    val pathStyleAccessEnabled: Boolean = false,
    val failFast: Boolean = true,
    val refreshInterval: Duration? = null,
    val sources: List<Source> = emptyList(),
) : Serializable {
    init {
        requireRegionWhenEndpointOverride(endpointOverride, region, "bluetape4k.aws.s3.config")
        refreshInterval?.let {
            require(!it.isZero && !it.isNegative) { "refreshInterval must be positive." }
        }
    }

    /**
     * Single S3 object source.
     */
    data class Source(
        val name: String? = null,
        val bucket: String = "",
        val key: String = "",
        val prefix: String? = null,
        val format: S3ConfigFormat = S3ConfigFormat.AUTO,
        val optional: Boolean = false,
    ) : Serializable {
        init {
            requireOptionalName(name, "name")
            requireOptionalName(prefix, "prefix")
            require(bucket.isNotBlank()) { "bucket must not be blank." }
            require(key.isNotBlank()) { "key must not be blank." }
        }

        val propertySourceName: String
            get() = "bluetape4k.aws.s3.config.${name ?: "$bucket/${key.trimStart('/')}".replace('/', '.')}"

        companion object {
            private const val serialVersionUID: Long = -8240629629794352855L
        }
    }

    companion object {
        private const val serialVersionUID: Long = 1331555039248527470L
    }
}

/**
 * Supported S3 config object formats.
 */
enum class S3ConfigFormat {
    AUTO,
    PROPERTIES,
    YAML,
    JSON,
}
