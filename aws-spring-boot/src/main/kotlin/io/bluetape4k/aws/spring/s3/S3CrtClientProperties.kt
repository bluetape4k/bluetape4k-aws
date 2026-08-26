package io.bluetape4k.aws.spring.s3

import org.springframework.boot.context.properties.ConfigurationProperties
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation
import java.io.Serializable

/** AWS CRT S3 async client와 native multipart 전송 튜닝 속성입니다. */
@ConfigurationProperties(prefix = "bluetape4k.aws.s3.crt")
data class S3CrtClientProperties(
    /** 기본 SDK async client와의 예측 가능한 선택을 위해 opt-in입니다. */
    val enabled: Boolean = false,
    val targetThroughputInGbps: Double? = null,
    val maxConcurrency: Int? = null,
    val minimumPartSizeInBytes: Long? = null,
    val initialReadBufferSizeInBytes: Long? = null,
    val thresholdInBytes: Long? = null,
    val maxNativeMemoryLimitInBytes: Long? = null,
    val checksumValidationEnabled: Boolean? = null,
    val requestChecksumCalculation: RequestChecksumCalculation? = null,
    val responseChecksumValidation: ResponseChecksumValidation? = null,
) : Serializable {

    init {
        require(targetThroughputInGbps == null || targetThroughputInGbps > 0) {
            "bluetape4k.aws.s3.crt.targetThroughputInGbps must be greater than 0."
        }
        require(maxConcurrency == null || maxConcurrency > 0) {
            "bluetape4k.aws.s3.crt.maxConcurrency must be greater than 0."
        }
        require(minimumPartSizeInBytes == null || minimumPartSizeInBytes > 0) {
            "bluetape4k.aws.s3.crt.minimumPartSizeInBytes must be greater than 0."
        }
        require(initialReadBufferSizeInBytes == null || initialReadBufferSizeInBytes > 0) {
            "bluetape4k.aws.s3.crt.initialReadBufferSizeInBytes must be greater than 0."
        }
        require(thresholdInBytes == null || thresholdInBytes > 0) {
            "bluetape4k.aws.s3.crt.thresholdInBytes must be greater than 0."
        }
        require(maxNativeMemoryLimitInBytes == null || maxNativeMemoryLimitInBytes > 0) {
            "bluetape4k.aws.s3.crt.maxNativeMemoryLimitInBytes must be greater than 0."
        }
    }

    companion object {
        private const val serialVersionUID: Long = -2042877002159479823L
    }
}
