package io.bluetape4k.aws.spring.cloudwatch

import io.bluetape4k.support.requireInRange
import org.springframework.boot.context.properties.ConfigurationProperties
import java.io.Serializable
import java.net.URI

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
