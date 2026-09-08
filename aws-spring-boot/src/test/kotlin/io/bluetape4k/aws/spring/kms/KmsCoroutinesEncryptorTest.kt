package io.bluetape4k.aws.spring.kms

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeInstanceOf
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import software.amazon.awssdk.core.SdkBytes
import software.amazon.awssdk.services.kms.KmsAsyncClient
import software.amazon.awssdk.services.kms.model.DataKeySpec
import software.amazon.awssdk.services.kms.model.GenerateDataKeyRequest
import software.amazon.awssdk.services.kms.model.GenerateDataKeyResponse
import java.util.concurrent.CompletableFuture
import java.util.function.Consumer

class KmsCoroutinesEncryptorTest {

    @Test
    fun `cache put failure closes generated data key`() = runSuspendIO {
        val sdkPlaintext = ByteArray(32) { 7 }
        val client = mockk<KmsAsyncClient>()
        every {
            client.generateDataKey(any<Consumer<GenerateDataKeyRequest.Builder>>())
        } returns CompletableFuture.completedFuture(
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
        val offered = cache.offered.shouldNotBeNull()
        try {
            ownedPlaintext(offered).toList() shouldBeEqualTo List(32) { 0.toByte() }
        } finally {
            offered.close()
        }
    }

    @Test
    fun `successful generation clears SDK plaintext temporary after copying`() = runSuspendIO {
        val sdkPlaintext = ByteArray(32) { 7 }
        val sdkPlaintextBytes = mockk<SdkBytes>()
        every { sdkPlaintextBytes.asByteArray() } returns sdkPlaintext
        val response = mockk<GenerateDataKeyResponse>()
        every { response.keyId() } returns "alias/test"
        every { response.plaintext() } returns sdkPlaintextBytes
        every { response.ciphertextBlob() } returns SdkBytes.fromByteArray(byteArrayOf(1, 2, 3))
        val client = mockk<KmsAsyncClient>()
        every {
            client.generateDataKey(any<Consumer<GenerateDataKeyRequest.Builder>>())
        } returns CompletableFuture.completedFuture(response)

        val generated = KmsCoroutinesEncryptor(
            kmsAsyncClient = client,
            properties = KmsProperties(keyId = "alias/test"),
            dataKeyCache = NoopDataKeyCache,
        ).generateDataKey(
            keySpec = DataKeySpec.AES_256,
            useCache = false,
        )

        try {
            sdkPlaintext.toList() shouldBeEqualTo List(32) { 0.toByte() }
            generated.plaintext.toList() shouldBeEqualTo List(32) { 7.toByte() }
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
        KmsDataKey::class.java.getDeclaredField("plaintextBytes").apply {
            isAccessible = true
        }.get(dataKey) as ByteArray
}
