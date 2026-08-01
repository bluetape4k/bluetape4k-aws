package io.bluetape4k.aws.spring.kms

import java.time.Instant

/**
 * AWS KMS GenerateDataKey가 반환하는 평문 및 암호화된 데이터 키 쌍입니다.
 *
 * ## 계약
 * - `plaintext`는 민감한 키 자료이며 노출할 때 방어적으로 복사합니다.
 * - `encryptedDataKey`는 암호화된 페이로드 메타데이터와 함께 저장한 뒤 KMS Decrypt에 전달할 수 있습니다.
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
