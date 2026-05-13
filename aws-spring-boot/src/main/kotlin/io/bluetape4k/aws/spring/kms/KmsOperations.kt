package io.bluetape4k.aws.spring.kms

import software.amazon.awssdk.services.kms.model.DataKeySpec

/**
 * Coroutine-friendly AWS KMS encryption contract for Spring applications.
 *
 * ## Contract
 * - `encrypt` and `generateDataKey` require a KMS key id from configuration or the method argument.
 * - `decrypt` can omit the key id for symmetric ciphertexts, but passes one when configured.
 * - Method-level encryption context entries override same-named default entries from [KmsProperties].
 *
 * ```kotlin
 * class SecretService(private val kms: KmsOperations) {
 *     suspend fun protect(secret: String): ByteArray =
 *         kms.encrypt(secret.encodeToByteArray(), encryptionContext = mapOf("purpose" to "token"))
 * }
 * ```
 */
interface KmsOperations {

    suspend fun encrypt(
        plaintext: ByteArray,
        keyId: String? = null,
        encryptionContext: Map<String, String> = emptyMap(),
    ): ByteArray

    suspend fun decrypt(
        ciphertext: ByteArray,
        keyId: String? = null,
        encryptionContext: Map<String, String> = emptyMap(),
    ): ByteArray

    suspend fun generateDataKey(
        keyId: String? = null,
        keySpec: DataKeySpec? = null,
        numberOfBytes: Int? = null,
        encryptionContext: Map<String, String> = emptyMap(),
        useCache: Boolean = true,
    ): KmsDataKey
}
