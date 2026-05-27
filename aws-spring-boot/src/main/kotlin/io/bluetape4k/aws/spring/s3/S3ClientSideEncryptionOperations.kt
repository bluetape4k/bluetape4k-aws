package io.bluetape4k.aws.spring.s3

import io.bluetape4k.aws.spring.kms.KmsOperations
import io.bluetape4k.support.requireNotBlank
import kotlinx.coroutines.future.await
import software.amazon.awssdk.core.async.AsyncRequestBody
import software.amazon.awssdk.core.async.AsyncResponseTransformer
import software.amazon.awssdk.services.kms.model.DataKeySpec
import software.amazon.awssdk.services.s3.S3AsyncClient
import software.amazon.awssdk.services.s3.model.PutObjectResponse
import java.nio.charset.Charset
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
 * S3 client-side envelope encryption operations backed by AWS KMS data keys.
 *
 * ## Behavior / Contract
 *
 * Implementations encrypt object bytes locally before uploading them to S3 and
 * store the encrypted data key plus AES-GCM nonce in object metadata. This API
 * is intentionally byte-array based: it is suitable for small and medium
 * objects, not multipart uploads or streaming encryption. The object metadata is
 * required for decryption and the wire format is not AWS Encryption SDK
 * compatible.
 *
 * ```kotlin
 * class SecureDocuments(private val s3: S3ClientSideEncryptionOperations) {
 *     suspend fun save(bucket: String, key: String, text: String) {
 *         s3.uploadEncrypted(bucket, key, text.encodeToByteArray(), contentType = "text/plain")
 *     }
 * }
 * ```
 */
interface S3ClientSideEncryptionOperations {

    suspend fun uploadEncrypted(
        bucket: String,
        key: String,
        bytes: ByteArray,
        contentType: String? = null,
        metadata: Map<String, String> = emptyMap(),
        encryptionContext: Map<String, String> = emptyMap(),
    ): PutObjectResponse

    suspend fun downloadEncryptedBytes(
        bucket: String,
        key: String,
        encryptionContext: Map<String, String> = emptyMap(),
    ): ByteArray

    suspend fun downloadEncryptedText(
        bucket: String,
        key: String,
        charset: Charset = Charsets.UTF_8,
        encryptionContext: Map<String, String> = emptyMap(),
    ): String =
        downloadEncryptedBytes(bucket, key, encryptionContext).toString(charset)
}

/**
 * Default [S3ClientSideEncryptionOperations] implementation.
 */
class S3ClientSideEncryptionTemplate(
    private val s3AsyncClient: S3AsyncClient,
    private val kmsOperations: KmsOperations,
    private val properties: S3Properties,
    private val random: SecureRandom = SecureRandom(),
) : S3ClientSideEncryptionOperations {

    override suspend fun uploadEncrypted(
        bucket: String,
        key: String,
        bytes: ByteArray,
        contentType: String?,
        metadata: Map<String, String>,
        encryptionContext: Map<String, String>,
    ): PutObjectResponse {
        bucket.requireNotBlank("bucket")
        key.requireNotBlank("key")

        val effectiveContext = effectiveEncryptionContext(encryptionContext)
        val dataKey = kmsOperations.generateDataKey(
            keyId = properties.clientSideEncryption.keyId,
            keySpec = DataKeySpec.AES_256,
            encryptionContext = effectiveContext,
            useCache = properties.clientSideEncryption.useDataKeyCache,
        )
        val nonce = ByteArray(GCM_NONCE_BYTES).also(random::nextBytes)
        val ciphertext = encrypt(bytes, dataKey.plaintext, nonce)
        val encryptionMetadata = buildMap {
            put(METADATA_ALGORITHM, ENCRYPTION_ALGORITHM)
            put(METADATA_ENCRYPTED_KEY, Base64.getEncoder().encodeToString(dataKey.encryptedDataKey))
            put(METADATA_KEY_ID, dataKey.keyId)
            put(METADATA_NONCE, Base64.getEncoder().encodeToString(nonce))
        }

        return s3AsyncClient.putObject(
            { request ->
                request.bucket(bucket)
                request.key(key)
                contentType?.let(request::contentType)
                request.metadata(metadata + encryptionMetadata)
            },
            AsyncRequestBody.fromBytes(ciphertext),
        ).await()
    }

    override suspend fun downloadEncryptedBytes(
        bucket: String,
        key: String,
        encryptionContext: Map<String, String>,
    ): ByteArray {
        bucket.requireNotBlank("bucket")
        key.requireNotBlank("key")

        val response = s3AsyncClient.getObject(
            { request ->
                request.bucket(bucket)
                request.key(key)
            },
            AsyncResponseTransformer.toBytes(),
        ).await()
        val metadata = response.response().metadata()
        val algorithm = metadata.requiredMetadata(METADATA_ALGORITHM)
        require(algorithm == ENCRYPTION_ALGORITHM) {
            "Unsupported S3 client-side encryption algorithm: $algorithm"
        }

        val encryptedKey = Base64.getDecoder().decode(metadata.requiredMetadata(METADATA_ENCRYPTED_KEY))
        val keyId = metadata[METADATA_KEY_ID] ?: properties.clientSideEncryption.keyId
        val nonce = Base64.getDecoder().decode(metadata.requiredMetadata(METADATA_NONCE))
        val plaintextKey = kmsOperations.decrypt(
            ciphertext = encryptedKey,
            keyId = keyId,
            encryptionContext = effectiveEncryptionContext(encryptionContext),
        )

        return decrypt(response.asByteArray(), plaintextKey, nonce)
    }

    private fun effectiveEncryptionContext(
        encryptionContext: Map<String, String>,
    ): Map<String, String> =
        properties.clientSideEncryption.encryptionContext + encryptionContext

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
