package io.bluetape4k.aws.spring.s3

import org.springframework.boot.context.properties.ConfigurationProperties
import kotlin.jvm.internal.DefaultConstructorMarker
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

    @Suppress("DEPRECATION")
    data class Transfer(
        val enabled: Boolean = true,
        val uploadDirectoryMaxDepth: Int? = null,
        val transferDirectoryMaxConcurrency: Int? = null,
        /** 메모리에서 임시 파일로 전환하는 스트림 누적 한계입니다. */
        val outputStreamThresholdBytes: Long = DEFAULT_OUTPUT_STREAM_THRESHOLD_BYTES,
        /**
         * 호환성을 위해 남겨 둔 값이며 스트림별 multipart part 크기로 적용되지 않습니다.
         *
         * CRT 클라이언트를 사용할 때는 `bluetape4k.aws.s3.crt.minimum-part-size-in-bytes`를 설정하고,
         * Java multipart 클라이언트는 호출자가 소유한 [software.amazon.awssdk.services.s3.S3AsyncClient]에
         * `MultipartConfiguration.minimumPartSizeInBytes`를 구성하세요.
         */
        @Deprecated(
            message = "요청별 part size는 AWS SDK v2 TransferManager에서 지원하지 않습니다. " +
                "CRT 클라이언트는 bluetape4k.aws.s3.crt.minimum-part-size-in-bytes를 사용하세요.",
        )
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
        /** 기존 4-인자 생성자와의 source/JVM descriptor 호환성을 유지합니다. */
        constructor(
            enabled: Boolean,
            keyId: String?,
            encryptionContext: Map<String, String>,
            useDataKeyCache: Boolean,
        ) : this(
            enabled = enabled,
            keyId = keyId,
            encryptionContext = encryptionContext,
            useDataKeyCache = useDataKeyCache,
            provider = ClientSideEncryptionProvider.KMS,
            keyVersion = null,
        )

        /** Kotlin compiler가 생성하던 기존 4-인자 default constructor descriptor를 보존합니다. */
        @Suppress("UNUSED_PARAMETER")
        constructor(
            enabled: Boolean,
            keyId: String?,
            encryptionContext: Map<String, String>,
            useDataKeyCache: Boolean,
            mask: Int,
            marker: DefaultConstructorMarker?,
        ) : this(
            enabled = if (mask and COPY_ENABLED_MASK != 0) false else enabled,
            keyId = if (mask and COPY_KEY_ID_MASK != 0) null else keyId,
            encryptionContext = if (mask and COPY_ENCRYPTION_CONTEXT_MASK != 0) emptyMap() else encryptionContext,
            useDataKeyCache = if (mask and COPY_USE_DATA_KEY_CACHE_MASK != 0) true else useDataKeyCache,
            provider = ClientSideEncryptionProvider.KMS,
            keyVersion = null,
        )

        /** 기존 4-인자 data-class copy 호출과의 source/JVM descriptor 호환성을 유지합니다. */
        fun copy(
            enabled: Boolean,
            keyId: String?,
            encryptionContext: Map<String, String>,
            useDataKeyCache: Boolean,
        ): ClientSideEncryption = ClientSideEncryption(
            enabled = enabled,
            keyId = keyId,
            encryptionContext = encryptionContext,
            useDataKeyCache = useDataKeyCache,
            provider = provider,
            keyVersion = keyVersion,
        )

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
            /** Kotlin compiler가 생성하던 기존 4-인자 `copy$default` descriptor를 보존합니다. */
            @JvmStatic
            @Suppress("FunctionNaming", "unused")
            fun `copy$default`(
                self: ClientSideEncryption,
                enabled: Boolean,
                keyId: String?,
                encryptionContext: Map<String, String>,
                useDataKeyCache: Boolean,
                mask: Int,
                marker: Any?,
            ): ClientSideEncryption {
                @Suppress("UNUSED_VARIABLE")
                val ignoredMarker = marker
                return self.copy(
                    enabled = if (mask and COPY_ENABLED_MASK != 0) self.enabled else enabled,
                    keyId = if (mask and COPY_KEY_ID_MASK != 0) self.keyId else keyId,
                    encryptionContext = if (mask and COPY_ENCRYPTION_CONTEXT_MASK != 0) {
                        self.encryptionContext
                    } else {
                        encryptionContext
                    },
                    useDataKeyCache = if (mask and COPY_USE_DATA_KEY_CACHE_MASK != 0) {
                        self.useDataKeyCache
                    } else {
                        useDataKeyCache
                    },
                )
            }

            private const val COPY_ENABLED_MASK: Int = 1
            private const val COPY_KEY_ID_MASK: Int = 1 shl 1
            private const val COPY_ENCRYPTION_CONTEXT_MASK: Int = 1 shl 2
            private const val COPY_USE_DATA_KEY_CACHE_MASK: Int = 1 shl 3
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
