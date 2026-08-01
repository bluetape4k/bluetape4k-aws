package io.bluetape4k.aws.spring.kms

import software.amazon.awssdk.services.kms.model.DataKeySpec

/**
 * Spring 애플리케이션을 위한 코루틴 친화적인 AWS KMS 암호화 계약입니다.
 *
 * ## 계약
 * - `encrypt`와 `generateDataKey`에는 구성 또는 메서드 인수의 KMS 키 id가 필요합니다.
 * - 대칭 암호문에 `decrypt`를 사용할 때는 키 id를 생략할 수 있지만 구성된 경우 전달합니다.
 * - 메서드 수준 암호화 컨텍스트 항목은 [KmsProperties]의 같은 이름 기본 항목보다 우선합니다.
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
