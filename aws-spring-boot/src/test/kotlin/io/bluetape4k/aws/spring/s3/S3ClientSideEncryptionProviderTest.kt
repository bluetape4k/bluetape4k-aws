package io.bluetape4k.aws.spring.s3

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeSameInstanceAs
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.assertions.shouldNotBeNull
import java.security.KeyPair
import org.junit.jupiter.api.Test
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.KeyGenerator
import javax.crypto.spec.SecretKeySpec

class S3ClientSideEncryptionProviderTest {

    @Test
    fun `defaults to KMS and accepts key version`() {
        val defaults = S3Properties.ClientSideEncryption()

        defaults.provider shouldBeEqualTo ClientSideEncryptionProvider.KMS
        defaults.keyVersion shouldBeEqualTo null

        val configured = S3Properties.ClientSideEncryption(keyVersion = "v2")

        configured.keyVersion shouldBeEqualTo "v2"
    }

    @Test
    fun `provider factories return caller key material`() {
        val secretKey = KeyGenerator.getInstance("AES").apply { init(128) }.generateKey()
        val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()

        val aesProvider = S3AesProvider.of(secretKey)
        val rsaProvider = S3RsaProvider.of(keyPair)

        aesProvider.generateSecretKey() shouldBeSameInstanceAs secretKey
        rsaProvider.generateKeyPair() shouldBeSameInstanceAs keyPair
    }

    @Test
    fun `key id and version reject blank or control character`() {
        val blankKeyId = assertFailsWith<IllegalArgumentException> {
            S3Properties.ClientSideEncryption(keyId = " ")
        }
        blankKeyId.message.orEmpty() shouldContain "keyId"

        val controlKeyId = assertFailsWith<IllegalArgumentException> {
            S3Properties.ClientSideEncryption(keyId = "alias/test\u000B")
        }
        controlKeyId.message.orEmpty() shouldContain "keyId"

        val blankKeyVersion = assertFailsWith<IllegalArgumentException> {
            S3Properties.ClientSideEncryption(keyVersion = "")
        }
        blankKeyVersion.message.orEmpty() shouldContain "keyVersion"

        val controlKeyVersion = assertFailsWith<IllegalArgumentException> {
            S3Properties.ClientSideEncryption(keyVersion = "v1\u000B")
        }
        controlKeyVersion.message.orEmpty() shouldContain "keyVersion"
    }

    @Test
    fun `aes material close rejects use and does not mutate caller key`() {
        val source = SecretKeySpec(ByteArray(32) { 3 }, "AES")
        val material = AesClientSideEncryptionKeyMaterial.from(S3AesProvider.of(source))

        material.keyIdentityMaterial.isNotEmpty().shouldBeTrue()
        material.close()
        source.encoded.all { it == 3.toByte() }.shouldBeTrue()
        assertFailsWith<IllegalStateException> {
            material.wrap(ByteArray(32), SecureRandom())
        }
    }

    @Test
    fun `rsa material rejects small key`() {
        val generator = KeyPairGenerator.getInstance("RSA").apply { initialize(1024) }

        assertFailsWith<IllegalArgumentException> {
            RsaClientSideEncryptionKeyMaterial.from(
                S3RsaProvider.of(generator.generateKeyPair()),
            )
        }
    }

    @Test
    fun `provider envelope round trips aes and rsa`() {
        val aes = AesClientSideEncryptionKeyMaterial.from(
            S3AesProvider.of(SecretKeySpec(ByteArray(32) { 9 }, "AES")),
        )
        val rsa = RsaClientSideEncryptionKeyMaterial.from(
            S3RsaProvider.of(
                KeyPairGenerator.getInstance("RSA")
                    .apply { initialize(2048) }
                    .generateKeyPair(),
            ),
        )
        val plaintext = "provider envelope".encodeToByteArray()

        val aesEnvelope = ProviderEnvelope.encrypt(
            plaintext, aes, "orders", "v1", SecureRandom(), mapOf("service" to "orders"),
        )
        val rsaEnvelope = ProviderEnvelope.encrypt(
            plaintext, rsa, "orders", "v1", SecureRandom(), mapOf("service" to "orders"),
        )

        ProviderEnvelope.decrypt(
            aesEnvelope.ciphertext, aes, aesEnvelope.metadata, "orders", "v1",
            mapOf("service" to "orders"),
        ).contentEquals(plaintext).shouldBeTrue()
        ProviderEnvelope.decrypt(
            rsaEnvelope.ciphertext, rsa, rsaEnvelope.metadata, "orders", "v1",
            mapOf("service" to "orders"),
        ).contentEquals(plaintext).shouldBeTrue()
        aesEnvelope.metadata["bt4k-cek-provider"] shouldBeEqualTo "aes"
        rsaEnvelope.metadata["bt4k-cek-provider"] shouldBeEqualTo "rsa"
        aesEnvelope.metadata["bt4k-cek-wrap-nonce"].shouldNotBeNull()
        rsaEnvelope.metadata["bt4k-cek-wrap-nonce"] shouldBeEqualTo null
    }

    @Test
    fun `provider envelope rejects context mismatch without plaintext`() {
        val material = AesClientSideEncryptionKeyMaterial.from(
            S3AesProvider.of(SecretKeySpec(ByteArray(32) { 10 }, "AES")),
        )
        val envelope = ProviderEnvelope.encrypt(
            "context-bound".encodeToByteArray(),
            material,
            "orders",
            "v1",
            SecureRandom(),
            mapOf("service" to "orders"),
        )

        assertFailsWith<S3ClientSideEncryptionException> {
            ProviderEnvelope.decrypt(
                envelope.ciphertext,
                material,
                envelope.metadata,
                "orders",
                "v1",
                mapOf("service" to "billing"),
            )
        }
    }

    @Test
    fun `provider metadata validation rejects missing malformed and colliding fields`() {
        val material = AesClientSideEncryptionKeyMaterial.from(
            S3AesProvider.of(SecretKeySpec(ByteArray(32) { 11 }, "AES")),
        )
        val envelope = ProviderEnvelope.encrypt(
            byteArrayOf(1, 2, 3), material, "orders", "v1", SecureRandom(), emptyMap(),
        )

        listOf(
            envelope.metadata - "bt4k-cek-version",
            envelope.metadata + ("bt4k-cek-nonce" to "not-base64"),
            envelope.metadata + (
                "bt4k-cek-nonce" to Base64.getEncoder().encodeToString(byteArrayOf(1))
            ),
            envelope.metadata + ("bt4k-cek-wrap-alg" to "wrong"),
        ).forEach { metadata ->
            assertFailsWith<IllegalArgumentException> {
                ProviderEnvelope.decrypt(byteArrayOf(1), material, metadata, "orders", "v1", emptyMap())
            }
        }

        assertFailsWith<IllegalArgumentException> {
            ProviderEnvelope.mergeMetadata(
                mapOf("BT4K-CEK" to "caller-value"),
                envelope.metadata,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            ProviderEnvelope.mergeMetadata(
                mapOf("bt4k-cek" to "first", "BT4K-CEK" to "duplicate"),
                emptyMap(),
            )
        }
    }

    @Test
    fun `rsa material rejects mismatched public and private keys`() {
        val publicPair = KeyPairGenerator.getInstance("RSA")
            .apply { initialize(2048) }
            .generateKeyPair()
        val privatePair = KeyPairGenerator.getInstance("RSA")
            .apply { initialize(2048) }
            .generateKeyPair()

        assertFailsWith<IllegalArgumentException> {
            RsaClientSideEncryptionKeyMaterial.from(
                S3RsaProvider.of(KeyPair(publicPair.public, privatePair.private)),
            )
        }
    }

    @Test
    fun `rsa material close rejects wrap and identity access`() {
        val pair = KeyPairGenerator.getInstance("RSA")
            .apply { initialize(2048) }
            .generateKeyPair()
        val material = RsaClientSideEncryptionKeyMaterial.from(S3RsaProvider.of(pair))
        material.close()

        assertFailsWith<IllegalStateException> { material.keyIdentityMaterial }
        assertFailsWith<IllegalStateException> {
            material.wrap(ByteArray(32), SecureRandom())
        }
    }
}
