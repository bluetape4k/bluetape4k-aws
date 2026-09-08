package io.bluetape4k.aws.spring.kms

import java.time.Instant
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * AWS KMS GenerateDataKey가 반환하는 평문 및 암호화된 데이터 키 쌍입니다.
 *
 * ## 계약
 * - `plaintext`는 민감한 키 자료이며 노출할 때 방어적으로 복사합니다.
 * - `encryptedDataKey`는 암호화된 페이로드 메타데이터와 함께 저장한 뒤 KMS Decrypt에 전달할 수 있습니다.
 * - 사용이 끝나면 [close]를 호출해 라이브러리가 소유한 평문 배열을 소거합니다.
 * - 생성자에 넘긴 원본 배열과 getter가 반환한 배열은 호출자가 소유하며 이 객체가 소거하지 않습니다.
 */
class KmsDataKey(
    val keyId: String,
    plaintext: ByteArray,
    encryptedDataKey: ByteArray,
    val createdAt: Instant = Instant.now(),
) : AutoCloseable {
    private val lock = ReentrantLock()
    private val plaintextBytes: ByteArray = plaintext.copyOf()
    private val encryptedDataKeyBytes: ByteArray = encryptedDataKey.copyOf()
    private var closed = false

    val plaintext: ByteArray
        get() = lock.withLock {
            check(!closed) { "KmsDataKey is closed." }
            plaintextBytes.copyOf()
        }

    val encryptedDataKey: ByteArray
        get() = encryptedDataKeyBytes.copyOf()

    /**
     * 라이브러리 내부 캐시가 독립적인 키 자료를 보유하도록 snapshot을 만듭니다.
     * 호출자와 캐시가 같은 평문 배열을 공유하지 않도록 잠금 안에서 복사합니다.
     */
    internal fun copy(): KmsDataKey = lock.withLock {
        check(!closed) { "KmsDataKey is closed." }
        KmsDataKey(keyId, plaintextBytes, encryptedDataKeyBytes, createdAt)
    }

    /**
     * 평문 키 자료를 멱등적으로 소거합니다. 소거 뒤 [plaintext] 접근은 실패합니다.
     */
    override fun close() {
        lock.withLock {
            if (!closed) {
                plaintextBytes.fill(0)
                closed = true
            }
        }
    }
}
