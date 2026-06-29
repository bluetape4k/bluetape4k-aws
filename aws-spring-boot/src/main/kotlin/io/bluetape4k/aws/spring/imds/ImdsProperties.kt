package io.bluetape4k.aws.spring.imds

import io.bluetape4k.support.requireGt
import io.bluetape4k.support.requireZeroOrPositiveNumber
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
        tokenTtl.requireGt(Duration.ZERO, "$IMDS_PROPERTIES_PREFIX.token-ttl")
        requestTimeout.requireGt(Duration.ZERO, "$IMDS_PROPERTIES_PREFIX.request-timeout")
        retries.requireZeroOrPositiveNumber("$IMDS_PROPERTIES_PREFIX.retries")
    }

    companion object {
        private const val serialVersionUID: Long = -2835274381980128656L
    }
}
