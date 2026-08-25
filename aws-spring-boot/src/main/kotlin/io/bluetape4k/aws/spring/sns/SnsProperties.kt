package io.bluetape4k.aws.spring.sns

import org.springframework.boot.context.properties.ConfigurationProperties
import java.io.Serializable
import java.net.URI
import java.time.Duration

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
 *       verification:
 *         enabled: true
 * ```
 */
@ConfigurationProperties(prefix = "bluetape4k.aws.sns")
data class SnsProperties(
    val enabled: Boolean = true,
    val region: String? = null,
    val endpointOverride: URI? = null,
    val accountId: String? = null,
    val allowCrossAccountTopicArn: Boolean = false,
    val topicArnCache: TopicArnCache = TopicArnCache(),
    val topics: Map<String, Topic> = emptyMap(),
    val verification: Verification = Verification(),
): Serializable {
    init {
        require(endpointOverride == null || !region.isNullOrBlank()) {
            "bluetape4k.aws.sns.region is required when endpointOverride is configured."
        }
        accountId?.let {
            require(it.matches(ACCOUNT_ID_PATTERN)) {
                "bluetape4k.aws.sns.account-id must contain exactly 12 digits."
            }
        }
    }

    /**
     * name 기반 topic ARN 조회 cache 설정입니다.
     *
     * cache를 비활성화해도 같은 key에 대한 진행 중인 single-flight는 유지됩니다.
     */
    data class TopicArnCache(
        val enabled: Boolean = true,
        val maxSize: Int = InMemorySnsTopicArnCache.DEFAULT_MAX_SIZE,
        val ttl: Duration = InMemorySnsTopicArnCache.DEFAULT_TTL,
    ): Serializable {
        init {
            require(maxSize > 0) {
                "bluetape4k.aws.sns.topic-arn-cache.max-size must be greater than zero."
            }
            require(!ttl.isNegative && !ttl.isZero) {
                "bluetape4k.aws.sns.topic-arn-cache.ttl must be greater than zero."
            }
            require(ttl <= InMemorySnsTopicArnCache.MAX_TTL) {
                "bluetape4k.aws.sns.topic-arn-cache.ttl must not exceed 24 hours."
            }
        }

        companion object {
            private const val serialVersionUID: Long = 1L
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

    /**
     * SNS HTTP 메시지 서명 검증 자동 구성 속성입니다.
     *
     * 기본값은 활성화이며, 비활성화하면 자동 구성 verifier bean이 등록되지 않습니다.
     */
    data class Verification(
        val enabled: Boolean = true,
    ) : Serializable {
        companion object {
            private const val serialVersionUID: Long = 1L
        }
    }

    companion object {
        private val ACCOUNT_ID_PATTERN: Regex = Regex("\\d{12}")
        private const val serialVersionUID: Long = 1L
    }
}
