package io.bluetape4k.aws.spring.s3

import io.bluetape4k.aws.spring.sqs.SqsExtendedPayloadReadException
import io.bluetape4k.support.requireNotBlank
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.future.await
import kotlinx.coroutines.reactive.asFlow
import software.amazon.awssdk.core.async.AsyncRequestBody
import software.amazon.awssdk.core.async.AsyncResponseTransformer
import software.amazon.awssdk.services.s3.S3AsyncClient
import software.amazon.awssdk.services.s3.model.PutObjectResponse
import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher

/** AES/RSA provider를 사용하는 S3 클라이언트 측 봉투 암호화 작업입니다. */
class S3ClientSideEncryptionProviderTemplate(
    private val s3AsyncClient: S3AsyncClient,
    private val properties: S3Properties,
    aesProvider: S3AesProvider? = null,
    rsaProvider: S3RsaProvider? = null,
    private val random: SecureRandom = SecureRandom(),
) : S3BoundedEncryptedReadOperations,
    S3ClientSideEncryptionIdentity,
    AutoCloseable {

    private val material: ClientSideEncryptionKeyMaterial = when (properties.clientSideEncryption.provider) {
        ClientSideEncryptionProvider.AES ->
            AesClientSideEncryptionKeyMaterial.from(
                requireNotNull(aesProvider) {
                    "S3AesProvider is required when provider=AES."
                },
            )
        ClientSideEncryptionProvider.RSA ->
            RsaClientSideEncryptionKeyMaterial.from(
                requireNotNull(rsaProvider) {
                    "S3RsaProvider is required when provider=RSA."
                },
            )
        ClientSideEncryptionProvider.KMS ->
            error("KMS provider must use S3ClientSideEncryptionTemplate.")
    }

    private var closed = false
    private val lifecycleLock = Any()

    override suspend fun uploadEncrypted(
        bucket: String,
        key: String,
        bytes: ByteArray,
        contentType: String?,
        metadata: Map<String, String>,
        encryptionContext: Map<String, String>,
    ): PutObjectResponse {
        requireOpen()
        bucket.requireNotBlank("bucket")
        key.requireNotBlank("key")

        val envelope = newEncryptionEnvelope(bytes, encryptionContext)
        return try {
            s3AsyncClient.putObject(
                { builder ->
                    builder.bucket(bucket)
                    builder.key(key)
                    contentType?.let(builder::contentType)
                    builder.metadata(ProviderEnvelope.mergeMetadata(metadata, envelope.metadata))
                },
                AsyncRequestBody.fromBytes(envelope.ciphertext),
            ).await()
        } finally {
            envelope.ciphertext.fill(0)
        }
    }

    override suspend fun downloadEncryptedBytes(
        bucket: String,
        key: String,
        encryptionContext: Map<String, String>,
    ): ByteArray {
        requireOpen()
        bucket.requireNotBlank("bucket")
        key.requireNotBlank("key")

        val response = s3AsyncClient.getObject(
            { builder ->
                builder.bucket(bucket)
                builder.key(key)
            },
            AsyncResponseTransformer.toBytes(),
        ).await()
        val ciphertext = response.asByteArray()
        return try {
            decryptProviderPayload(
                ciphertext,
                response.response().metadata(),
                encryptionContext,
            )
        } finally {
            ciphertext.fill(0)
        }
    }

    override suspend fun downloadEncryptedBytesBounded(
        bucket: String,
        key: String,
        encryptionContext: Map<String, String>,
        maxCiphertextBytes: Int,
    ): ByteArray {
        requireOpen()
        bucket.requireNotBlank("bucket")
        key.requireNotBlank("key")
        require(maxCiphertextBytes in 1..S3BoundedEncryptedReadOperations.MAX_CIPHERTEXT_BYTES) {
            "maxCiphertextBytes must be between 1 and " +
                "${S3BoundedEncryptedReadOperations.MAX_CIPHERTEXT_BYTES}."
        }

        val publisher = s3AsyncClient.getObject(
            { builder ->
                builder.bucket(bucket)
                builder.key(key)
            },
            AsyncResponseTransformer.toPublisher(),
        ).await()
        val accumulator = ZeroizingByteArrayOutputStream(minOf(maxCiphertextBytes, 8 * 1024))
        var size = 0
        var ciphertext: ByteArray? = null
        try {
            publisher.asFlow().collect { chunk ->
                val copy = chunk.slice()
                val chunkSize = copy.remaining()
                if (chunkSize > maxCiphertextBytes - size) {
                    throw SqsExtendedPayloadReadException.create(pointerPresent = true, retryable = false)
                }
                val bytes = ByteArray(chunkSize)
                copy.get(bytes)
                try {
                    accumulator.write(bytes)
                } finally {
                    bytes.fill(0)
                }
                size += chunkSize
            }
            ciphertext = accumulator.toByteArray()
            return decryptProviderPayload(
                ciphertext,
                publisher.response().metadata(),
                encryptionContext,
            )
        } finally {
            ciphertext?.fill(0)
            accumulator.zeroizeAndReset()
        }
    }

    /** Transfer adapter가 기존 ciphertext를 provider envelope로 복호화할 때 사용하는 경계입니다. */
    internal fun decryptProviderPayload(
        ciphertext: ByteArray,
        metadata: Map<String, String>,
        encryptionContext: Map<String, String> = emptyMap(),
    ): ByteArray = withOpenMaterial {
        ProviderEnvelope.decrypt(
            ciphertext,
            material,
            metadata,
            effectiveProviderKeyIdentity(material, properties.clientSideEncryption.keyId),
            properties.effectiveKeyVersion(),
            properties.effectiveEncryptionContext(encryptionContext),
        )
    }

    override val canonicalKeyIdentity: String
        get() = withOpenMaterial {
            "bluetape4k.s3.cse/" + material.providerToken + "/" +
                effectiveProviderKeyIdentity(material, properties.clientSideEncryption.keyId) + "/" +
                properties.effectiveKeyVersion()
        }

    override val keyFingerprint: String
        get() = withOpenMaterial {
            sha256Url(
                "${canonicalKeyIdentity}/" +
                    providerEncryptionContextIdentity(properties.effectiveEncryptionContext(emptyMap())),
            )
        }

    internal fun newEncryptionEnvelope(
        plaintext: ByteArray,
        encryptionContext: Map<String, String> = emptyMap(),
    ): ProviderEncryptedPayload = withOpenMaterial {
        ProviderEnvelope.encrypt(
            plaintext,
            material,
            effectiveProviderKeyIdentity(material, properties.clientSideEncryption.keyId),
            properties.effectiveKeyVersion(),
            random,
            properties.effectiveEncryptionContext(encryptionContext),
        )
    }

    internal fun newStreamingEnvelope(
        encryptionContext: Map<String, String> = emptyMap(),
    ): ProviderStreamingEnvelope = withOpenMaterial {
        ProviderEnvelope.newStreamingEnvelope(
            material,
            effectiveProviderKeyIdentity(material, properties.clientSideEncryption.keyId),
            properties.effectiveKeyVersion(),
            random,
            properties.effectiveEncryptionContext(encryptionContext),
        )
    }

    internal fun newPayloadCipher(envelope: ProviderStreamingEnvelope): Cipher = withOpenMaterial {
        ProviderEnvelopeCrypto.payloadCipher(
            Cipher.ENCRYPT_MODE,
            envelope.dataKey,
            envelope.nonce,
        ).also { it.updateAAD(envelope.aad) }
    }

    internal fun requireOpen() {
        synchronized(lifecycleLock) {
            check(!closed) { "S3 client-side encryption provider is already closed." }
        }
    }

    private fun <T> withOpenMaterial(block: () -> T): T = synchronized(lifecycleLock) {
        check(!closed) { "S3 client-side encryption provider is already closed." }
        block()
    }

    override fun close() {
        synchronized(lifecycleLock) {
            if (!closed) {
                closed = true
                material.close()
            }
        }
    }
}

private class ZeroizingByteArrayOutputStream(initialCapacity: Int) : ByteArrayOutputStream(initialCapacity) {
    fun zeroizeAndReset() {
        buf.fill(0, 0, count)
        reset()
    }
}

private fun S3Properties.effectiveEncryptionContext(
    callContext: Map<String, String>,
): Map<String, String> =
    clientSideEncryption.encryptionContext + callContext

private fun S3Properties.effectiveKeyVersion(): String =
    clientSideEncryption.keyVersion.orEmpty()

private fun effectiveProviderKeyIdentity(
    material: ClientSideEncryptionKeyMaterial,
    configuredKeyId: String?,
): String {
    configuredKeyId?.let { return it }
    val fingerprint = material.keyIdentityMaterial
    return try {
        "sha256:" + Base64.getUrlEncoder().withoutPadding().encodeToString(fingerprint)
    } finally {
        fingerprint.fill(0)
    }
}

private fun providerEncryptionContextIdentity(encryptionContext: Map<String, String>): String {
    val aad = ProviderEnvelope.canonicalContextAad(encryptionContext)
    return try {
        sha256Url(aad.toHex())
    } finally {
        aad.fill(0)
    }
}
