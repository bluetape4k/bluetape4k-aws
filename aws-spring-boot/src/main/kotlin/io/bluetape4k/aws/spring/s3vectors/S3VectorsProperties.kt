package io.bluetape4k.aws.spring.s3vectors

import org.springframework.boot.context.properties.ConfigurationProperties
import java.io.Serializable
import java.net.URI

internal const val S3_VECTORS_PROPERTIES_PREFIX = "bluetape4k.aws.s3-vectors"

/**
 * 선택적인 Amazon S3 Vectors 통합용 구성 속성입니다.
 *
 * ## 계약
 *
 * S3 Vectors에는 선택적인 `software.amazon.awssdk:s3vectors` 런타임 의존성과 서비스별
 * IAM 권한 집합이 필요하므로 기본적으로 비활성화됩니다. 서비스별 리전과 엔드포인트 값은
 * 공유 `bluetape4k.aws` 기본값보다 우선합니다.
 */
@ConfigurationProperties(prefix = S3_VECTORS_PROPERTIES_PREFIX)
data class S3VectorsProperties(
    val enabled: Boolean = false,
    val region: String? = null,
    val endpointOverride: URI? = null,
): Serializable {

    init {
        require(region == null || region.isNotBlank()) {
            "$S3_VECTORS_PROPERTIES_PREFIX.region must not be blank."
        }
    }

    companion object {
        private const val serialVersionUID: Long = 8809572045499301875L
    }
}
