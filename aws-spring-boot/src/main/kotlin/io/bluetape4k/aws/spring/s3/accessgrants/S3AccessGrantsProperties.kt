package io.bluetape4k.aws.spring.s3.accessgrants

import org.springframework.boot.context.properties.ConfigurationProperties
import java.io.Serializable
import java.net.URI

internal const val S3_ACCESS_GRANTS_PROPERTIES_PREFIX = "bluetape4k.aws.s3.access-grants"

/**
 * Configuration properties for S3 Access Grants integration.
 *
 * ## Contract
 *
 * Access Grants is disabled by default because it requires the optional
 * `software.amazon.awssdk:s3control` runtime dependency and AWS account-level
 * S3 Control permissions. Service-specific region and endpoint values override
 * the shared `bluetape4k.aws` defaults.
 */
@ConfigurationProperties(prefix = S3_ACCESS_GRANTS_PROPERTIES_PREFIX)
data class S3AccessGrantsProperties(
    val enabled: Boolean = false,
    val region: String? = null,
    val endpointOverride: URI? = null,
): Serializable {

    init {
        require(region == null || region.isNotBlank()) {
            "$S3_ACCESS_GRANTS_PROPERTIES_PREFIX.region must not be blank."
        }
    }

    companion object {
        private const val serialVersionUID: Long = 7215929874561230194L
    }
}
