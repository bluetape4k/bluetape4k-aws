package io.bluetape4k.aws.spring.sns

import org.springframework.boot.context.properties.ConfigurationProperties
import java.io.Serializable
import java.net.URI

/**
 * Configuration properties for SNS auto-configuration.
 *
 * ## Contract
 *
 * Binds the `bluetape4k.aws.sns` prefix and defines SDK client settings plus
 * default topic attributes used by [SnsOperations.createConfiguredTopic].
 *
 * ```yaml
 * bluetape4k:
 *   aws:
 *     sns:
 *       region: ap-northeast-2
 *       topics:
 *         orders:
 *           attributes:
 *             Environment: prod
 * ```
 */
@ConfigurationProperties(prefix = "bluetape4k.aws.sns")
data class SnsProperties(
    val enabled: Boolean = true,
    val region: String? = null,
    val endpointOverride: URI? = null,
    val topics: Map<String, Topic> = emptyMap(),
): Serializable {
    init {
        require(endpointOverride == null || !region.isNullOrBlank()) {
            "bluetape4k.aws.sns.region is required when endpointOverride is configured."
        }
    }

    /**
     * Topic properties used by configuration-driven topic creation.
     */
    data class Topic(
        val fifo: Boolean = false,
        val contentBasedDeduplication: Boolean = true,
        val fifoThroughputScope: SnsFifoThroughputScope? = null,
        val attributes: Map<String, String> = emptyMap(),
    ): Serializable {
        companion object {
            private const val serialVersionUID: Long = 1L
        }
    }

    companion object {
        private const val serialVersionUID: Long = 1L
    }
}
