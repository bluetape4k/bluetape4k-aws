package io.bluetape4k.aws.spring.s3

import org.springframework.boot.context.properties.ConfigurationProperties
import java.net.URI
import java.time.Duration

/**
 * S3 자동 설정 속성.
 */
@ConfigurationProperties(prefix = "bluetape4k.aws.s3")
data class S3Properties(
    val enabled: Boolean = true,
    val region: String? = null,
    val endpointOverride: URI? = null,
    val pathStyleAccessEnabled: Boolean = false,
    val accelerateModeEnabled: Boolean = false,
    val chunkedEncodingEnabled: Boolean? = null,
    val presign: Presign = Presign(),
) {
    init {
        require(endpointOverride == null || !region.isNullOrBlank()) {
            "bluetape4k.aws.s3.region is required when endpointOverride is configured."
        }
    }

    data class Presign(
        val duration: Duration = Duration.ofMinutes(15),
    )
}
