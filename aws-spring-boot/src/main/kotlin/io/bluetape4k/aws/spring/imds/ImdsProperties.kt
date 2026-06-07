package io.bluetape4k.aws.spring.imds

import org.springframework.boot.context.properties.ConfigurationProperties
import software.amazon.awssdk.imds.EndpointMode
import java.io.Serializable
import java.net.URI
import java.time.Duration

internal const val IMDS_PROPERTIES_PREFIX = "bluetape4k.aws.imds"

/**
 * Configuration properties for EC2 Instance Metadata Service access.
 *
 * ## Contract
 *
 * IMDS access is passive during startup. Bean creation does not call the
 * metadata endpoint; each operation is bounded by [requestTimeout].
 */
@ConfigurationProperties(prefix = IMDS_PROPERTIES_PREFIX)
data class ImdsProperties(
    val enabled: Boolean = true,
    val endpoint: URI? = null,
    val endpointMode: EndpointMode? = EndpointMode.IPV4,
    val tokenTtl: Duration = Duration.ofHours(6),
    val requestTimeout: Duration = Duration.ofSeconds(1),
    val retries: Int = 0,
): Serializable {

    init {
        require(!tokenTtl.isNegative && !tokenTtl.isZero) {
            "$IMDS_PROPERTIES_PREFIX.token-ttl must be positive."
        }
        require(!requestTimeout.isNegative && !requestTimeout.isZero) {
            "$IMDS_PROPERTIES_PREFIX.request-timeout must be positive."
        }
        require(retries >= 0) {
            "$IMDS_PROPERTIES_PREFIX.retries must be greater than or equal to 0."
        }
    }

    companion object {
        private const val serialVersionUID: Long = -2835274381980128656L
    }
}
