package io.bluetape4k.aws.ktor.s3

import io.bluetape4k.support.requireNotBlank
import java.io.Serializable
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

private const val ENCRYPTION_ALGORITHM = "AES/GCM/NoPadding"
private const val DATA_KEY_ALGORITHM = "AES"
private const val GCM_TAG_BITS = 128
private const val GCM_NONCE_BYTES = 12
private const val METADATA_ALGORITHM = "bt4k-cek-alg"
private const val METADATA_ENCRYPTED_KEY = "bt4k-cek"
private const val METADATA_KEY_ID = "bt4k-cek-key-id"
private const val METADATA_NONCE = "bt4k-cek-nonce"

/**
 * S3 클라이언트 측 봉투 암호화를 위한 평문 및 암호화된 데이터 키 쌍입니다.
 *
 * ## 동작/계약
 *
 * [plaintextKey]는 객체 페이로드 하나를 암호화하거나 복호화할 때 로컬에서만 사용합니다.
 * [encryptedKey]는 S3 객체 메타데이터에 저장한 뒤 같은 공급자의 복호화 경로에 전달합니다.
 * 프로덕션 공급자는 일반적으로 AWS KMS GenerateDataKey 및 Decrypt 작업을 감싸야 합니다.
 */
class S3KtorDataKey(
    val plaintextKey: ByteArray,
    val encryptedKey: ByteArray,
    val keyId: String? = null,
): Serializable {

    init {
        require(plaintextKey.size in setOf(16, 24, 32)) {
            "plaintextKey must be 16, 24, or 32 bytes for AES. size=${plaintextKey.size}"
        }
        require(encryptedKey.isNotEmpty()) { "encryptedKey must not be empty." }
        keyId?.requireNotBlank("keyId")
    }

    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

/**
 * S3 클라이언트 측 봉투 암호화용 데이터 키 공급자입니다.
 */
interface S3KtorDataKeyProvider {

    /**
     * 객체 암호화 작업 하나에 사용할 새 데이터 키를 생성합니다.
     */
    suspend fun generateDataKey(encryptionContext: Map<String, String>): S3KtorDataKey

    /**
     * S3 객체 메타데이터에서 읽은 암호화된 데이터 키를 복호화합니다.
     */
    suspend fun decryptDataKey(encryptedDataKey: ByteArray, encryptionContext: Map<String, String>): ByteArray
}

/**
 * [S3KtorClient]용 클라이언트 측 봉투 암호화 도우미입니다.
 *
 * ## 동작/계약
 *
 * S3 PutObject를 호출하기 전에 객체 바이트를 AES-GCM으로 로컬에서 암호화합니다.
 * 암호화된 데이터 키와 nonce는 S3 메타데이터로 저장합니다. 이 도우미는 AWS KMS에
 * 직접 의존하지 않습니다. KMS 관리형 데이터 키가 필요하면 KMS를 사용하는
 * [S3KtorDataKeyProvider]를 주입하세요.
 */
class S3KtorClientSideEncryption(
    private val dataKeyProvider: S3KtorDataKeyProvider,
    private val random: SecureRandom = SecureRandom(),
) {

    /**
     * [plaintext]를 로컬에서 암호화하고 암호문 객체를 업로드합니다.
     */
    suspend fun putEncryptedObject(
        s3: S3KtorClient,
        bucket: String,
        key: String,
        plaintext: ByteArray,
        contentType: String? = null,
        metadata: Map<String, String> = emptyMap(),
        encryptionContext: Map<String, String> = emptyMap(),
        headers: Map<String, String> = emptyMap(),
    ): S3KtorPutObjectResponse {
        val dataKey = dataKeyProvider.generateDataKey(encryptionContext)
        val nonce = ByteArray(GCM_NONCE_BYTES).also(random::nextBytes)
        val ciphertext = encrypt(plaintext, dataKey.plaintextKey, nonce)

        val encryptionMetadata = buildMap {
            put(METADATA_ALGORITHM, ENCRYPTION_ALGORITHM)
            put(METADATA_ENCRYPTED_KEY, Base64.getEncoder().encodeToString(dataKey.encryptedKey))
            put(METADATA_NONCE, Base64.getEncoder().encodeToString(nonce))
            dataKey.keyId?.let { put(METADATA_KEY_ID, it) }
        }

        return s3.putObject(
            bucket = bucket,
            key = key,
            bytes = ciphertext,
            contentType = contentType,
            metadata = metadata + encryptionMetadata,
            headers = headers,
        )
    }

    /**
     * 암호화된 객체를 다운로드하고 페이로드를 로컬에서 복호화합니다.
     */
    suspend fun getEncryptedObjectBytes(
        s3: S3KtorClient,
        bucket: String,
        key: String,
        encryptionContext: Map<String, String> = emptyMap(),
    ): ByteArray {
        val objectResponse = s3.getObject(bucket, key)
        val algorithm = objectResponse.metadata.requiredMetadata(METADATA_ALGORITHM)
        require(algorithm == ENCRYPTION_ALGORITHM) {
            "Unsupported S3 client-side encryption algorithm: $algorithm"
        }

        val encryptedKey = Base64.getDecoder().decode(objectResponse.metadata.requiredMetadata(METADATA_ENCRYPTED_KEY))
        val nonce = Base64.getDecoder().decode(objectResponse.metadata.requiredMetadata(METADATA_NONCE))
        val plaintextKey = dataKeyProvider.decryptDataKey(encryptedKey, encryptionContext)

        return decrypt(objectResponse.bytes, plaintextKey, nonce)
    }

    private fun encrypt(plaintext: ByteArray, key: ByteArray, nonce: ByteArray): ByteArray =
        cipher(Cipher.ENCRYPT_MODE, key, nonce).doFinal(plaintext)

    private fun decrypt(ciphertext: ByteArray, key: ByteArray, nonce: ByteArray): ByteArray =
        cipher(Cipher.DECRYPT_MODE, key, nonce).doFinal(ciphertext)

    private fun cipher(mode: Int, key: ByteArray, nonce: ByteArray): Cipher {
        require(key.size in setOf(16, 24, 32)) { "key must be 16, 24, or 32 bytes for AES. size=${key.size}" }
        require(nonce.size == GCM_NONCE_BYTES) { "nonce must be $GCM_NONCE_BYTES bytes. size=${nonce.size}" }

        return Cipher.getInstance(ENCRYPTION_ALGORITHM).apply {
            init(mode, SecretKeySpec(key, DATA_KEY_ALGORITHM), GCMParameterSpec(GCM_TAG_BITS, nonce))
        }
    }
}

private fun Map<String, String>.requiredMetadata(name: String): String =
    entries.firstOrNull { (key, _) -> key.equals(name, ignoreCase = true) }?.value
        ?: error("S3 encrypted object metadata '$name' is missing.")
