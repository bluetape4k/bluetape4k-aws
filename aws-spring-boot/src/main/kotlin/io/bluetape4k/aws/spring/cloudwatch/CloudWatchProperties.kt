package io.bluetape4k.aws.spring.cloudwatch

import io.bluetape4k.support.requireInRange
import org.springframework.boot.context.properties.ConfigurationProperties
import java.io.Serializable
import java.net.URI
import java.time.Duration

internal const val CLOUDWATCH_PROPERTIES_PREFIX = "bluetape4k.aws.cloudwatch"
internal const val CLOUDWATCH_LOGS_PROPERTIES_PREFIX = "bluetape4k.aws.cloudwatch-logs"

/**
 * CloudWatch 메트릭 게시용 구성 속성입니다.
 *
 * ## 계약
 *
 * 서비스별 리전과 엔드포인트 설정은 공유 `bluetape4k.aws` 기본값보다 우선합니다.
 * 네임스페이스를 명시적으로 전달하는 메서드에는 선택 사항이며 기본 네임스페이스 게시
 * 메서드에는 필수입니다.
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
        val registry: Registry = Registry(),
    ): Serializable {
        data class Registry(
            val enabled: Boolean = false,
            val namespace: String? = null,
            val step: Duration = Duration.ofMinutes(DEFAULT_STEP_MINUTES),
            val batchSize: Int = DEFAULT_BATCH_SIZE,
            val readTimeout: Duration = Duration.ofSeconds(DEFAULT_READ_TIMEOUT_SECONDS),
            val commonTags: Map<String, String> = emptyMap(),
            val filters: Filters = Filters(),
        ): Serializable {
            init {
                batchSize.requireInRange(
                    MIN_BATCH_SIZE,
                    MAX_BATCH_SIZE,
                    "$CLOUDWATCH_PROPERTIES_PREFIX.micrometer.registry.batch-size",
                )
                require(step >= MIN_STEP) {
                    "$CLOUDWATCH_PROPERTIES_PREFIX.micrometer.registry.step must be at least 1s."
                }
                require(readTimeout >= MIN_READ_TIMEOUT && readTimeout <= MAX_READ_TIMEOUT) {
                    "$CLOUDWATCH_PROPERTIES_PREFIX.micrometer.registry.read-timeout must be between 1s and 5m."
                }
                commonTags.forEach { (key, value) ->
                    require(key.isNotBlank() && value.isNotBlank()) {
                        "$CLOUDWATCH_PROPERTIES_PREFIX.micrometer.registry.common-tags must not contain " +
                            "blank keys or values."
                    }
                }
                (filters.includes + filters.excludes).forEach { prefix ->
                    require(prefix.isNotBlank()) {
                        "$CLOUDWATCH_PROPERTIES_PREFIX.micrometer.registry.filters prefixes must not be blank."
                    }
                }
            }

            companion object {
                private const val DEFAULT_STEP_MINUTES: Long = 1
                private const val DEFAULT_BATCH_SIZE: Int = 20
                private const val DEFAULT_READ_TIMEOUT_SECONDS: Long = 10
                private const val MIN_BATCH_SIZE: Int = 1
                private const val MAX_BATCH_SIZE: Int = 1_000
                private val MIN_STEP: Duration = Duration.ofSeconds(1)
                private val MIN_READ_TIMEOUT: Duration = Duration.ofSeconds(1)
                private val MAX_READ_TIMEOUT: Duration = Duration.ofMinutes(5)
                private const val serialVersionUID: Long = -3585477261009725865L
            }
        }

        data class Filters(
            val includes: List<String> = emptyList(),
            val excludes: List<String> = emptyList(),
        ): Serializable {
            companion object {
                private const val serialVersionUID: Long = 7100803681444627909L
            }
        }

        companion object {
            private const val serialVersionUID: Long = 5942778323434698764L
        }
    }

    companion object {
        private const val serialVersionUID: Long = 4873776473649119024L
    }
}

/**
 * CloudWatch Logs 이벤트 게시용 구성 속성입니다.
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
