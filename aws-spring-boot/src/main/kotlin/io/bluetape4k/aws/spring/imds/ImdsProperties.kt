package io.bluetape4k.aws.spring.imds

import io.bluetape4k.support.requireGt
import io.bluetape4k.support.requireZeroOrPositiveNumber
import org.springframework.boot.context.properties.ConfigurationProperties
import software.amazon.awssdk.imds.EndpointMode
import java.io.Serializable
import java.net.URI
import java.time.Duration

internal const val IMDS_PROPERTIES_PREFIX = "bluetape4k.aws.imds"

/**
 * EC2 Instance Metadata Service 접근용 구성 속성입니다.
 *
 * ## 계약
 *
 * 시작 중 IMDS 접근은 수동적입니다. Bean을 생성해도 메타데이터 엔드포인트를 호출하지 않으며
 * 각 작업은 [requestTimeout]으로 제한됩니다.
 */
@ConfigurationProperties(prefix = IMDS_PROPERTIES_PREFIX)
data class ImdsProperties(
    val enabled: Boolean = true,
    val endpoint: URI? = null,
    val endpointMode: EndpointMode? = EndpointMode.IPV4,
    val tokenTtl: Duration = Duration.ofHours(6),
    val requestTimeout: Duration = Duration.ofSeconds(1),
    val retries: Int = 0,
): Serializable {

    init {
        tokenTtl.requireGt(Duration.ZERO, "$IMDS_PROPERTIES_PREFIX.token-ttl")
        requestTimeout.requireGt(Duration.ZERO, "$IMDS_PROPERTIES_PREFIX.request-timeout")
        retries.requireZeroOrPositiveNumber("$IMDS_PROPERTIES_PREFIX.retries")
    }

    companion object {
        private const val serialVersionUID: Long = -2835274381980128656L
    }
}
