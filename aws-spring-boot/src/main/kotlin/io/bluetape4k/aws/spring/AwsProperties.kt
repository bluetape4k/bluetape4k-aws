package io.bluetape4k.aws.spring

import org.springframework.boot.context.properties.ConfigurationProperties
import java.io.Serializable
import java.net.URI
import java.nio.file.Path

/**
 * bluetape4k Spring Boot 자동 구성의 공유 AWS 기본값입니다.
 *
 * ## 계약
 *
 * 서비스별 속성은 이 기본값보다 우선합니다. 공유 엔드포인트는 로컬 AWS 에뮬레이터에 유용합니다.
 * Web Identity 자격 증명은 옵트인이므로 애플리케이션은 공통 자격 증명 Bean을 교체하지 않고
 * EKS/IRSA 방식 배포를 사용할 수 있습니다.
 */
@ConfigurationProperties(prefix = "bluetape4k.aws")
data class AwsProperties(
    val enabled: Boolean = true,
    val region: String? = null,
    val endpointOverride: URI? = null,
    val credentials: Credentials = Credentials(),
): Serializable {

    init {
        require(endpointOverride == null || !region.isNullOrBlank()) {
            "bluetape4k.aws.region is required when endpoint-override is configured."
        }
    }

    data class Credentials(
        val webIdentity: WebIdentity = WebIdentity(),
    ): Serializable {
        companion object {
            private const val serialVersionUID: Long = -8718709604652897705L
        }
    }

    data class WebIdentity(
        val enabled: Boolean = false,
        val roleArn: String? = null,
        val roleSessionName: String? = null,
        val tokenFile: Path? = null,
    ): Serializable {
        companion object {
            private const val serialVersionUID: Long = -7501764107485657850L
        }
    }

    companion object {
        private const val serialVersionUID: Long = 7949942656398501048L
    }
}
