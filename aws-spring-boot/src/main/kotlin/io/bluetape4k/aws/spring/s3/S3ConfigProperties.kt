package io.bluetape4k.aws.spring.s3

import io.bluetape4k.aws.spring.env.requireOptionalName
import io.bluetape4k.aws.spring.env.requireRegionWhenEndpointOverride
import org.springframework.boot.context.properties.ConfigurationProperties
import java.io.Serializable
import java.net.URI
import java.time.Duration

/**
 * S3 기반 Spring Environment 소스용 구성 속성입니다.
 *
 * ## 계약
 *
 * `bluetape4k.aws.s3.config`를 바인딩하고 Spring Environment 후처리 중 로드할 S3 객체를
 * 정의합니다. [sources]가 비어 있으면 AWS를 요청하지 않습니다.
 */
@ConfigurationProperties(prefix = "bluetape4k.aws.s3.config")
data class S3ConfigProperties(
    val enabled: Boolean = true,
    val region: String? = null,
    val endpointOverride: URI? = null,
    val pathStyleAccessEnabled: Boolean = false,
    val failFast: Boolean = true,
    val refreshInterval: Duration? = null,
    val sources: List<Source> = emptyList(),
) : Serializable {
    init {
        requireRegionWhenEndpointOverride(endpointOverride, region, "bluetape4k.aws.s3.config")
        refreshInterval?.let {
            require(!it.isZero && !it.isNegative) { "refreshInterval must be positive." }
        }
    }

    /**
     * 단일 S3 객체 소스입니다.
     */
    data class Source(
        val name: String? = null,
        val bucket: String = "",
        val key: String = "",
        val prefix: String? = null,
        val format: S3ConfigFormat = S3ConfigFormat.AUTO,
        val optional: Boolean = false,
    ) : Serializable {
        init {
            requireOptionalName(name, "name")
            requireOptionalName(prefix, "prefix")
            require(bucket.isNotBlank()) { "bucket must not be blank." }
            require(key.isNotBlank()) { "key must not be blank." }
        }

        val propertySourceName: String
            get() = "bluetape4k.aws.s3.config.${name ?: "$bucket/${key.trimStart('/')}".replace('/', '.')}"

        companion object {
            private const val serialVersionUID: Long = -8240629629794352855L
        }
    }

    companion object {
        private const val serialVersionUID: Long = 1331555039248527470L
    }
}

/**
 * 지원하는 S3 구성 객체 형식입니다.
 */
enum class S3ConfigFormat {
    AUTO,
    PROPERTIES,
    YAML,
    JSON,
}
