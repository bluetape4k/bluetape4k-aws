package io.bluetape4k.aws.spring.s3

import org.springframework.boot.context.properties.ConfigurationProperties
import java.io.Serializable
import java.net.URI
import java.time.Duration

private const val DEFAULT_OUTPUT_STREAM_THRESHOLD_BYTES: Long = 8L * 1024 * 1024
private const val DEFAULT_OUTPUT_STREAM_PART_SIZE_BYTES: Long = 8L * 1024 * 1024

enum class ClientSideEncryptionProvider {
    KMS,
    AES,
    RSA,
}

/**
 * Spring Boot S3 지원용 구성 속성입니다.
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
    val clientSideEncryption: ClientSideEncryption = ClientSideEncryption(),
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
        /** 메모리에서 임시 파일로 전환하는 스트림 누적 한계입니다. */
        val outputStreamThresholdBytes: Long = DEFAULT_OUTPUT_STREAM_THRESHOLD_BYTES,
        /** TransferManager multipart 업로드를 위한 권장 part 크기입니다. */
        val outputStreamPartSizeBytes: Long = DEFAULT_OUTPUT_STREAM_PART_SIZE_BYTES,
    ) : Serializable {
        init {
            require(uploadDirectoryMaxDepth == null || uploadDirectoryMaxDepth >= 0) {
                "bluetape4k.aws.s3.transfer.uploadDirectoryMaxDepth must be greater than or equal to 0."
            }
            require(transferDirectoryMaxConcurrency == null || transferDirectoryMaxConcurrency > 0) {
                "bluetape4k.aws.s3.transfer.transferDirectoryMaxConcurrency must be greater than 0."
            }
            require(outputStreamThresholdBytes > 0) {
                "bluetape4k.aws.s3.transfer.outputStreamThresholdBytes must be greater than 0."
            }
            require(outputStreamPartSizeBytes > 0) {
                "bluetape4k.aws.s3.transfer.outputStreamPartSizeBytes must be greater than 0."
            }
        }

        companion object {
            private const val serialVersionUID: Long = 3189014737041398681L
        }
    }

    /**
     * S3 객체 페이로드의 클라이언트 측 봉투 암호화 설정입니다.
     */
    data class ClientSideEncryption(
        val enabled: Boolean = false,
        val keyId: String? = null,
        val encryptionContext: Map<String, String> = emptyMap(),
        val useDataKeyCache: Boolean = true,
        // 기존 positional source call의 의미를 유지하도록 새 parameter를 뒤에 추가합니다.
        val provider: ClientSideEncryptionProvider = ClientSideEncryptionProvider.KMS,
        val keyVersion: String? = null,
    ) : Serializable {
        init {
            require(keyId == null || keyId.isSafeCseToken("keyId")) {
                "bluetape4k.aws.s3.client-side-encryption.keyId must not be blank or contain control characters."
            }
            require(keyVersion == null || keyVersion.isSafeCseToken("keyVersion")) {
                "bluetape4k.aws.s3.client-side-encryption.keyVersion must not be blank or contain control characters."
            }
            require(encryptionContext.keys.none { it.isBlank() || it.any(Char::isISOControl) }) {
                "bluetape4k.aws.s3.client-side-encryption.encryptionContext keys must not be blank " +
                    "or contain control characters."
            }
            require(encryptionContext.values.none { it.any(Char::isISOControl) }) {
                "bluetape4k.aws.s3.client-side-encryption.encryptionContext values must not contain control characters."
            }
        }

        companion object {
            private const val serialVersionUID: Long = -2600404936788080311L
        }
    }

    companion object {
        private const val serialVersionUID: Long = -710482694906352408L
    }
}

private fun String.isSafeCseToken(name: String): Boolean {
    require(isNotBlank()) {
        "bluetape4k.aws.s3.client-side-encryption.$name must not be blank."
    }
    require(none(Char::isISOControl)) {
        "bluetape4k.aws.s3.client-side-encryption.$name must not contain control characters."
    }
    return true
}
