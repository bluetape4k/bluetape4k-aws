package io.bluetape4k.aws.spring.kms

import kotlinx.coroutines.future.await
import software.amazon.awssdk.core.SdkBytes
import software.amazon.awssdk.services.kms.KmsAsyncClient
import software.amazon.awssdk.services.kms.model.DataKeySpec

/**
 * AWS SDK v2 [KmsAsyncClient] backed implementation of [KmsOperations].
 */
class KmsCoroutinesEncryptor(
    private val kmsAsyncClient: KmsAsyncClient,
    private val properties: KmsProperties,
    private val dataKeyCache: DataKeyCache,
): KmsOperations {

    override suspend fun encrypt(
        plaintext: ByteArray,
        keyId: String?,
        encryptionContext: Map<String, String>,
    ): ByteArray {
        val effectiveKeyId = resolveRequiredKeyId(keyId)
        val response = kmsAsyncClient.encrypt { request ->
            request.keyId(effectiveKeyId)
            request.plaintext(SdkBytes.fromByteArray(plaintext))
            mergedEncryptionContext(encryptionContext).takeIf { it.isNotEmpty() }?.let(request::encryptionContext)
            properties.encryptionAlgorithm?.let(request::encryptionAlgorithm)
        }.await()

        return response.ciphertextBlob().asByteArray()
    }

    override suspend fun decrypt(
        ciphertext: ByteArray,
        keyId: String?,
        encryptionContext: Map<String, String>,
    ): ByteArray {
        val response = kmsAsyncClient.decrypt { request ->
            request.ciphertextBlob(SdkBytes.fromByteArray(ciphertext))
            resolveOptionalKeyId(keyId)?.let(request::keyId)
            mergedEncryptionContext(encryptionContext).takeIf { it.isNotEmpty() }?.let(request::encryptionContext)
            properties.encryptionAlgorithm?.let(request::encryptionAlgorithm)
        }.await()

        return response.plaintext().asByteArray()
    }

    override suspend fun generateDataKey(
        keyId: String?,
        keySpec: DataKeySpec?,
        numberOfBytes: Int?,
        encryptionContext: Map<String, String>,
        useCache: Boolean,
    ): KmsDataKey {
        val effectiveKeyId = resolveRequiredKeyId(keyId)
        val effectiveKeySpec = keySpec ?: if (numberOfBytes == null) properties.dataKey.keySpec else null
        val effectiveContext = mergedEncryptionContext(encryptionContext)
        val cacheKey = KmsDataKeyCacheKey(
            keyId = effectiveKeyId,
            keySpec = effectiveKeySpec,
            numberOfBytes = numberOfBytes,
            encryptionContext = effectiveContext.toSortedMap(),
        )

        if (useCache) {
            dataKeyCache.get(cacheKey)?.let { return it }
        }

        val response = kmsAsyncClient.generateDataKey { request ->
            request.keyId(effectiveKeyId)
            effectiveKeySpec?.let(request::keySpec)
            numberOfBytes?.let(request::numberOfBytes)
            effectiveContext.takeIf { it.isNotEmpty() }?.let(request::encryptionContext)
        }.await()

        val dataKey = KmsDataKey(
            keyId = response.keyId() ?: effectiveKeyId,
            plaintext = response.plaintext().asByteArray(),
            encryptedDataKey = response.ciphertextBlob().asByteArray(),
        )

        if (useCache) {
            dataKeyCache.put(cacheKey, dataKey)
        }

        return dataKey
    }

    private fun resolveRequiredKeyId(keyId: String?): String {
        val effectiveKeyId = keyId ?: properties.keyId
        require(!effectiveKeyId.isNullOrBlank()) {
            "KMS keyId is required. Configure bluetape4k.aws.kms.key-id or pass keyId to the operation."
        }
        return effectiveKeyId
    }

    private fun resolveOptionalKeyId(keyId: String?): String? {
        val effectiveKeyId = keyId ?: properties.keyId
        effectiveKeyId?.let { require(it.isNotBlank()) { "keyId must not be blank." } }
        return effectiveKeyId
    }

    private fun mergedEncryptionContext(encryptionContext: Map<String, String>): Map<String, String> =
        properties.encryptionContext + encryptionContext
}
