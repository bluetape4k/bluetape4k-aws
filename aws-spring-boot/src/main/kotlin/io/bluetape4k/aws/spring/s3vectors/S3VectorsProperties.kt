package io.bluetape4k.aws.spring.s3vectors

import org.springframework.boot.context.properties.ConfigurationProperties
import java.io.Serializable
import java.net.URI

internal const val S3_VECTORS_PROPERTIES_PREFIX = "bluetape4k.aws.s3-vectors"

/**
 * Configuration properties for optional Amazon S3 Vectors integration.
 *
 * ## Contract
 *
 * S3 Vectors is disabled by default because it requires the optional
 * `software.amazon.awssdk:s3vectors` runtime dependency and a service-specific
 * IAM permission set. Service-specific region and endpoint values override the
 * shared `bluetape4k.aws` defaults.
 */
@ConfigurationProperties(prefix = S3_VECTORS_PROPERTIES_PREFIX)
data class S3VectorsProperties(
    val enabled: Boolean = false,
    val region: String? = null,
    val endpointOverride: URI? = null,
): Serializable {

    init {
        require(region == null || region.isNotBlank()) {
            "$S3_VECTORS_PROPERTIES_PREFIX.region must not be blank."
        }
    }

    companion object {
        private const val serialVersionUID: Long = 8809572045499301875L
    }
}
