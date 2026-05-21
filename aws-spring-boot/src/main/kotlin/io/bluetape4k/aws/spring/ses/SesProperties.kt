package io.bluetape4k.aws.spring.ses

import org.springframework.boot.context.properties.ConfigurationProperties
import java.io.Serializable
import java.net.URI

/**
 * Configuration properties for SES auto-configuration.
 *
 * ## Contract
 *
 * Binds `bluetape4k.aws.ses` and defines SDK client settings plus sender
 * defaults used by [SesCoroutinesMailSender].
 */
@ConfigurationProperties(prefix = "bluetape4k.aws.ses")
data class SesProperties(
    val enabled: Boolean = true,
    val region: String? = null,
    val endpointOverride: URI? = null,
    val defaultFrom: String? = null,
    val configurationSetName: String? = null,
    val javaMailSender: JavaMailSenderProperties = JavaMailSenderProperties(),
): Serializable {

    init {
        require(endpointOverride == null || !region.isNullOrBlank()) {
            "bluetape4k.aws.ses.region is required when endpointOverride is configured."
        }
        defaultFrom?.requireEmailHeaderValue("defaultFrom")
        configurationSetName?.let {
            require(it.isNotBlank()) { "configurationSetName must not be blank." }
        }
    }

    /**
     * Controls the optional Spring [org.springframework.mail.javamail.JavaMailSender] adapter.
     */
    data class JavaMailSenderProperties(
        val enabled: Boolean = true,
    ): Serializable {

        companion object {
            private const val serialVersionUID: Long = 1L
        }
    }

    companion object {
        private const val serialVersionUID: Long = 1L
    }
}
