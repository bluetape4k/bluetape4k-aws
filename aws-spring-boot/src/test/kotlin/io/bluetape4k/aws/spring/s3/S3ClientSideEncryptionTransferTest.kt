package io.bluetape4k.aws.spring.s3

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineDispatcher
import org.junit.jupiter.api.Test
import software.amazon.awssdk.core.ResponseBytes
import software.amazon.awssdk.services.s3.S3AsyncClient
import software.amazon.awssdk.services.s3.model.HeadObjectRequest
import software.amazon.awssdk.services.s3.model.HeadObjectResponse
import software.amazon.awssdk.services.s3.model.GetObjectResponse
import software.amazon.awssdk.transfer.s3.model.CompletedDownload
import software.amazon.awssdk.transfer.s3.model.CompletedFileDownload
import software.amazon.awssdk.transfer.s3.model.CompletedFileUpload
import software.amazon.awssdk.transfer.s3.model.CompletedUpload
import software.amazon.awssdk.transfer.s3.model.DownloadFileRequest
import software.amazon.awssdk.transfer.s3.model.DownloadRequest
import software.amazon.awssdk.transfer.s3.model.UploadFileRequest
import software.amazon.awssdk.transfer.s3.model.UploadRequest
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.RejectedExecutionException
import java.util.function.Consumer
import kotlin.coroutines.CoroutineContext
import javax.crypto.spec.SecretKeySpec
import kotlin.test.assertFailsWith

class S3ClientSideEncryptionTransferTest {

    @Test
    fun `encrypted stream never sends plaintext to delegate`() = runSuspendIO {
        val tempDirectory = Files.createTempDirectory("bluetape-s3-cse-stream-")
        val operations = EncryptedRecordingTransferOperations()
        val delegate = RecordingS3OutputStreamProvider(operations, tempDirectory, thresholdBytes = 1)
        val template = testProviderTemplate()
        val plaintext = "plaintext that crosses threshold".encodeToByteArray()
        try {
            val encrypted = S3EncryptedOutputStream.create(
                template = template,
                outputStreamProvider = delegate,
                bucket = "bucket",
                key = "large.bin",
                contentType = "application/octet-stream",
                metadata = emptyMap(),
                encryptionContext = mapOf("service" to "orders"),
                ioDispatcher = Dispatchers.IO,
            )

            encrypted.use { it.write(plaintext) }

            operations.uploadedFileContents.size shouldBeEqualTo 1
            operations.uploadedFileContents.single().containsSubsequence(plaintext).shouldBeFalse()
            Files.list(tempDirectory).use { stream -> stream.count() shouldBeEqualTo 0L }
        } finally {
            template.close()
            Files.deleteIfExists(tempDirectory)
        }
    }

    @Test
    fun `encrypted stream completes once and rejects writes after completion`() = runSuspendIO {
        val tempDirectory = Files.createTempDirectory("bluetape-s3-cse-complete-")
        val operations = EncryptedRecordingTransferOperations()
        val delegate = RecordingS3OutputStreamProvider(operations, tempDirectory, thresholdBytes = 1)
        val template = testProviderTemplate()
        try {
            val encrypted = S3EncryptedOutputStream.create(
                template = template,
                outputStreamProvider = delegate,
                bucket = "bucket",
                key = "object",
                contentType = null,
                metadata = emptyMap(),
                encryptionContext = emptyMap(),
                ioDispatcher = Dispatchers.IO,
            )
            encrypted.write("payload".encodeToByteArray())
            encrypted.complete()
            encrypted.close()
            encrypted.complete()

            assertFailsWith<IllegalStateException> { encrypted.write(1) }
            assertFailsWith<IllegalStateException> { encrypted.write(byteArrayOf(), 0, 0) }
            operations.uploadedFileContents.size shouldBeEqualTo 1
        } finally {
            template.close()
            Files.deleteIfExists(tempDirectory)
        }
    }

    @Test
    fun `encrypted stream write failure discards delegate without uploading`() = runSuspendIO {
        val root = Files.createTempDirectory("bluetape-s3-cse-write-failure-")
        val blockingPath = root.resolve("not-a-directory")
        Files.write(blockingPath, byteArrayOf(1))
        val operations = EncryptedRecordingTransferOperations()
        val delegate = RecordingS3OutputStreamProvider(operations, blockingPath, thresholdBytes = 1)
        val template = testProviderTemplate()
        try {
            val encrypted = S3EncryptedOutputStream.create(
                template = template,
                outputStreamProvider = delegate,
                bucket = "bucket",
                key = "failed.bin",
                contentType = null,
                metadata = emptyMap(),
                encryptionContext = emptyMap(),
                ioDispatcher = Dispatchers.IO,
            )

            assertFailsWith<Exception> { encrypted.write(ByteArray(128) { 7 }) }
            operations.uploadedFileContents.size shouldBeEqualTo 0
            assertFailsWith<Exception> { encrypted.close() }
        } finally {
            template.close()
            Files.deleteIfExists(blockingPath)
            Files.deleteIfExists(root)
        }
    }

    @Test
    fun `dispatcher entry cancellation discards delegate and preserves cancellation`() = runSuspendIO {
        val tempDirectory = Files.createTempDirectory("bluetape-s3-cse-cancel-")
        val operations = EncryptedRecordingTransferOperations()
        val delegate = RecordingS3OutputStreamProvider(operations, tempDirectory, thresholdBytes = 1)
        val template = testProviderTemplate()
        try {
            val encrypted = S3EncryptedOutputStream.create(
                template = template,
                outputStreamProvider = delegate,
                bucket = "bucket",
                key = "cancelled.bin",
                contentType = null,
                metadata = emptyMap(),
                encryptionContext = emptyMap(),
                ioDispatcher = CancellingDispatcher,
            )
            encrypted.write(ByteArray(128) { 7 })

            val cancelled = assertFailsWith<CancellationException> { encrypted.complete() }
            cancelled.message shouldBeEqualTo "cancelled before dispatch"
            operations.uploadedFileContents.size shouldBeEqualTo 0
            Files.list(tempDirectory).use { stream -> stream.count() shouldBeEqualTo 0L }
        } finally {
            template.close()
            Files.deleteIfExists(tempDirectory)
        }
    }

    @Test
    fun `dispatcher rejection discards delegate and preserves terminal failure`() = runSuspendIO {
        val tempDirectory = Files.createTempDirectory("bluetape-s3-cse-rejected-")
        val operations = EncryptedRecordingTransferOperations()
        val delegate = RecordingS3OutputStreamProvider(operations, tempDirectory, thresholdBytes = 1)
        val template = testProviderTemplate()
        try {
            val encrypted = S3EncryptedOutputStream.create(
                template = template,
                outputStreamProvider = delegate,
                bucket = "bucket",
                key = "rejected.bin",
                contentType = null,
                metadata = emptyMap(),
                encryptionContext = emptyMap(),
                ioDispatcher = RejectingDispatcher,
            )
            encrypted.write(ByteArray(128) { 7 })

            val rejected = assertFailsWith<RejectedExecutionException> { encrypted.complete() }
            rejected.message shouldBeEqualTo "dispatcher rejected"
            operations.uploadedFileContents.size shouldBeEqualTo 0
            Files.list(tempDirectory).use { stream -> stream.count() shouldBeEqualTo 0L }
            assertFailsWith<RejectedExecutionException> { encrypted.complete() }
        } finally {
            template.close()
            Files.deleteIfExists(tempDirectory)
        }
    }

    @Test
    fun `encrypted file download validates ETag and writes plaintext after authentication`() = runSuspendIO {
        val root = Files.createTempDirectory("bluetape-s3-cse-download-")
        val destination = root.resolve("destination.bin")
        val sentinel = "keep this value".encodeToByteArray()
        Files.write(destination, sentinel)
        val client = mockk<S3AsyncClient>()
        val transfer = RecordingEncryptedDownloadOperations()
        val providerTemplate = testProviderTemplate(client = client)
        val adapter = S3ClientSideEncryptionTransferTemplate(client, providerTemplate, transfer, transfer)
        val plaintext = "file payload".encodeToByteArray()
        val envelope = providerTemplate.newEncryptionEnvelope(plaintext)
        val etag = "\"etag-v1\""
        every { client.headObject(any<Consumer<HeadObjectRequest.Builder>>()) } returns
            CompletableFuture.completedFuture(
                HeadObjectResponse.builder()
                    .contentLength(envelope.ciphertext.size.toLong())
                    .eTag(etag)
                    .build(),
            )
        transfer.payload = envelope.ciphertext.copyOf()
        transfer.response = GetObjectResponse.builder().metadata(envelope.metadata).build()

        try {
            adapter.downloadEncryptedFile("bucket", "object", destination)

            Files.readAllBytes(destination).contentEquals(plaintext).shouldBeTrue()
            transfer.capturedRequest?.getObjectRequest()?.ifMatch() shouldBeEqualTo etag
            transfer.downloadDestination?.let(Files::exists).shouldBeFalse()
        } finally {
            providerTemplate.close()
            envelope.ciphertext.fill(0)
            Files.deleteIfExists(destination)
            Files.deleteIfExists(root)
        }
    }

    @Test
    fun `encrypted file authentication failure preserves destination and cleans ciphertext file`() = runSuspendIO {
        val root = Files.createTempDirectory("bluetape-s3-cse-auth-failure-")
        val destination = root.resolve("destination.bin")
        val sentinel = "keep this value".encodeToByteArray()
        Files.write(destination, sentinel)
        val client = mockk<S3AsyncClient>()
        val transfer = RecordingEncryptedDownloadOperations()
        val providerTemplate = testProviderTemplate(client = client)
        val adapter = S3ClientSideEncryptionTransferTemplate(client, providerTemplate, transfer, transfer)
        val envelope = providerTemplate.newEncryptionEnvelope("file payload".encodeToByteArray())
        every { client.headObject(any<Consumer<HeadObjectRequest.Builder>>()) } returns
            CompletableFuture.completedFuture(
                HeadObjectResponse.builder()
                    .contentLength(envelope.ciphertext.size.toLong())
                    .eTag("\"etag-v1\"")
                    .build(),
            )
        transfer.payload = envelope.ciphertext.copyOf().also { ciphertext ->
            ciphertext[ciphertext.lastIndex] = (ciphertext.last().toInt() xor 1).toByte()
        }
        transfer.response = GetObjectResponse.builder().metadata(envelope.metadata).build()

        try {
            assertFailsWith<S3ClientSideEncryptionException> {
                adapter.downloadEncryptedFile("bucket", "object", destination)
            }
            Files.readAllBytes(destination).contentEquals(sentinel).shouldBeTrue()
            transfer.downloadDestination?.let(Files::exists).shouldBeFalse()
        } finally {
            providerTemplate.close()
            envelope.ciphertext.fill(0)
            Files.deleteIfExists(destination)
            Files.deleteIfExists(root)
        }
    }

    @Test
    fun `encrypted file download rejects oversized head before creating temp file`() = runSuspendIO {
        val root = Files.createTempDirectory("bluetape-s3-cse-oversize-")
        val destination = root.resolve("destination.bin")
        val sentinel = "keep this value".encodeToByteArray()
        Files.write(destination, sentinel)
        val client = mockk<S3AsyncClient>()
        val transfer = RecordingEncryptedDownloadOperations()
        val providerTemplate = testProviderTemplate(client = client)
        val adapter = S3ClientSideEncryptionTransferTemplate(client, providerTemplate, transfer, transfer)
        every { client.headObject(any<Consumer<HeadObjectRequest.Builder>>()) } returns
            CompletableFuture.completedFuture(
                HeadObjectResponse.builder()
                    .contentLength(S3BoundedEncryptedReadOperations.MAX_CIPHERTEXT_BYTES.toLong() + 1)
                    .eTag("\"etag-v1\"")
                    .build(),
            )

        try {
            assertFailsWith<IllegalArgumentException> {
                adapter.downloadEncryptedFile("bucket", "object", destination)
            }
            transfer.downloadCalls shouldBeEqualTo 0
            transfer.downloadDestination shouldBeEqualTo null
            Files.readAllBytes(destination).contentEquals(sentinel).shouldBeTrue()
        } finally {
            providerTemplate.close()
            Files.deleteIfExists(destination)
            Files.deleteIfExists(root)
        }
    }

    @Test
    fun `closed provider rejects encrypted file download before HEAD`() = runSuspendIO {
        val client = mockk<S3AsyncClient>(relaxed = true)
        val transfer = RecordingEncryptedDownloadOperations()
        val providerTemplate = testProviderTemplate(client = client)
        val adapter = S3ClientSideEncryptionTransferTemplate(client, providerTemplate, transfer, transfer)
        providerTemplate.close()

        assertFailsWith<IllegalStateException> {
            adapter.downloadEncryptedFile("bucket", "object", Files.createTempFile("cse-closed-", ".bin"))
        }
        verify(exactly = 0) { client.headObject(any<Consumer<HeadObjectRequest.Builder>>()) }
    }
}

private object CancellingDispatcher : CoroutineDispatcher() {
    override fun dispatch(context: CoroutineContext, block: Runnable): Unit =
        throw CancellationException("cancelled before dispatch")
}

private object RejectingDispatcher : CoroutineDispatcher() {
    override fun dispatch(context: CoroutineContext, block: Runnable): Unit =
        throw RejectedExecutionException("dispatcher rejected")
}

private class RecordingS3OutputStreamProvider(
    private val operations: S3TransferOperations,
    private val temporaryDirectory: Path,
    private val thresholdBytes: Long,
) : S3OutputStreamProvider {
    override fun outputStream(
        bucket: String,
        key: String,
        contentType: String?,
        metadata: Map<String, String>,
    ): S3OutputStream =
        S3OutputStream(
            operations = operations,
            bucket = bucket,
            key = key,
            thresholdBytes = thresholdBytes,
            contentType = contentType,
            metadata = metadata,
            temporaryDirectory = temporaryDirectory,
        )
}

private class EncryptedRecordingTransferOperations : S3TransferOperations {
    val uploadedFileContents = mutableListOf<ByteArray>()

    override suspend fun upload(
        bucket: String,
        key: String,
        bytes: ByteArray,
        configure: UploadRequest.Builder.() -> Unit,
    ): CompletedUpload =
        throw UnsupportedOperationException("EncryptedRecordingTransferOperations expects encrypted spill files.")

    override suspend fun uploadFile(
        bucket: String,
        key: String,
        source: Path,
        configure: UploadFileRequest.Builder.() -> Unit,
    ): CompletedFileUpload {
        uploadedFileContents += Files.readAllBytes(source)
        return mockk(relaxed = true)
    }

    override suspend fun downloadBytes(
        bucket: String,
        key: String,
        configure: DownloadRequest.UntypedBuilder.() -> Unit,
    ): CompletedDownload<ResponseBytes<GetObjectResponse>> =
        throw UnsupportedOperationException()

    override suspend fun downloadFile(
        bucket: String,
        key: String,
        destination: Path,
        configure: DownloadFileRequest.Builder.() -> Unit,
    ): CompletedFileDownload =
        throw UnsupportedOperationException()
}

private class RecordingEncryptedDownloadOperations :
    S3TransferOperations,
    S3OutputStreamProvider {
    var payload: ByteArray = byteArrayOf()
    var response: GetObjectResponse = GetObjectResponse.builder().build()
    var capturedRequest: DownloadFileRequest? = null
    var downloadDestination: Path? = null
    var downloadCalls: Int = 0

    override suspend fun upload(
        bucket: String,
        key: String,
        bytes: ByteArray,
        configure: UploadRequest.Builder.() -> Unit,
    ): CompletedUpload =
        throw UnsupportedOperationException()

    override suspend fun uploadFile(
        bucket: String,
        key: String,
        source: Path,
        configure: UploadFileRequest.Builder.() -> Unit,
    ): CompletedFileUpload =
        throw UnsupportedOperationException()

    override suspend fun downloadBytes(
        bucket: String,
        key: String,
        configure: DownloadRequest.UntypedBuilder.() -> Unit,
    ): CompletedDownload<ResponseBytes<GetObjectResponse>> =
        throw UnsupportedOperationException()

    override suspend fun downloadFile(
        bucket: String,
        key: String,
        destination: Path,
        configure: DownloadFileRequest.Builder.() -> Unit,
    ): CompletedFileDownload {
        downloadCalls++
        downloadDestination = destination
        capturedRequest = DownloadFileRequest.builder().destination(destination).apply(configure).build()
        Files.write(destination, payload)
        val completed = mockk<CompletedFileDownload>()
        every { completed.response() } returns response
        return completed
    }

    override fun outputStream(
        bucket: String,
        key: String,
        contentType: String?,
        metadata: Map<String, String>,
    ): S3OutputStream =
        throw UnsupportedOperationException()
}

private fun testProviderTemplate(
    client: S3AsyncClient = mockk(relaxed = true),
): S3ClientSideEncryptionProviderTemplate =
    S3ClientSideEncryptionProviderTemplate(
        s3AsyncClient = client,
        properties = S3Properties(
            clientSideEncryption = S3Properties.ClientSideEncryption(
                enabled = true,
                provider = ClientSideEncryptionProvider.AES,
                keyId = "test-key",
            ),
        ),
        aesProvider = S3AesProvider.of(SecretKeySpec(ByteArray(32) { 6 }, "AES")),
    )

private fun ByteArray.containsSubsequence(candidate: ByteArray): Boolean {
    return candidate.isEmpty() ||
        (candidate.size <= size && (0..size - candidate.size).any { start ->
            candidate.indices.all { offset -> this[start + offset] == candidate[offset] }
        })
}
