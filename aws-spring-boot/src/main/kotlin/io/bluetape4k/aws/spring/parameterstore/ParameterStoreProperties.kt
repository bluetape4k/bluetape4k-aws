package io.bluetape4k.aws.spring.parameterstore

import io.bluetape4k.aws.spring.env.requireOptionalName
import io.bluetape4k.aws.spring.env.requireRegionWhenEndpointOverride
import io.bluetape4k.support.requireGt
import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.support.requireStartsWith
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import java.io.Serializable
import java.net.URI
import java.time.Duration

/**
 * SSM Parameter Store Environment 소스용 구성 속성입니다.
 *
 * ## 계약
 *
 * `bluetape4k.aws.parameter-store`를 바인딩하고 Spring Environment 후처리 중 로드할
 * SSM 파라미터 경로를 정의합니다. [sources]가 비어 있으면 AWS를 요청하지 않습니다.
 */
@EnableConfigurationProperties(ParameterStoreProperties::class)
@ConfigurationProperties(prefix = "bluetape4k.aws.parameter-store")
data class ParameterStoreProperties(
    val enabled: Boolean = true,
    val region: String? = null,
    val endpointOverride: URI? = null,
    val failFast: Boolean = true,
    val refreshInterval: Duration? = null,
    val sources: List<Source> = emptyList(),
): Serializable {
    init {
        requireRegionWhenEndpointOverride(endpointOverride, region, "bluetape4k.aws.parameter-store")
        refreshInterval?.requireGt(Duration.ZERO) { "refreshInterval must be positive" }
    }

    /**
     * 단일 Parameter Store 경로 소스입니다.
     */
    data class Source(
        val name: String? = null,
        val path: String = "",
        val prefix: String? = null,
        val recursive: Boolean = true,
        val withDecryption: Boolean = true,
        val optional: Boolean = false,
    ): Serializable {
        init {
            requireOptionalName(name, "name")
            requireOptionalName(prefix, "prefix")
            path.requireNotBlank("path")
            path.requireStartsWith("/", "path")
        }

        val propertySourceName: String by lazy {
            "bluetape4k.aws.parameter-store.${name ?: path.trim('/').replace('/', '.')}"
        }

        companion object {
            private const val serialVersionUID: Long = 1L
        }
    }

    companion object {
        private const val serialVersionUID: Long = 1L
    }
}
