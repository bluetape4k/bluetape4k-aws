package io.bluetape4k.aws.spring.kms

import io.bluetape4k.support.toUtf8String
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.springframework.security.crypto.encrypt.TextEncryptor
import java.nio.charset.StandardCharsets
import java.util.*

/**
 * [KmsOperations]를 사용하는 Spring Security Crypto [TextEncryptor] 어댑터입니다.
 *
 * ## 계약
 * - UTF-8 텍스트를 암호화하고 Base64로 인코딩한 KMS 암호문을 반환합니다.
 * - 이 어댑터가 생성한 Base64 암호문을 복호화합니다.
 * - [TextEncryptor]는 동기 Spring Security 인터페이스이므로 호출자를 블로킹합니다.
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

    override fun encrypt(text: String): String {
        val encrypted = runBlocking(Dispatchers.IO) {
            kmsOperations.encrypt(
                plaintext = text.toByteArray(StandardCharsets.UTF_8),
                keyId = keyId,
                encryptionContext = encryptionContext,
            )
        }
        return encoder.encodeToString(encrypted)
    }

    override fun decrypt(encryptedText: String): String {
        val plainBytes = runBlocking(Dispatchers.IO) {
            kmsOperations.decrypt(
                ciphertext = decoder.decode(encryptedText),
                keyId = keyId,
                encryptionContext = encryptionContext,
            )
        }
        return try {
            plainBytes.toUtf8String()
        } finally {
            plainBytes.fill(0)
        }
    }
}
