package io.bluetape4k.aws.spring.kms

import java.time.Instant

/**
 * Plaintext and encrypted data key pair returned by AWS KMS GenerateDataKey.
 *
 * ## Contract
 * - `plaintext` is sensitive key material and is defensively copied when exposed.
 * - `encryptedDataKey` can be stored with encrypted payload metadata and later passed to KMS Decrypt.
 */
class KmsDataKey(
    val keyId: String,
    plaintext: ByteArray,
    encryptedDataKey: ByteArray,
    val createdAt: Instant = Instant.now(),
) {
    private val plaintextBytes: ByteArray = plaintext.copyOf()
    private val encryptedDataKeyBytes: ByteArray = encryptedDataKey.copyOf()

    val plaintext: ByteArray
        get() = plaintextBytes.copyOf()

    val encryptedDataKey: ByteArray
        get() = encryptedDataKeyBytes.copyOf()
}
