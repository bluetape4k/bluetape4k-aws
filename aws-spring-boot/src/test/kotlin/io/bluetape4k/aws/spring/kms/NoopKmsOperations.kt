package io.bluetape4k.aws.spring.kms

import software.amazon.awssdk.services.kms.model.DataKeySpec

object NoopKmsOperations: KmsOperations {
    override suspend fun encrypt(
        plaintext: ByteArray,
        keyId: String?,
        encryptionContext: Map<String, String>,
    ): ByteArray =
        plaintext.copyOf()

    override suspend fun decrypt(
        ciphertext: ByteArray,
        keyId: String?,
        encryptionContext: Map<String, String>,
    ): ByteArray =
        ciphertext.copyOf()

    override suspend fun generateDataKey(
        keyId: String?,
        keySpec: DataKeySpec?,
        numberOfBytes: Int?,
        encryptionContext: Map<String, String>,
        useCache: Boolean,
    ): KmsDataKey =
        KmsDataKey(
            keyId = keyId ?: "noop-key",
            plaintext = byteArrayOf(1, 2, 3),
            encryptedDataKey = byteArrayOf(4, 5, 6),
        )
}
