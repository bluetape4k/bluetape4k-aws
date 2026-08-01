package io.bluetape4k.aws.spring.secretsmanager

import io.bluetape4k.aws.spring.env.requireOptionalName
import io.bluetape4k.aws.spring.env.requireRegionWhenEndpointOverride
import org.springframework.boot.context.properties.ConfigurationProperties
import java.io.Serializable
import java.net.URI
import java.time.Duration

/**
 * Secrets Manager Environment 소스용 구성 속성입니다.
 *
 * ## 계약
 *
 * `bluetape4k.aws.secrets-manager`를 바인딩하고 Spring Environment 후처리 중 로드할
 * 원격 보안 소스를 정의합니다. [sources]가 비어 있으면 AWS를 요청하지 않습니다.
 */
@ConfigurationProperties(prefix = "bluetape4k.aws.secrets-manager")
data class SecretsManagerProperties(
    val enabled: Boolean = true,
    val region: String? = null,
    val endpointOverride: URI? = null,
    val failFast: Boolean = true,
    val refreshInterval: Duration? = null,
    val sources: List<Source> = emptyList(),
): Serializable {
    init {
        requireRegionWhenEndpointOverride(endpointOverride, region, "bluetape4k.aws.secrets-manager")
        refreshInterval?.let {
            require(!it.isZero && !it.isNegative) { "refreshInterval must be positive." }
        }
    }

    /**
     * 단일 Secrets Manager 보안 소스입니다.
     */
    data class Source(
        val name: String? = null,
        val secretId: String = "",
        val prefix: String? = null,
        val optional: Boolean = false,
        val format: SecretFormat = SecretFormat.JSON,
    ): Serializable {
        init {
            requireOptionalName(name, "name")
            requireOptionalName(prefix, "prefix")
            require(secretId.isNotBlank()) { "secretId must not be blank." }
        }

        val propertySourceName: String
            get() = "bluetape4k.aws.secrets-manager.${name ?: secretId}"

        companion object {
            private const val serialVersionUID: Long = 1L
        }
    }

    companion object {
        private const val serialVersionUID: Long = 1L
    }
}
