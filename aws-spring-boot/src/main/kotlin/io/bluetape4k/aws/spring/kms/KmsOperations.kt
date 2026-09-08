package io.bluetape4k.aws.spring.kms

import software.amazon.awssdk.services.kms.model.DataKeySpec

/**
 * Spring 애플리케이션을 위한 코루틴 친화적인 AWS KMS 암호화 계약입니다.
 *
 * ## 계약
 * - `encrypt`와 `generateDataKey`에는 구성 또는 메서드 인수의 KMS 키 id가 필요합니다.
 * - 대칭 암호문에 `decrypt`를 사용할 때는 키 id를 생략할 수 있지만 구성된 경우 전달합니다.
 * - 메서드 수준 암호화 컨텍스트 항목은 [KmsProperties]의 같은 이름 기본 항목보다 우선합니다.
 * - `generateDataKey`가 반환하는 [KmsDataKey]는 호출자 소유이므로 사용 후 `use`로 닫아 평문을 소거합니다.
 * - `decrypt`가 반환하는 평문 배열도 호출자 소유이며 사용 후 직접 소거할 수 있습니다.
 * - 사용자 정의 [DataKeyCache]는 `put`에서 독립 snapshot을 보유하고 `get`에서 호출자 소유 snapshot을 반환해야 합니다.
 *
 * ```kotlin
 * class SecretService(private val kms: KmsOperations) {
 *     suspend fun protect(secret: String): ByteArray =
 *         kms.encrypt(secret.encodeToByteArray(), encryptionContext = mapOf("purpose" to "token"))
 * }
 * ```
 *
 * `generateDataKey` 사용 예:
 * ```kotlin
 * kms.generateDataKey(useCache = true).use { dataKey ->
 *     val plaintextKey = dataKey.plaintext
 *     try {
 *         // 로컬 암호화에 plaintextKey를 사용합니다.
 *     } finally {
 *         plaintextKey.fill(0)
 *     }
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
