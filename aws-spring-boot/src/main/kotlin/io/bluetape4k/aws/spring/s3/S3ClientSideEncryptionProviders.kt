package io.bluetape4k.aws.spring.s3

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.GeneralSecurityException
import java.security.KeyPair
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.MGF1ParameterSpec
import java.util.Base64
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource
import javax.crypto.spec.SecretKeySpec

private const val AES_128_KEY_BYTES = 16
private const val AES_192_KEY_BYTES = 24
private const val AES_256_KEY_BYTES = 32
private const val MIN_RSA_KEY_BITS = 2048
private const val BYTE_MASK = 0xff

fun interface S3AesProvider {
    fun generateSecretKey(): SecretKey

    companion object {
        fun of(key: SecretKey): S3AesProvider = S3AesProvider { key }
    }
}

fun interface S3RsaProvider {
    fun generateKeyPair(): KeyPair

    companion object {
        fun of(keyPair: KeyPair): S3RsaProvider = S3RsaProvider { keyPair }
    }
}

/** Provider envelope 처리 중 발생한 공개 경계 예외입니다. */
open class S3ClientSideEncryptionException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

internal sealed interface ClientSideEncryptionKeyMaterial : AutoCloseable {
    val providerToken: String
    val wrappingAlgorithm: String
    val keyIdentityMaterial: ByteArray

    fun wrap(dataKey: ByteArray, random: SecureRandom): WrappedDataKey

    fun unwrap(wrapped: ByteArray, nonce: ByteArray?): ByteArray
}

internal data class WrappedDataKey(
    val ciphertext: ByteArray,
    val nonce: ByteArray?,
)

internal data class ProviderEncryptedPayload(
    val ciphertext: ByteArray,
    val metadata: Map<String, String>,
)

internal data class ProviderStreamingEnvelope(
    val dataKey: ByteArray,
    val nonce: ByteArray,
    val aad: ByteArray,
    val metadata: Map<String, String>,
)

internal object ProviderEnvelope {
    private const val VERSION = "2"
    private const val CONTENT_ALGORITHM = "AES/GCM/NoPadding"
    private const val ENCODING = "base64"
    private const val NONCE_SIZE = 12
    private const val DATA_KEY_SIZE = 32
    private const val GCM_TAG_BITS = 128

    private const val VERSION_KEY = "bt4k-cek-version"
    private const val PROVIDER_KEY = "bt4k-cek-provider"
    private const val ALGORITHM_KEY = "bt4k-cek-alg"
    private const val WRAP_ALGORITHM_KEY = "bt4k-cek-wrap-alg"
    private const val ENCODING_KEY = "bt4k-cek-encoding"
    private const val WRAPPED_KEY = "bt4k-cek"
    private const val NONCE_KEY = "bt4k-cek-nonce"
    private const val WRAP_NONCE_KEY = "bt4k-cek-wrap-nonce"
    private const val KEY_ID_KEY = "bt4k-cek-key-id"
    private const val KEY_VERSION_KEY = "bt4k-cek-key-version"

    fun encrypt(
        plaintext: ByteArray,
        material: ClientSideEncryptionKeyMaterial,
        keyId: String,
        keyVersion: String,
        random: SecureRandom,
        encryptionContext: Map<String, String>,
    ): ProviderEncryptedPayload {
        val dataKey = ByteArray(DATA_KEY_SIZE).also(random::nextBytes)
        val nonce = ByteArray(NONCE_SIZE).also(random::nextBytes)
        var wrapped: WrappedDataKey? = null
        var aad: ByteArray? = null
        var ciphertext: ByteArray? = null
        var succeeded = false
        return try {
            wrapped = material.wrap(dataKey, random)
            aad = canonicalContextAad(encryptionContext)
            val cipher = payloadCipher(Cipher.ENCRYPT_MODE, dataKey, nonce)
            cipher.updateAAD(aad)
            val encrypted = cipher.doFinal(plaintext)
            ciphertext = encrypted
            val result = ProviderEncryptedPayload(
                ciphertext = encrypted,
                metadata = metadata(material, keyId, keyVersion, wrapped, nonce),
            )
            succeeded = true
            result
        } catch (error: S3ClientSideEncryptionException) {
            throw error
        } catch (error: GeneralSecurityException) {
            throw S3ClientSideEncryptionException("Provider envelope encryption failed.", error)
        } finally {
            dataKey.fill(0)
            nonce.fill(0)
            aad?.fill(0)
            wrapped?.ciphertext?.fill(0)
            wrapped?.nonce?.fill(0)
            if (!succeeded) ciphertext?.fill(0)
        }
    }

    fun decrypt(
        ciphertext: ByteArray,
        material: ClientSideEncryptionKeyMaterial,
        metadata: Map<String, String>,
        expectedKeyId: String,
        expectedKeyVersion: String,
        encryptionContext: Map<String, String>,
    ): ByteArray {
        val normalized = normalizeMetadata(metadata)
        var wrapped: ByteArray? = null
        var nonce: ByteArray? = null
        var wrapNonce: ByteArray? = null
        var dataKey: ByteArray? = null
        var aad: ByteArray? = null
        return try {
            validateMetadata(normalized, material, expectedKeyId, expectedKeyVersion)
            val decodedWrapped = decodeRequired(normalized, WRAPPED_KEY)
            wrapped = decodedWrapped
            val decodedNonce = decodeRequired(normalized, NONCE_KEY)
            nonce = decodedNonce
            require(decodedNonce.size == NONCE_SIZE) { "Provider envelope nonce must be 12 bytes." }
            val decodedWrapNonce = normalized[WRAP_NONCE_KEY]?.let { decode(it, WRAP_NONCE_KEY) }
            wrapNonce = decodedWrapNonce
            if (material.providerToken == "aes") {
                require(decodedWrapNonce?.size == NONCE_SIZE) {
                    "AES provider envelope wrap nonce must be 12 bytes."
                }
            } else {
                require(decodedWrapNonce == null) { "RSA provider envelope must not contain a wrap nonce." }
            }
            val unwrappedDataKey = material.unwrap(decodedWrapped, decodedWrapNonce)
            dataKey = unwrappedDataKey
            require(unwrappedDataKey.size == DATA_KEY_SIZE) { "Provider envelope data key must be 32 bytes." }
            aad = canonicalContextAad(encryptionContext)
            val cipher = payloadCipher(Cipher.DECRYPT_MODE, unwrappedDataKey, decodedNonce)
            cipher.updateAAD(aad)
            try {
                cipher.doFinal(ciphertext)
            } catch (error: GeneralSecurityException) {
                throw S3ClientSideEncryptionException("Provider envelope authentication failed.", error)
            }
        } finally {
            wrapped?.fill(0)
            nonce?.fill(0)
            wrapNonce?.fill(0)
            dataKey?.fill(0)
            aad?.fill(0)
        }
    }

    fun mergeMetadata(
        userMetadata: Map<String, String>,
        reservedMetadata: Map<String, String>,
    ): Map<String, String> {
        val result = LinkedHashMap<String, String>(userMetadata.size + reservedMetadata.size)
        val normalizedKeys = HashSet<String>()
        userMetadata.forEach { (key, value) ->
            val normalized = key.lowercase(Locale.ROOT)
            require(normalizedKeys.add(normalized)) {
                "Duplicate S3 metadata key is not allowed: $key"
            }
            require(normalized !in reservedMetadata.keys.map { it.lowercase(Locale.ROOT) }) {
                "User metadata collides with reserved provider metadata: $key"
            }
            result[key] = value
        }
        reservedMetadata.forEach { (key, value) ->
            val normalized = key.lowercase(Locale.ROOT)
            require(normalizedKeys.add(normalized)) {
                "Duplicate provider metadata key is not allowed: $key"
            }
            result[normalized] = value
        }
        return result
    }

    fun newStreamingEnvelope(
        material: ClientSideEncryptionKeyMaterial,
        keyId: String,
        keyVersion: String,
        random: SecureRandom,
        encryptionContext: Map<String, String>,
    ): ProviderStreamingEnvelope {
        val dataKey = ByteArray(DATA_KEY_SIZE).also(random::nextBytes)
        val nonce = ByteArray(NONCE_SIZE).also(random::nextBytes)
        var wrapped: WrappedDataKey? = null
        var aad: ByteArray? = null
        var succeeded = false
        return try {
            wrapped = material.wrap(dataKey, random)
            aad = canonicalContextAad(encryptionContext)
            ProviderStreamingEnvelope(
                dataKey = dataKey,
                nonce = nonce,
                aad = aad,
                metadata = metadata(material, keyId, keyVersion, wrapped, nonce),
            ).also { succeeded = true }
        } finally {
            wrapped?.ciphertext?.fill(0)
            wrapped?.nonce?.fill(0)
            if (!succeeded) {
                dataKey.fill(0)
                nonce.fill(0)
                aad?.fill(0)
            }
        }
    }

    fun canonicalContextAad(encryptionContext: Map<String, String>): ByteArray {
        val output = ByteArrayOutputStream()
        encryptionContext.toSortedMap().forEach { (key, value) ->
            require(key.isNotBlank() && key.none(Char::isISOControl)) {
                "Provider encryption context keys must not be blank or contain control characters."
            }
            require(value.none(Char::isISOControl)) {
                "Provider encryption context values must not contain control characters."
            }
            val keyBytes = key.toByteArray(StandardCharsets.UTF_8)
            val valueBytes = value.toByteArray(StandardCharsets.UTF_8)
            try {
                output.write(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(keyBytes.size).array())
                output.write(keyBytes)
                output.write(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(valueBytes.size).array())
                output.write(valueBytes)
            } finally {
                keyBytes.fill(0)
                valueBytes.fill(0)
            }
        }
        return output.toByteArray()
    }

    private fun metadata(
        material: ClientSideEncryptionKeyMaterial,
        keyId: String,
        keyVersion: String,
        wrapped: WrappedDataKey,
        nonce: ByteArray,
    ): Map<String, String> = buildMap {
        put(VERSION_KEY, VERSION)
        put(PROVIDER_KEY, material.providerToken)
        put(ALGORITHM_KEY, CONTENT_ALGORITHM)
        put(WRAP_ALGORITHM_KEY, material.wrappingAlgorithm)
        put(ENCODING_KEY, ENCODING)
        put(WRAPPED_KEY, Base64.getEncoder().encodeToString(wrapped.ciphertext))
        put(NONCE_KEY, Base64.getEncoder().encodeToString(nonce))
        wrapped.nonce?.let { put(WRAP_NONCE_KEY, Base64.getEncoder().encodeToString(it)) }
        put(KEY_ID_KEY, keyId)
        if (keyVersion.isNotEmpty()) put(KEY_VERSION_KEY, keyVersion)
    }

    private fun normalizeMetadata(metadata: Map<String, String>): Map<String, String> {
        val result = LinkedHashMap<String, String>(metadata.size)
        metadata.forEach { (key, value) ->
            val normalized = key.lowercase(Locale.ROOT)
            require(result.put(normalized, value) == null) {
                "Duplicate provider metadata key is not allowed: $key"
            }
        }
        return result
    }

    private fun validateMetadata(
        metadata: Map<String, String>,
        material: ClientSideEncryptionKeyMaterial,
        expectedKeyId: String,
        expectedKeyVersion: String,
    ) {
        require(metadata[VERSION_KEY] == VERSION) { "Unsupported provider envelope version." }
        check(metadata[PROVIDER_KEY] == material.providerToken) {
            "Provider envelope provider does not match the configured provider."
        }
        require(metadata[ALGORITHM_KEY] == CONTENT_ALGORITHM) {
            "Unsupported provider envelope content algorithm."
        }
        require(metadata[WRAP_ALGORITHM_KEY] == material.wrappingAlgorithm) {
            "Provider envelope wrapping algorithm does not match the configured provider."
        }
        require(metadata[ENCODING_KEY] == ENCODING) { "Unsupported provider envelope encoding." }
        check(metadata[KEY_ID_KEY] == expectedKeyId) {
            "Provider envelope key identity does not match the configured provider."
        }
        check(metadata[KEY_VERSION_KEY].orEmpty() == expectedKeyVersion) {
            "Provider envelope key version does not match the configured provider."
        }
    }

    private fun decodeRequired(metadata: Map<String, String>, key: String): ByteArray {
        val value = metadata[key]
        require(value != null) { "Provider envelope metadata is missing: $key" }
        return decode(value, key)
    }

    private fun decode(value: String, key: String): ByteArray = try {
        Base64.getDecoder().decode(value)
    } catch (error: IllegalArgumentException) {
        throw IllegalArgumentException("Provider envelope metadata is not valid base64: $key", error)
    }

    private fun payloadCipher(mode: Int, dataKey: ByteArray, nonce: ByteArray): Cipher =
        ProviderEnvelopeCrypto.payloadCipher(mode, dataKey, nonce)

}

internal object ProviderEnvelopeCrypto {
    private const val CONTENT_ALGORITHM = "AES/GCM/NoPadding"
    private const val NONCE_SIZE = 12
    private const val GCM_TAG_BITS = 128
    private const val RSA_ALGORITHM = "RSA/ECB/OAEPWithSHA-1AndMGF1Padding"

    fun payloadCipher(mode: Int, dataKey: ByteArray, nonce: ByteArray): Cipher =
        Cipher.getInstance(CONTENT_ALGORITHM).apply {
            init(
                mode,
                SecretKeySpec(dataKey, "AES"),
                GCMParameterSpec(GCM_TAG_BITS, nonce),
            )
        }

    fun aesGcmWrap(
        dataKey: ByteArray,
        keyBytes: ByteArray,
        random: SecureRandom,
    ): WrappedDataKey {
        val nonce = ByteArray(NONCE_SIZE).also(random::nextBytes)
        return try {
            val cipher = Cipher.getInstance(CONTENT_ALGORITHM).apply {
                init(
                    Cipher.ENCRYPT_MODE,
                    SecretKeySpec(keyBytes, "AES"),
                    GCMParameterSpec(GCM_TAG_BITS, nonce),
                )
            }
            WrappedDataKey(cipher.doFinal(dataKey), nonce)
        } catch (error: GeneralSecurityException) {
            nonce.fill(0)
            throw S3ClientSideEncryptionException("AES provider key wrapping failed.", error)
        }
    }

    fun aesGcmUnwrap(
        wrapped: ByteArray,
        keyBytes: ByteArray,
        nonce: ByteArray,
    ): ByteArray = try {
        val cipher = Cipher.getInstance(CONTENT_ALGORITHM).apply {
            init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(keyBytes, "AES"),
                GCMParameterSpec(GCM_TAG_BITS, nonce),
            )
        }
        cipher.doFinal(wrapped)
    } catch (error: GeneralSecurityException) {
        throw S3ClientSideEncryptionException("AES provider key unwrapping failed.", error)
    }

    fun rsaOaepWrap(dataKey: ByteArray, publicKey: RSAPublicKey, random: SecureRandom): ByteArray =
        try {
            Cipher.getInstance(RSA_ALGORITHM).apply {
                init(Cipher.ENCRYPT_MODE, publicKey, oaepParameterSpec, random)
            }.doFinal(dataKey)
        } catch (error: GeneralSecurityException) {
            throw S3ClientSideEncryptionException("RSA provider key wrapping failed.", error)
        }

    fun rsaOaepUnwrap(wrapped: ByteArray, privateKey: RSAPrivateKey): ByteArray =
        try {
            Cipher.getInstance(RSA_ALGORITHM).apply {
                init(Cipher.DECRYPT_MODE, privateKey, oaepParameterSpec)
            }.doFinal(wrapped)
        } catch (error: GeneralSecurityException) {
            throw S3ClientSideEncryptionException("RSA provider key unwrapping failed.", error)
        }

    private val oaepParameterSpec = OAEPParameterSpec(
        "SHA-1",
        "MGF1",
        MGF1ParameterSpec.SHA1,
        PSource.PSpecified.DEFAULT,
    )
}

internal fun ByteArray.toHex(): String =
    joinToString(separator = "") { byte -> "%02x".format(Locale.ROOT, byte.toInt() and BYTE_MASK) }

internal fun sha256Url(value: String): String =
    Base64.getUrlEncoder().withoutPadding().encodeToString(
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(StandardCharsets.UTF_8)),
    )

internal class AesClientSideEncryptionKeyMaterial private constructor(
    private val keyBytes: ByteArray,
) : ClientSideEncryptionKeyMaterial {
    private var closed = false
    override val providerToken: String = "aes"
    override val wrappingAlgorithm: String = "AES/GCM/NoPadding"
    override val keyIdentityMaterial: ByteArray
        get() {
            check(!closed) { "AES provider material is closed." }
            return MessageDigest.getInstance("SHA-256").digest(keyBytes)
        }
    override fun wrap(dataKey: ByteArray, random: SecureRandom): WrappedDataKey =
        ProviderEnvelopeCrypto.aesGcmWrap(dataKey, openBytes(), random)
    override fun unwrap(wrapped: ByteArray, nonce: ByteArray?): ByteArray =
        ProviderEnvelopeCrypto.aesGcmUnwrap(wrapped, openBytes(), requireNotNull(nonce))
    override fun close() {
        if (!closed) {
            closed = true
            keyBytes.fill(0)
        }
    }

    private fun openBytes(): ByteArray {
        check(!closed) { "AES provider material is closed." }
        return keyBytes
    }

    companion object {
        fun from(provider: S3AesProvider): AesClientSideEncryptionKeyMaterial {
            val key = requireNotNull(provider.generateSecretKey()) {
                "S3AesProvider returned null key."
            }
            require(key.algorithm.equals("AES", ignoreCase = true)) {
                "AES provider key algorithm must be AES."
            }
            val encoded = requireNotNull(key.encoded) {
                "AES provider key must expose encoded bytes."
            }
            require(
                encoded.size == AES_128_KEY_BYTES ||
                    encoded.size == AES_192_KEY_BYTES ||
                    encoded.size == AES_256_KEY_BYTES,
            ) {
                "AES provider key must be 16, 24, or 32 bytes. size=" + encoded.size
            }
            return AesClientSideEncryptionKeyMaterial(encoded.copyOf())
        }
    }
}

internal class RsaClientSideEncryptionKeyMaterial private constructor(
    private var publicKey: RSAPublicKey?,
    private var privateKey: RSAPrivateKey?,
) : ClientSideEncryptionKeyMaterial {
    override val providerToken: String = "rsa"
    override val wrappingAlgorithm: String = "RSA/ECB/OAEPWithSHA-1AndMGF1Padding"
    override val keyIdentityMaterial: ByteArray
        get() = MessageDigest.getInstance("SHA-256").digest(
            checkNotNull(publicKey) { "RSA provider material is closed." }.encoded,
        )
    override fun wrap(dataKey: ByteArray, random: SecureRandom): WrappedDataKey =
        WrappedDataKey(
            ciphertext = ProviderEnvelopeCrypto.rsaOaepWrap(
                dataKey,
                checkNotNull(publicKey) { "RSA provider material is closed." },
                random,
            ),
            nonce = null,
        )
    override fun unwrap(wrapped: ByteArray, nonce: ByteArray?): ByteArray =
        ProviderEnvelopeCrypto.rsaOaepUnwrap(
            wrapped,
            checkNotNull(privateKey) { "RSA provider material is closed." },
        )
    override fun close() {
        publicKey = null
        privateKey = null
    }

    companion object {
        fun from(provider: S3RsaProvider): RsaClientSideEncryptionKeyMaterial {
            val pair = requireNotNull(provider.generateKeyPair()) {
                "S3RsaProvider returned null key pair."
            }
            require(pair.public.algorithm.equals("RSA", ignoreCase = true)) {
                "RSA public key algorithm must be RSA."
            }
            require(pair.private.algorithm.equals("RSA", ignoreCase = true)) {
                "RSA private key algorithm must be RSA."
            }
            val public = pair.public as? RSAPublicKey
            val private = pair.private as? RSAPrivateKey
            require(public != null && private != null && public.modulus.bitLength() >= MIN_RSA_KEY_BITS) {
                "RSA provider public key must be at least 2048 bits."
            }
            require(public.modulus == private.modulus) {
                "RSA provider public and private key modulus must match."
            }
            require(private.modulus.bitLength() >= MIN_RSA_KEY_BITS) {
                "RSA provider private key must be at least 2048 bits."
            }
            return RsaClientSideEncryptionKeyMaterial(public, private)
        }
    }
}
