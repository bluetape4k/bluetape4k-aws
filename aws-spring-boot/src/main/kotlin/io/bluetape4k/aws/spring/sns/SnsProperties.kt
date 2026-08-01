package io.bluetape4k.aws.spring.sns

import org.springframework.boot.context.properties.ConfigurationProperties
import java.io.Serializable
import java.net.URI

/**
 * SNS 자동 구성용 구성 속성입니다.
 *
 * ## 계약
 *
 * `bluetape4k.aws.sns` 접두사를 바인딩하고 SDK 클라이언트 설정과
 * [SnsOperations.createConfiguredTopic]에서 사용하는 기본 주제 속성을 정의합니다.
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
     * 구성 기반 주제 생성에 사용하는 주제 속성입니다.
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
