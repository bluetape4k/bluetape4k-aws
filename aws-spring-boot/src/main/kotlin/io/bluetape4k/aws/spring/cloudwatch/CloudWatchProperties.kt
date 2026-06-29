package io.bluetape4k.aws.spring.cloudwatch

import io.bluetape4k.support.requireInRange
import org.springframework.boot.context.properties.ConfigurationProperties
import java.io.Serializable
import java.net.URI

internal const val CLOUDWATCH_PROPERTIES_PREFIX = "bluetape4k.aws.cloudwatch"
internal const val CLOUDWATCH_LOGS_PROPERTIES_PREFIX = "bluetape4k.aws.cloudwatch-logs"

/**
 * Configuration properties for CloudWatch metric publishing.
 *
 * ## Contract
 *
 * Service-specific region and endpoint settings override shared
 * `bluetape4k.aws` defaults. The namespace is optional for methods that pass a
 * namespace explicitly and required for default-namespace publishing methods.
 */
@ConfigurationProperties(prefix = CLOUDWATCH_PROPERTIES_PREFIX)
data class CloudWatchProperties(
    val enabled: Boolean = true,
    val region: String? = null,
    val endpointOverride: URI? = null,
    val namespace: String? = null,
    val batchSize: Int = 1_000,
    val micrometer: Micrometer = Micrometer(),
): Serializable {

    init {
        require(endpointOverride == null || !region.isNullOrBlank()) {
            "$CLOUDWATCH_PROPERTIES_PREFIX.region is required when endpointOverride is configured."
        }
        batchSize.requireInRange(1, 1_000, "$CLOUDWATCH_PROPERTIES_PREFIX.batch-size")
    }

    data class Micrometer(
        val enabled: Boolean = true,
    ): Serializable {
        companion object {
            private const val serialVersionUID: Long = 5942778323434698764L
        }
    }

    companion object {
        private const val serialVersionUID: Long = 4873776473649119024L
    }
}

/**
 * Configuration properties for CloudWatch Logs event publishing.
 */
@ConfigurationProperties(prefix = CLOUDWATCH_LOGS_PROPERTIES_PREFIX)
data class CloudWatchLogsProperties(
    val enabled: Boolean = true,
    val region: String? = null,
    val endpointOverride: URI? = null,
    val logGroupName: String? = null,
    val logStreamName: String? = null,
    val batchSize: Int = 10_000,
): Serializable {

    init {
        require(endpointOverride == null || !region.isNullOrBlank()) {
            "$CLOUDWATCH_LOGS_PROPERTIES_PREFIX.region is required when endpointOverride is configured."
        }
        batchSize.requireInRange(1, 10_000, "$CLOUDWATCH_LOGS_PROPERTIES_PREFIX.batch-size")
    }

    companion object {
        private const val serialVersionUID: Long = -6218232676929083226L
    }
}
