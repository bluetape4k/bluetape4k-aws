package io.bluetape4k.aws.spring.ses

import io.bluetape4k.aws.spring.ses.SesProperties.Companion.PREFIX
import io.bluetape4k.support.requireNotBlank
import org.springframework.boot.context.properties.ConfigurationProperties
import java.io.Serializable
import java.net.URI

/**
 * SES 자동 구성용 구성 속성입니다.
 *
 * ## 계약
 *
 * `bluetape4k.aws.ses`를 바인딩하고 SDK 클라이언트 설정과 [SesCoroutinesMailSender]에서
 * 사용하는 발신자 기본값을 정의합니다.
 */
@ConfigurationProperties(prefix = PREFIX)
data class SesProperties(
    val enabled: Boolean = true,
    val region: String? = null,
    val endpointOverride: URI? = null,
    val defaultFrom: String? = null,
    val configurationSetName: String? = null,
    val javaMailSender: JavaMailSenderProperties = JavaMailSenderProperties(),
): Serializable {

    companion object {
        const val PREFIX = "bluetape4k.aws.ses"
        private const val serialVersionUID: Long = 1L
    }

    init {
        require(endpointOverride == null || !region.isNullOrBlank()) {
            "$PREFIX.region is required when endpointOverride is configured."
        }
        defaultFrom?.requireEmailHeaderValue("defaultFrom")
        configurationSetName?.requireNotBlank { "configurationSetName must not be blank." }
    }

    /**
     * 선택적인 Spring [org.springframework.mail.javamail.JavaMailSender] 어댑터를 제어합니다.
     */
    data class JavaMailSenderProperties(
        val enabled: Boolean = true,
    ): Serializable {

        companion object {
            private const val serialVersionUID: Long = 1L
        }
    }
}
