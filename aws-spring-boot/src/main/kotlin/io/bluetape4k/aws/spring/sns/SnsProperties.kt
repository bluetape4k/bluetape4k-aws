package io.bluetape4k.aws.spring.sns

import org.springframework.boot.context.properties.ConfigurationProperties
import kotlin.jvm.internal.DefaultConstructorMarker
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
 *       account-id: 123456789012
 *       allow-cross-account-topic-arn: false
 *       topic-arn-cache:
 *         enabled: true
 *         max-size: 256
 *         ttl: 5m
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
    val topics: Map<String, Topic> = emptyMap(),
    val verification: Verification = Verification(),
    /** ARN scope 검증에 사용할 호출자 account ID입니다. */
    val accountId: String? = null,
    /** account ID가 다른 explicit ARN을 명시적으로 허용합니다. */
    val allowCrossAccountTopicArn: Boolean = false,
    /** topic name 조회 결과의 bounded cache 설정입니다. */
    val topicArnCache: TopicArnCache = TopicArnCache(),
): Serializable {
    /** 기존 5-인자 생성자와의 source/JVM descriptor 호환성을 유지합니다. */
    constructor(
        enabled: Boolean,
        region: String?,
        endpointOverride: URI?,
        topics: Map<String, Topic>,
        verification: Verification,
    ) : this(
        enabled = enabled,
        region = region,
        endpointOverride = endpointOverride,
        topics = topics,
        verification = verification,
        accountId = null,
        allowCrossAccountTopicArn = false,
        topicArnCache = TopicArnCache(),
    )

    /** Kotlin compiler가 생성하던 기존 5-인자 default constructor descriptor를 보존합니다. */
    @Suppress("UNUSED_PARAMETER")
    constructor(
        enabled: Boolean,
        region: String?,
        endpointOverride: URI?,
        topics: Map<String, Topic>,
        verification: Verification,
        mask: Int,
        marker: DefaultConstructorMarker?,
    ) : this(
        enabled = if (mask and COPY_ENABLED_MASK != 0) true else enabled,
        region = if (mask and COPY_REGION_MASK != 0) null else region,
        endpointOverride = if (mask and COPY_ENDPOINT_MASK != 0) null else endpointOverride,
        topics = if (mask and COPY_TOPICS_MASK != 0) emptyMap() else topics,
        verification = if (mask and COPY_VERIFICATION_MASK != 0) Verification() else verification,
        accountId = null,
        allowCrossAccountTopicArn = false,
        topicArnCache = TopicArnCache(),
    )

    /** 기존 5-인자 data-class copy 호출과의 source/JVM descriptor 호환성을 유지합니다. */
    fun copy(
        enabled: Boolean,
        region: String?,
        endpointOverride: URI?,
        topics: Map<String, Topic>,
        verification: Verification,
    ): SnsProperties = SnsProperties(
        enabled = enabled,
        region = region,
        endpointOverride = endpointOverride,
        topics = topics,
        verification = verification,
        accountId = accountId,
        allowCrossAccountTopicArn = allowCrossAccountTopicArn,
        topicArnCache = topicArnCache,
    )

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
        /** Kotlin compiler가 생성하던 기존 5-인자 `copy$default` descriptor를 보존합니다. */
        @JvmStatic
        @Suppress("FunctionNaming", "unused")
        fun `copy$default`(
            self: SnsProperties,
            enabled: Boolean,
            region: String?,
            endpointOverride: URI?,
            topics: Map<String, Topic>,
            verification: Verification,
            mask: Int,
            marker: Any?,
        ): SnsProperties {
            @Suppress("UNUSED_VARIABLE")
            val ignoredMarker = marker
            return self.copy(
                enabled = if (mask and COPY_ENABLED_MASK != 0) self.enabled else enabled,
                region = if (mask and COPY_REGION_MASK != 0) self.region else region,
                endpointOverride = if (mask and COPY_ENDPOINT_MASK != 0) self.endpointOverride else endpointOverride,
                topics = if (mask and COPY_TOPICS_MASK != 0) self.topics else topics,
                verification = if (mask and COPY_VERIFICATION_MASK != 0) self.verification else verification,
            )
        }

        private const val COPY_ENABLED_MASK: Int = 1
        private const val COPY_REGION_MASK: Int = 1 shl 1
        private const val COPY_ENDPOINT_MASK: Int = 1 shl 2
        private const val COPY_TOPICS_MASK: Int = 1 shl 3
        private const val COPY_VERIFICATION_MASK: Int = 1 shl 4
        private val ACCOUNT_ID_PATTERN: Regex = Regex("\\d{12}")
        private const val serialVersionUID: Long = 1L
    }
}
