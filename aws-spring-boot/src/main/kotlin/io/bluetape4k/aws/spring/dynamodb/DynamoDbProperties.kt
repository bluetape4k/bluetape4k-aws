package io.bluetape4k.aws.spring.dynamodb

import io.bluetape4k.support.requireNotBlank
import org.springframework.boot.context.properties.ConfigurationProperties
import java.io.Serializable
import java.net.URI
import java.time.Duration

/**
 * DynamoDB 자동 설정 속성.
 */
@ConfigurationProperties(prefix = "bluetape4k.aws.dynamodb")
data class DynamoDbProperties(
    val enabled: Boolean = true,
    val region: String? = null,
    val endpointOverride: URI? = null,
    val tablePrefix: String = "",
    val dax: DynamoDbDaxProperties = DynamoDbDaxProperties(),
): Serializable {
    init {
        require(endpointOverride == null || !region.isNullOrBlank()) {
            "bluetape4k.aws.dynamodb.region is required when endpointOverride is configured."
        }
    }

    companion object {
        private const val serialVersionUID: Long = -4626918242303634193L
    }
}

/**
 * DAX client properties for DynamoDB Accelerator integration.
 *
 * ## Contract
 *
 * These properties are used only when DAX auto-configuration is active. The DAX
 * SDK is optional; applications must add `software.amazon.dax:amazon-dax-client`
 * at runtime before enabling this section.
 */
data class DynamoDbDaxProperties(
    val enabled: Boolean = false,
    val url: URI? = null,
    val region: String? = null,
    val connectTimeout: Duration = Duration.ofSeconds(1),
    val requestTimeout: Duration = Duration.ofSeconds(1),
    val idleTimeout: Duration = Duration.ofSeconds(30),
    val connectionTtl: Duration = Duration.ZERO,
    val writeRetries: Int = 2,
    val readRetries: Int = 2,
    val clusterUpdateInterval: Duration = Duration.ofSeconds(4),
    val endpointRefreshTimeout: Duration = Duration.ofSeconds(6),
    val maxConcurrency: Int = 1000,
    val maxPendingConnectionAcquires: Int = 10000,
    val skipHostNameVerification: Boolean = false,
): Serializable {

    internal fun validateEnabled() {
        require(url != null) {
            "bluetape4k.aws.dynamodb.dax.url is required when DAX is enabled."
        }
        region?.requireNotBlank("bluetape4k.aws.dynamodb.dax.region")

        requireNonNegative(connectTimeout, "bluetape4k.aws.dynamodb.dax.connect-timeout")
        requireNonNegative(requestTimeout, "bluetape4k.aws.dynamodb.dax.request-timeout")
        requireNonNegative(idleTimeout, "bluetape4k.aws.dynamodb.dax.idle-timeout")
        requireNonNegative(connectionTtl, "bluetape4k.aws.dynamodb.dax.connection-ttl")
        requireNonNegative(clusterUpdateInterval, "bluetape4k.aws.dynamodb.dax.cluster-update-interval")
        requireNonNegative(endpointRefreshTimeout, "bluetape4k.aws.dynamodb.dax.endpoint-refresh-timeout")

        require(writeRetries >= 0) {
            "bluetape4k.aws.dynamodb.dax.write-retries must be greater than or equal to 0."
        }
        require(readRetries >= 0) {
            "bluetape4k.aws.dynamodb.dax.read-retries must be greater than or equal to 0."
        }
        require(maxConcurrency >= 0) {
            "bluetape4k.aws.dynamodb.dax.max-concurrency must be greater than or equal to 0."
        }
        require(maxPendingConnectionAcquires >= 0) {
            "bluetape4k.aws.dynamodb.dax.max-pending-connection-acquires must be greater than or equal to 0."
        }
    }

    internal fun Duration.toMillisInt(propertyName: String): Int {
        val millis = toMillis()
        require(millis in 0..Int.MAX_VALUE) {
            "$propertyName must be between 0 and ${Int.MAX_VALUE} milliseconds."
        }
        return millis.toInt()
    }

    private fun requireNonNegative(duration: Duration, propertyName: String) {
        require(!duration.isNegative) {
            "$propertyName must be greater than or equal to 0 milliseconds."
        }
    }

    companion object {
        private const val serialVersionUID: Long = -773737867078642545L
    }
}
