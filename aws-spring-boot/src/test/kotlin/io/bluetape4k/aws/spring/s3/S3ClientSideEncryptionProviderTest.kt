package io.bluetape4k.aws.spring.s3

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldBeSameInstanceAs
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.assertions.shouldContentEqual
import io.bluetape4k.assertions.shouldNotBeEmpty
import io.bluetape4k.assertions.shouldNotBeEqualTo
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.aws.spring.sqs.SqsExtendedPayloadReadException
import io.bluetape4k.concurrent.completableFutureOf
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.support.toUtf8Bytes
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import software.amazon.awssdk.core.async.AsyncRequestBody
import software.amazon.awssdk.core.async.AsyncResponseTransformer
import software.amazon.awssdk.core.async.ResponsePublisher
import software.amazon.awssdk.core.async.SdkPublisher
import software.amazon.awssdk.services.s3.S3AsyncClient
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.GetObjectResponse
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import software.amazon.awssdk.services.s3.model.PutObjectResponse
import java.nio.ByteBuffer
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.util.*
import java.util.function.Consumer
import javax.crypto.KeyGenerator
import javax.crypto.spec.SecretKeySpec

class S3ClientSideEncryptionProviderTest: AbstractS3Test() {

    companion object: KLogging()

    private val client: S3AsyncClient = mockk<S3AsyncClient>(relaxed = true)

    @BeforeEach
    fun beforeEach() {
        clearMocks(client)
    }

    @Test
    fun `defaults to KMS and accepts key version`() {
        val defaults = S3Properties.ClientSideEncryption()

        defaults.provider shouldBeEqualTo ClientSideEncryptionProvider.KMS
        defaults.keyVersion.shouldBeNull()

        val configured = S3Properties.ClientSideEncryption(keyVersion = "v2")

        log.debug { "configured=$configured" }
        configured.keyVersion shouldBeEqualTo "v2"
    }

    @Test
    fun `provider factories return caller key material`() {
        val secretKey = KeyGenerator.getInstance("AES").apply { init(128) }.generateKey()
        val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()

        val aesProvider = S3AesProvider(secretKey)
        val rsaProvider = S3RsaProvider(keyPair)

        aesProvider.generateSecretKey() shouldBeSameInstanceAs secretKey
        rsaProvider.generateKeyPair() shouldBeSameInstanceAs keyPair
    }

    @Test
    fun `key id and version reject blank or control character`() {
        val blankKeyId = assertFailsWith<IllegalArgumentException> {
            S3Properties.ClientSideEncryption(keyId = " ")
        }
        log.debug { "blankKeyId=$blankKeyId" }
        blankKeyId.message shouldContain "keyId"

        val controlKeyId = assertFailsWith<IllegalArgumentException> {
            S3Properties.ClientSideEncryption(keyId = "alias/test\u000B")
        }
        log.debug { "controlKeyId=$controlKeyId" }
        controlKeyId.message shouldContain "keyId"

        val blankKeyVersion = assertFailsWith<IllegalArgumentException> {
            S3Properties.ClientSideEncryption(keyVersion = "")
        }
        log.debug { "blankKeyVersion=$blankKeyVersion" }
        blankKeyVersion.message shouldContain "keyVersion"

        val controlKeyVersion = assertFailsWith<IllegalArgumentException> {
            S3Properties.ClientSideEncryption(keyVersion = "v1\u000B")
        }
        log.debug { "controlKeyVersion=$controlKeyVersion" }
        controlKeyVersion.message shouldContain "keyVersion"
    }

    @Test
    fun `aes material close rejects use and does not mutate caller key`() {
        val source = SecretKeySpec(ByteArray(32) { 3 }, "AES")
        val material = AesClientSideEncryptionKeyMaterial.from(S3AesProvider(source))

        material.keyIdentityMaterial.shouldNotBeEmpty()
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
            S3AesProvider(SecretKeySpec(ByteArray(32) { 9 }, "AES")),
        )
        val rsa = RsaClientSideEncryptionKeyMaterial.from(
            S3RsaProvider(
                KeyPairGenerator.getInstance("RSA")
                    .apply { initialize(2048) }
                    .generateKeyPair(),
            ),
        )
        val plaintext = "provider envelope".toUtf8Bytes()

        val aesEnvelope = ProviderEnvelope.encrypt(
            plaintext, aes, "orders", "v1", SecureRandom(), mapOf("service" to "orders"),
        )
        val rsaEnvelope = ProviderEnvelope.encrypt(
            plaintext, rsa, "orders", "v1", SecureRandom(), mapOf("service" to "orders"),
        )

        ProviderEnvelope.decrypt(
            aesEnvelope.ciphertext, aes, aesEnvelope.metadata, "orders", "v1",
            mapOf("service" to "orders"),
        ) shouldContentEqual plaintext

        ProviderEnvelope.decrypt(
            rsaEnvelope.ciphertext, rsa, rsaEnvelope.metadata, "orders", "v1",
            mapOf("service" to "orders"),
        ) shouldContentEqual plaintext

        aesEnvelope.metadata["bt4k-cek-provider"] shouldBeEqualTo "aes"
        rsaEnvelope.metadata["bt4k-cek-provider"] shouldBeEqualTo "rsa"
        aesEnvelope.metadata["bt4k-cek-wrap-nonce"].shouldNotBeNull()
        rsaEnvelope.metadata["bt4k-cek-wrap-nonce"].shouldBeNull()
    }

    @Test
    fun `provider envelope rejects context mismatch without plaintext`() {
        val material = AesClientSideEncryptionKeyMaterial.from(
            S3AesProvider(SecretKeySpec(ByteArray(32) { 10 }, "AES")),
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
            S3AesProvider(SecretKeySpec(ByteArray(32) { 11 }, "AES")),
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
            envelope.metadata + (
                    "bt4k-cek" to Base64.getEncoder().encodeToString(ByteArray(4 * 1024 + 1))
                    ),
        ).forEach { metadata ->
            assertFailsWith<IllegalArgumentException> {
                ProviderEnvelope.decrypt(
                    byteArrayOf(1),
                    material,
                    metadata,
                    "orders",
                    "v1",
                    emptyMap()
                )
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
                S3RsaProvider(KeyPair(publicPair.public, privatePair.private)),
            )
        }
    }

    @Test
    fun `rsa material rejects null key components`() {
        val pair = KeyPairGenerator.getInstance("RSA")
            .apply { initialize(2048) }
            .generateKeyPair()

        assertFailsWith<IllegalArgumentException> {
            RsaClientSideEncryptionKeyMaterial.from(
                S3RsaProvider.of(KeyPair(null, pair.private)),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            RsaClientSideEncryptionKeyMaterial.from(
                S3RsaProvider.of(KeyPair(pair.public, null)),
            )
        }
    }

    @Test
    fun `rsa material close rejects wrap and identity access`() {
        val pair = KeyPairGenerator.getInstance("RSA")
            .apply { initialize(2048) }
            .generateKeyPair()
        val material = RsaClientSideEncryptionKeyMaterial.from(S3RsaProvider(pair))
        material.close()

        assertFailsWith<IllegalStateException> { material.keyIdentityMaterial }
        assertFailsWith<IllegalStateException> {
            material.wrap(ByteArray(32), SecureRandom())
        }
    }

    @Test
    fun `provider template uploads ciphertext and exposes identity`() = runTest {
        every {
            client.putObject(
                any<Consumer<PutObjectRequest.Builder>>(),
                any<AsyncRequestBody>(),
            )
        } returns completableFutureOf(PutObjectResponse.builder().build())

        val template = S3ClientSideEncryptionProviderTemplate(
            s3AsyncClient = client,
            properties = S3Properties(
                clientSideEncryption = S3Properties.ClientSideEncryption(
                    enabled = true,
                    provider = ClientSideEncryptionProvider.AES,
                    keyId = "orders-key",
                    keyVersion = "v1",
                ),
            ),
            aesProvider = S3AesProvider(SecretKeySpec(ByteArray(32) { 8 }, "AES")),
        )

        template.uploadEncrypted("bucket", "object", "plain".toUtf8Bytes())

        template.canonicalKeyIdentity shouldContain "orders-key"
        template.keyFingerprint.isNotBlank().shouldBeTrue()

        verify(exactly = 1) {
            client.putObject(
                any<Consumer<PutObjectRequest.Builder>>(),
                any<AsyncRequestBody>(),
            )
        }
        template.close()
    }

    @Test
    fun `provider template rejects provider mismatch without plaintext`() {
        val template = testProviderTemplate()
        val metadata = mapOf(
            "bt4k-cek-version" to "2",
            "bt4k-cek-provider" to "rsa",
            "bt4k-cek-alg" to "AES/GCM/NoPadding",
            "bt4k-cek-wrap-alg" to "RSA/ECB/OAEPWithSHA-1AndMGF1Padding",
            "bt4k-cek-encoding" to "base64",
        )

        assertFailsWith<IllegalStateException> {
            template.decryptProviderPayload(byteArrayOf(1), metadata)
        }
        template.close()
    }

    @Test
    fun `provider template rejects operations after close`() = runTest {
        val template = testProviderTemplate(client = client)
        template.close()

        assertFailsWith<IllegalStateException> {
            template.uploadEncrypted("bucket", "key", byteArrayOf(1))
        }
        assertFailsWith<IllegalStateException> {
            template.downloadEncryptedBytesBounded("bucket", "key", emptyMap(), 1)
        }
        verify(exactly = 0) {
            client.putObject(
                any<Consumer<PutObjectRequest.Builder>>(),
                any<AsyncRequestBody>(),
            )
        }
        verify(exactly = 0) {
            client.getObject(
                any<Consumer<GetObjectRequest.Builder>>(),
                any<AsyncResponseTransformer<GetObjectResponse, *>>(),
            )
        }
    }

    @Test
    fun `bounded provider download accepts ciphertext at the exact limit`() = runTest {
        val template = testProviderTemplate(client = client)
        val plaintext = "bounded provider payload".encodeToByteArray()
        val envelope = template.newEncryptionEnvelope(plaintext)
        val response = GetObjectResponse.builder().metadata(envelope.metadata).build()
        val publisher = ResponsePublisher(
            response,
            SdkPublisher.fromIterable(listOf(ByteBuffer.wrap(envelope.ciphertext))),
        )
        every {
            client.getObject(
                any<GetObjectRequest>(),
                any<AsyncResponseTransformer<GetObjectResponse, ResponsePublisher<GetObjectResponse>>>(),
            )
        } returns completableFutureOf(publisher)

        val result = template.downloadEncryptedBytesBounded(
            "bucket",
            "key",
            emptyMap(),
            envelope.ciphertext.size,
        )

        result shouldContentEqual plaintext
        envelope.ciphertext.fill(0)
        template.close()
    }

    @Test
    fun `bounded provider download rejects ciphertext over limit`() = runTest {
        val template = testProviderTemplate(client = client)
        val envelope = template.newEncryptionEnvelope("too-large".encodeToByteArray())
        val response = GetObjectResponse.builder().metadata(envelope.metadata).build()
        val publisher = ResponsePublisher(
            response,
            SdkPublisher.fromIterable(listOf(ByteBuffer.wrap(envelope.ciphertext))),
        )
        every {
            client.getObject(
                any<GetObjectRequest>(),
                any<AsyncResponseTransformer<GetObjectResponse, ResponsePublisher<GetObjectResponse>>>(),
            )
        } returns completableFutureOf(publisher)

        assertFailsWith<SqsExtendedPayloadReadException> {
            template.downloadEncryptedBytesBounded(
                "bucket",
                "key",
                emptyMap(),
                envelope.ciphertext.size - 1,
            )
        }
        envelope.ciphertext.fill(0)
        template.close()
    }

    @Test
    fun `provider identity changes when effective context changes`() {
        val first = testProviderTemplate(
            properties = S3Properties(
                clientSideEncryption = S3Properties.ClientSideEncryption(
                    enabled = true,
                    provider = ClientSideEncryptionProvider.AES,
                    encryptionContext = mapOf("service" to "orders"),
                ),
            ),
        )
        log.debug { "first=$first" }

        val second = testProviderTemplate(
            properties = S3Properties(
                clientSideEncryption = S3Properties.ClientSideEncryption(
                    enabled = true,
                    provider = ClientSideEncryptionProvider.AES,
                    encryptionContext = mapOf("service" to "billing"),
                ),
            ),
        )
        log.debug { "second=$second" }
        
        first.keyFingerprint shouldNotBeEqualTo second.keyFingerprint
        testProviderTemplate(
            properties = S3Properties(
                clientSideEncryption = S3Properties.ClientSideEncryption(
                    enabled = true,
                    provider = ClientSideEncryptionProvider.AES,
                ),
            ),
        ).canonicalKeyIdentity shouldContain "sha256:"
        first.close()
        second.close()
    }

    private fun testProviderTemplate(
        client: S3AsyncClient = mockk(relaxed = true),
        properties: S3Properties = S3Properties(
            clientSideEncryption = S3Properties.ClientSideEncryption(
                enabled = true,
                provider = ClientSideEncryptionProvider.AES,
                keyId = "test-key",
            ),
        ),
    ): S3ClientSideEncryptionProviderTemplate =
        S3ClientSideEncryptionProviderTemplate(
            s3AsyncClient = client,
            properties = properties,
            aesProvider = S3AesProvider.of(SecretKeySpec(ByteArray(32) { 4 }, "AES")),
        )
}
