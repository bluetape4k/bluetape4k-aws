package io.bluetape4k.aws.spring.kms

import io.bluetape4k.assertions.shouldBeInstanceOf
import io.bluetape4k.assertions.shouldContentEqual
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.concurrent.completableFutureOf
import io.bluetape4k.logging.KLogging
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import software.amazon.awssdk.core.SdkBytes
import software.amazon.awssdk.services.kms.KmsAsyncClient
import software.amazon.awssdk.services.kms.model.DataKeySpec
import software.amazon.awssdk.services.kms.model.GenerateDataKeyRequest
import software.amazon.awssdk.services.kms.model.GenerateDataKeyResponse
import java.util.function.Consumer

class KmsCoroutinesEncryptorTest {

    companion object: KLogging()

    private val client = mockk<KmsAsyncClient>()
    private val sdkPlaintextBytes = mockk<SdkBytes>()
    private val response = mockk<GenerateDataKeyResponse>()

    @BeforeEach
    fun beforeEach() {
        clearMocks(client, sdkPlaintextBytes, response)
    }

    @Test
    fun `cache put failure closes generated data key`() = runTest {
        val sdkPlaintext = ByteArray(32) { 7 }

        every {
            client.generateDataKey(any<Consumer<GenerateDataKeyRequest.Builder>>())
        } returns completableFutureOf(
            GenerateDataKeyResponse.builder()
                .keyId("alias/test")
                .plaintext(SdkBytes.fromByteArray(sdkPlaintext))
                .ciphertextBlob(SdkBytes.fromByteArray(byteArrayOf(1, 2, 3)))
                .build(),
        )
        val cache = FailingDataKeyCache()
        val encryptor = KmsCoroutinesEncryptor(
            kmsAsyncClient = client,
            properties = KmsProperties(keyId = "alias/test"),
            dataKeyCache = cache,
        )

        val failure = runCatching {
            encryptor.generateDataKey(
                keySpec = DataKeySpec.AES_256,
                useCache = true,
            )
        }.exceptionOrNull()

        failure.shouldBeInstanceOf<IllegalStateException>()
        cache.offered.shouldNotBeNull().use { offered ->
            ownedPlaintext(offered) shouldContentEqual ByteArray(32) { 0 }
        }
    }

    @Test
    fun `successful generation clears SDK plaintext temporary after copying`() = runTest {
        val sdkPlaintext = ByteArray(32) { 7 }
        every { sdkPlaintextBytes.asByteArray() } returns sdkPlaintext
        every { response.keyId() } returns "alias/test"
        every { response.plaintext() } returns sdkPlaintextBytes
        every { response.ciphertextBlob() } returns SdkBytes.fromByteArray(byteArrayOf(1, 2, 3))

        every {
            client.generateDataKey(any<Consumer<GenerateDataKeyRequest.Builder>>())
        } returns completableFutureOf(response)

        val generated = KmsCoroutinesEncryptor(
            kmsAsyncClient = client,
            properties = KmsProperties(keyId = "alias/test"),
            dataKeyCache = NoopDataKeyCache,
        ).generateDataKey(
            keySpec = DataKeySpec.AES_256,
            useCache = false,
        )

        try {
            sdkPlaintext shouldContentEqual ByteArray(32) { 0 }
            generated.plaintext shouldContentEqual ByteArray(32) { 7 }
        } finally {
            generated.close()
        }
    }

    private class FailingDataKeyCache: DataKeyCache {
        var offered: KmsDataKey? = null

        override fun get(key: KmsDataKeyCacheKey): KmsDataKey? = null

        override fun put(key: KmsDataKeyCacheKey, value: KmsDataKey) {
            offered = value
            throw IllegalStateException("synthetic cache failure")
        }

        override fun evict(key: KmsDataKeyCacheKey) = Unit

        override fun clear() = Unit
    }

    private fun ownedPlaintext(dataKey: KmsDataKey): ByteArray =
        KmsDataKey::class.java.getDeclaredField("plaintextBytes")
            .apply { isAccessible = true }
            .get(dataKey) as ByteArray
}
