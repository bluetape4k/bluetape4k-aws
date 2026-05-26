package io.bluetape4k.aws.spring.s3

import org.springframework.boot.context.properties.ConfigurationProperties
import java.io.Serializable
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
    val transfer: Transfer = Transfer(),
) : Serializable {
    data class Presign(
        val duration: Duration = Duration.ofMinutes(15),
    ) : Serializable {
        companion object {
            private const val serialVersionUID: Long = 6142960232232931590L
        }
    }

    data class Transfer(
        val enabled: Boolean = true,
        val uploadDirectoryMaxDepth: Int? = null,
        val transferDirectoryMaxConcurrency: Int? = null,
    ) : Serializable {
        init {
            require(uploadDirectoryMaxDepth == null || uploadDirectoryMaxDepth >= 0) {
                "bluetape4k.aws.s3.transfer.uploadDirectoryMaxDepth must be greater than or equal to 0."
            }
            require(transferDirectoryMaxConcurrency == null || transferDirectoryMaxConcurrency > 0) {
                "bluetape4k.aws.s3.transfer.transferDirectoryMaxConcurrency must be greater than 0."
            }
        }

        companion object {
            private const val serialVersionUID: Long = 3189014737041398681L
        }
    }

    companion object {
        private const val serialVersionUID: Long = -710482694906352408L
    }
}
