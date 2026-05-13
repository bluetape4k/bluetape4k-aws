package io.bluetape4k.aws.spring.kms

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.springframework.security.crypto.encrypt.TextEncryptor
import java.nio.charset.StandardCharsets
import java.util.Base64

/**
 * Spring Security Crypto [TextEncryptor] adapter backed by [KmsOperations].
 *
 * ## Contract
 * - Encrypts UTF-8 text and returns Base64-encoded KMS ciphertext.
 * - Decrypts Base64 ciphertext produced by this adapter.
 * - Blocks the caller because [TextEncryptor] is a synchronous Spring Security interface.
 *
 * ```kotlin
 * val protectedValue = textEncryptor.encrypt("short-secret")
 * val plainValue = textEncryptor.decrypt(protectedValue)
 * ```
 */
class KmsTextEncryptor(
    private val kmsOperations: KmsOperations,
    private val keyId: String? = null,
    private val encryptionContext: Map<String, String> = emptyMap(),
): TextEncryptor {

    private val encoder = Base64.getEncoder()
    private val decoder = Base64.getDecoder()

    override fun encrypt(text: String): String =
        encoder.encodeToString(
            runBlocking(Dispatchers.IO) {
                kmsOperations.encrypt(
                    plaintext = text.toByteArray(StandardCharsets.UTF_8),
                    keyId = keyId,
                    encryptionContext = encryptionContext,
                )
            }
        )

    override fun decrypt(encryptedText: String): String =
        runBlocking(Dispatchers.IO) {
            kmsOperations.decrypt(
                ciphertext = decoder.decode(encryptedText),
                keyId = keyId,
                encryptionContext = encryptionContext,
            )
        }.toString(StandardCharsets.UTF_8)
}
