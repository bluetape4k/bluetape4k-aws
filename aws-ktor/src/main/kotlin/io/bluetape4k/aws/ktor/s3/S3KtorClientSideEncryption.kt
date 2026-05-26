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
 * Plaintext and encrypted data key pair for S3 client-side envelope encryption.
 *
 * ## Behavior / Contract
 *
 * [plaintextKey] is used only locally to encrypt or decrypt one object payload.
 * [encryptedKey] is stored in S3 object metadata and later passed to the same
 * provider's decrypt path. Production providers should usually wrap AWS KMS
 * GenerateDataKey and Decrypt operations.
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
 * Data-key provider for S3 client-side envelope encryption.
 */
interface S3KtorDataKeyProvider {

    /**
     * Creates a new data key for one object encryption operation.
     */
    suspend fun generateDataKey(encryptionContext: Map<String, String>): S3KtorDataKey

    /**
     * Decrypts the encrypted data key read from S3 object metadata.
     */
    suspend fun decryptDataKey(encryptedDataKey: ByteArray, encryptionContext: Map<String, String>): ByteArray
}

/**
 * Client-side envelope encryption helper for [S3KtorClient].
 *
 * ## Behavior / Contract
 *
 * Encrypts object bytes locally with AES-GCM before calling S3 PutObject. The
 * encrypted data key and nonce are stored as S3 metadata. The helper does not
 * depend on AWS KMS directly; inject an [S3KtorDataKeyProvider] backed by KMS
 * when KMS-managed data keys are required.
 */
class S3KtorClientSideEncryption(
    private val dataKeyProvider: S3KtorDataKeyProvider,
    private val random: SecureRandom = SecureRandom(),
) {

    /**
     * Encrypts [plaintext] locally and uploads the ciphertext object.
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
     * Downloads an encrypted object and decrypts the payload locally.
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
