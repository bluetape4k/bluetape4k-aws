package io.bluetape4k.aws.spring.dynamodb

import io.bluetape4k.support.requireGe
import io.bluetape4k.support.requireInRange
import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.support.requireNotNull
import io.bluetape4k.support.requirePositiveNumber
import io.bluetape4k.support.requireZeroOrPositiveNumber
import org.springframework.boot.context.properties.ConfigurationProperties
import java.io.Serializable
import java.net.URI
import java.time.Duration

internal const val DYNAMODB_PROPERTIES_PREFIX: String = "bluetape4k.aws.dynamodb"
internal const val DYNAMODB_DAX_PROPERTIES_PREFIX: String = "$DYNAMODB_PROPERTIES_PREFIX.dax"

/**
 * DynamoDB 자동 구성용 구성 속성입니다.
 */
@ConfigurationProperties(prefix = DYNAMODB_PROPERTIES_PREFIX)
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
 * DynamoDB Accelerator 통합용 DAX 클라이언트 속성입니다.
 *
 * ## 계약
 *
 * 이 속성은 DAX 자동 구성이 활성화된 경우에만 사용합니다. DAX SDK는 선택 사항이므로
 * 이 구성을 활성화하기 전에 애플리케이션이 런타임에
 * `software.amazon.dax:amazon-dax-client`를 추가해야 합니다.
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
        url.requireNotNull("$DYNAMODB_DAX_PROPERTIES_PREFIX.url")
        region?.requireNotBlank("$DYNAMODB_DAX_PROPERTIES_PREFIX.region")

        requireNonNegative(connectTimeout, "$DYNAMODB_DAX_PROPERTIES_PREFIX.connect-timeout")
        requireNonNegative(requestTimeout, "$DYNAMODB_DAX_PROPERTIES_PREFIX.request-timeout")
        requireNonNegative(idleTimeout, "$DYNAMODB_DAX_PROPERTIES_PREFIX.idle-timeout")
        requireNonNegative(connectionTtl, "$DYNAMODB_DAX_PROPERTIES_PREFIX.connection-ttl")
        requireNonNegative(clusterUpdateInterval, "$DYNAMODB_DAX_PROPERTIES_PREFIX.cluster-update-interval")
        requireNonNegative(endpointRefreshTimeout, "$DYNAMODB_DAX_PROPERTIES_PREFIX.endpoint-refresh-timeout")

        writeRetries.requireZeroOrPositiveNumber("$DYNAMODB_DAX_PROPERTIES_PREFIX.write-retries")
        readRetries.requireZeroOrPositiveNumber("$DYNAMODB_DAX_PROPERTIES_PREFIX.read-retries")
        maxConcurrency.requirePositiveNumber("$DYNAMODB_DAX_PROPERTIES_PREFIX.max-concurrency")
        maxPendingConnectionAcquires.requirePositiveNumber(
            "$DYNAMODB_DAX_PROPERTIES_PREFIX.max-pending-connection-acquires"
        )
    }

    internal fun Duration.toMillisInt(propertyName: String): Int {
        val millis = toMillis()
        millis.requireInRange(0L, Int.MAX_VALUE.toLong(), propertyName)
        return millis.toInt()
    }

    private fun requireNonNegative(duration: Duration, propertyName: String) {
        duration.requireGe(Duration.ZERO, propertyName)
    }

    companion object {
        private const val serialVersionUID: Long = -773737867078642545L
    }
}
