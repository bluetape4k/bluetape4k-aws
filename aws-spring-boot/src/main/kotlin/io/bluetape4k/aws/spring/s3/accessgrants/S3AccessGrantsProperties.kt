package io.bluetape4k.aws.spring.s3.accessgrants

import org.springframework.boot.context.properties.ConfigurationProperties
import java.io.Serializable
import java.net.URI

internal const val S3_ACCESS_GRANTS_PROPERTIES_PREFIX = "bluetape4k.aws.s3.access-grants"

/**
 * S3 Access Grants 통합용 구성 속성입니다.
 *
 * ## 계약
 *
 * Access Grants에는 선택적인 `software.amazon.awssdk:s3control` 런타임 의존성과 AWS 계정 수준
 * S3 Control 권한이 필요하므로 기본적으로 비활성화됩니다. 서비스별 리전과 엔드포인트 값은
 * 공유 `bluetape4k.aws` 기본값보다 우선합니다.
 */
@ConfigurationProperties(prefix = S3_ACCESS_GRANTS_PROPERTIES_PREFIX)
data class S3AccessGrantsProperties(
    val enabled: Boolean = false,
    val region: String? = null,
    val endpointOverride: URI? = null,
): Serializable {

    init {
        require(region == null || region.isNotBlank()) {
            "$S3_ACCESS_GRANTS_PROPERTIES_PREFIX.region must not be blank."
        }
    }

    companion object {
        private const val serialVersionUID: Long = 7215929874561230194L
    }
}
