package io.bluetape4k.aws.spring.s3

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.aws.spring.kms.KmsDataKey
import io.bluetape4k.aws.spring.kms.KmsOperations
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.junit.jupiter.api.Test
import software.amazon.awssdk.core.ResponseBytes
import software.amazon.awssdk.core.async.AsyncRequestBody
import software.amazon.awssdk.core.async.AsyncResponseTransformer
import software.amazon.awssdk.core.async.ResponsePublisher
import software.amazon.awssdk.core.async.SdkPublisher
import software.amazon.awssdk.services.kms.model.DataKeySpec
import software.amazon.awssdk.services.s3.S3AsyncClient
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.GetObjectResponse
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import software.amazon.awssdk.services.s3.model.PutObjectResponse
import java.nio.ByteBuffer
import java.util.Base64
import java.util.concurrent.CompletableFuture
import java.util.function.Consumer
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class S3KmsKeyLifetimeTest {

    @Test
    fun `successful upload closes its generated data key`() = runSuspendIO {
        upload(CompletableFuture.completedFuture(PutObjectResponse.builder().build()))
    }

    @Test
    fun `failed upload closes its generated data key`() = runSuspendIO {
        upload(
            CompletableFuture.failedFuture(IllegalStateException("upload failed")),
            IllegalStateException::class.java,
        )
    }

    @Test
    fun `cancelled upload closes its generated data key and propagates cancellation`() = runSuspendIO {
        upload(CompletableFuture<PutObjectResponse>().apply { cancel(false) }, CancellationException::class.java)
    }

    @Test
    fun `cancelling an in flight upload clears key and cancels the AWS future`() = runSuspendIO {
        coroutineScope {
            val kms = RecordingKms()
            val client = mockk<S3AsyncClient>()
            val response = CompletableFuture<PutObjectResponse>()
            val submitted = CompletableDeferred<Unit>()
            every {
                client.putObject(any<Consumer<PutObjectRequest.Builder>>(), any<AsyncRequestBody>())
            } answers {
                submitted.complete(Unit)
                response
            }
            val operation = launch {
                template(client, kms).uploadEncrypted("bucket", "key", byteArrayOf(1, 2, 3))
            }
            try {
                submitted.await()
                kms.issuedPlaintext.all { it == 0.toByte() }.shouldBeTrue()
                operation.cancelAndJoin()
                response.isCancelled.shouldBeTrue()
                kms.issuedPlaintext.all { it == 0.toByte() }.shouldBeTrue()
            } finally {
                operation.cancelAndJoin()
                kms.generated.close()
            }
        }
    }

    @Test
    fun `normal and bounded downloads clear keys after successful decryption`() = runSuspendIO {
        download(bounded = false, corrupt = false)
        download(bounded = true, corrupt = false)
    }

    @Test
    fun `normal and bounded downloads clear keys after authentication failure`() = runSuspendIO {
        download(bounded = false, corrupt = true)
        download(bounded = true, corrupt = true)
    }

    @Test
    fun `cancelled downloads propagate cancellation before requesting a key`() = runSuspendIO {
        for (bounded in listOf(false, true)) {
            val kms = RecordingKms()
            val client = mockk<S3AsyncClient>()
            every {
                client.getObject(
                    any<Consumer<GetObjectRequest.Builder>>(),
                    any<AsyncResponseTransformer<GetObjectResponse, Any>>(),
                )
            } returns CompletableFuture<Any>().apply { cancel(false) }
            val template = template(client, kms)

            assertFailsWith<CancellationException> {
                if (bounded) template.downloadEncryptedBytesBounded("bucket", "key", emptyMap(), 1024)
                else template.downloadEncryptedBytes("bucket", "key")
            }
            kms.decryptCalls shouldBeEqualTo 0
        }
    }

    private suspend fun upload(
        response: CompletableFuture<PutObjectResponse>,
        expectedFailure: Class<out Throwable>? = null,
    ) {
        val kms = RecordingKms()
        val client = mockk<S3AsyncClient>()
        every {
            client.putObject(any<Consumer<PutObjectRequest.Builder>>(), any<AsyncRequestBody>())
        } returns response
        val failure = runCatching {
            template(client, kms).uploadEncrypted("bucket", "key", byteArrayOf(1, 2, 3))
        }.exceptionOrNull()
        if (expectedFailure == null) failure shouldBeEqualTo null
        else expectedFailure.isInstance(failure).shouldBeTrue()

        kms.issuedPlaintext.all { it == 0.toByte() }.shouldBeTrue()
        assertFailsWith<IllegalStateException> { kms.generated.plaintext }
    }

    private suspend fun download(bounded: Boolean, corrupt: Boolean) {
        val kms = RecordingKms()
        val client = mockk<S3AsyncClient>()
        val nonce = ByteArray(12) { 2 }
        val plaintext = byteArrayOf(1, 2, 3)
        val ciphertext = Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(kms.decrypted, "AES"), GCMParameterSpec(128, nonce))
            doFinal(plaintext)
        }
        if (corrupt) ciphertext[0] = (ciphertext[0].toInt() xor 1).toByte()
        val metadata = mapOf(
            "bt4k-cek-alg" to "AES/GCM/NoPadding",
            "bt4k-cek" to Base64.getEncoder().encodeToString(byteArrayOf(7)),
            "bt4k-cek-key-id" to "key-id",
            "bt4k-cek-nonce" to Base64.getEncoder().encodeToString(nonce),
        )
        val response = GetObjectResponse.builder().metadata(metadata).build()
        if (bounded) {
            every {
                client.getObject(
                    any<Consumer<GetObjectRequest.Builder>>(),
                    any<AsyncResponseTransformer<GetObjectResponse, ResponsePublisher<GetObjectResponse>>>(),
                )
            } returns CompletableFuture.completedFuture(
                ResponsePublisher(response, SdkPublisher.fromIterable(listOf(ByteBuffer.wrap(ciphertext)))),
            )
        } else {
            every {
                client.getObject(
                    any<Consumer<GetObjectRequest.Builder>>(),
                    any<AsyncResponseTransformer<GetObjectResponse, ResponseBytes<GetObjectResponse>>>(),
                )
            } returns CompletableFuture.completedFuture(ResponseBytes.fromByteArray(response, ciphertext))
        }
        val template = template(client, kms)
        suspend fun read(): ByteArray = if (bounded) {
            template.downloadEncryptedBytesBounded("bucket", "key", emptyMap(), 1024)
        } else {
            template.downloadEncryptedBytes("bucket", "key")
        }
        if (corrupt) assertFailsWith<AEADBadTagException> { read() }
        else read().toList() shouldBeEqualTo plaintext.toList()
        kms.decryptCalls shouldBeEqualTo 1
        kms.decrypted.all { it == 0.toByte() }.shouldBeTrue()
    }

    private fun template(client: S3AsyncClient, kms: KmsOperations) = S3ClientSideEncryptionTemplate(
        client,
        kms,
        S3Properties(clientSideEncryption = S3Properties.ClientSideEncryption(enabled = true, keyId = "key-id")),
    )

    private class RecordingKms : KmsOperations {
        val decrypted = ByteArray(32) { 3 }
        val generated by lazy { KmsDataKey("key-id", ByteArray(32) { 4 }, byteArrayOf(7)) }
        val issuedPlaintext: ByteArray by lazy {
            KmsDataKey::class.java.getDeclaredField("plaintextBytes").apply {
                isAccessible = true
            }.get(generated) as ByteArray
        }
        var decryptCalls = 0

        override suspend fun encrypt(plaintext: ByteArray, keyId: String?, encryptionContext: Map<String, String>) =
            error("unused")

        override suspend fun decrypt(
            ciphertext: ByteArray,
            keyId: String?,
            encryptionContext: Map<String, String>,
        ): ByteArray {
            decryptCalls++
            return decrypted
        }

        override suspend fun generateDataKey(
            keyId: String?,
            keySpec: DataKeySpec?,
            numberOfBytes: Int?,
            encryptionContext: Map<String, String>,
            useCache: Boolean,
        ): KmsDataKey = generated
    }
}
