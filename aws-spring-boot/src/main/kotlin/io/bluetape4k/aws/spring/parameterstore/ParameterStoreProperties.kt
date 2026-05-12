package io.bluetape4k.aws.spring.parameterstore

import io.bluetape4k.aws.spring.env.requireOptionalName
import io.bluetape4k.aws.spring.env.requireRegionWhenEndpointOverride
import org.springframework.boot.context.properties.ConfigurationProperties
import java.net.URI

/**
 * Configuration properties for SSM Parameter Store Environment sources.
 *
 * ## Contract
 *
 * Binds `bluetape4k.aws.parameter-store` and defines the SSM parameter paths
 * loaded during Spring Environment post-processing. No AWS request is made when
 * [sources] is empty.
 */
@ConfigurationProperties(prefix = "bluetape4k.aws.parameter-store")
data class ParameterStoreProperties(
    val enabled: Boolean = true,
    val region: String? = null,
    val endpointOverride: URI? = null,
    val failFast: Boolean = true,
    val sources: List<Source> = emptyList(),
) {
    init {
        requireRegionWhenEndpointOverride(endpointOverride, region, "bluetape4k.aws.parameter-store")
    }

    /**
     * Single Parameter Store path source.
     */
    data class Source(
        val name: String? = null,
        val path: String = "",
        val prefix: String? = null,
        val recursive: Boolean = true,
        val withDecryption: Boolean = true,
        val optional: Boolean = false,
    ) {
        init {
            requireOptionalName(name, "name")
            requireOptionalName(prefix, "prefix")
            require(path.isNotBlank()) { "path must not be blank." }
            require(path.startsWith("/")) { "path must start with /." }
        }

        val propertySourceName: String
            get() = "bluetape4k.aws.parameter-store.${name ?: path.trim('/').replace('/', '.')}"
    }
}

