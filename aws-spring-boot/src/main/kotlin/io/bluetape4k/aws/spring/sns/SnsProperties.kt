package io.bluetape4k.aws.spring.sns

import org.springframework.boot.context.properties.ConfigurationProperties
import java.net.URI

/**
 * SNS 자동 설정 속성.
 *
 * `bluetape4k.aws.sns` prefix로 바인딩되며, SDK client region/endpoint와
 * 애플리케이션에서 생성할 topic 기본 속성을 정의합니다.
 */
@ConfigurationProperties(prefix = "bluetape4k.aws.sns")
data class SnsProperties(
    val enabled: Boolean = true,
    val region: String? = null,
    val endpointOverride: URI? = null,
    val topics: Map<String, Topic> = emptyMap(),
) {
    init {
        require(endpointOverride == null || !region.isNullOrBlank()) {
            "bluetape4k.aws.sns.region is required when endpointOverride is configured."
        }
    }

    /**
     * 설정 기반 topic 생성에 사용할 속성.
     */
    data class Topic(
        val fifo: Boolean = false,
        val contentBasedDeduplication: Boolean = true,
        val fifoThroughputScope: SnsFifoThroughputScope? = null,
        val attributes: Map<String, String> = emptyMap(),
    )
}
