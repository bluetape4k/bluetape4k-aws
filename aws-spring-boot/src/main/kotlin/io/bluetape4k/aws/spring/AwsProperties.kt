package io.bluetape4k.aws.spring

import org.springframework.boot.context.properties.ConfigurationProperties
import java.io.Serializable
import java.net.URI
import java.nio.file.Path

/**
 * Shared AWS defaults for bluetape4k Spring Boot auto-configuration.
 *
 * ## Contract
 *
 * Service-specific properties override these defaults. The shared endpoint is
 * useful for local AWS emulators, and web-identity credentials are opt-in so
 * applications can use EKS/IRSA-style deployments without replacing the common
 * credentials bean.
 */
@ConfigurationProperties(prefix = "bluetape4k.aws")
data class AwsProperties(
    val enabled: Boolean = true,
    val region: String? = null,
    val endpointOverride: URI? = null,
    val credentials: Credentials = Credentials(),
): Serializable {

    init {
        require(endpointOverride == null || !region.isNullOrBlank()) {
            "bluetape4k.aws.region is required when endpoint-override is configured."
        }
    }

    data class Credentials(
        val webIdentity: WebIdentity = WebIdentity(),
    ): Serializable {
        companion object {
            private const val serialVersionUID: Long = -8718709604652897705L
        }
    }

    data class WebIdentity(
        val enabled: Boolean = false,
        val roleArn: String? = null,
        val roleSessionName: String? = null,
        val tokenFile: Path? = null,
    ): Serializable {
        companion object {
            private const val serialVersionUID: Long = -7501764107485657850L
        }
    }

    companion object {
        private const val serialVersionUID: Long = 7949942656398501048L
    }
}
