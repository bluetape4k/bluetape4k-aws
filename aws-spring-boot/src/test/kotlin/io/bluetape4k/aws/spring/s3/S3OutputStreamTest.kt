package io.bluetape4k.aws.spring.s3

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeSameInstanceAs
import io.bluetape4k.assertions.shouldBeTrue
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import software.amazon.awssdk.core.async.AsyncRequestBody
import software.amazon.awssdk.core.ResponseBytes
import software.amazon.awssdk.services.s3.model.GetObjectResponse
import software.amazon.awssdk.transfer.s3.model.CompletedDownload
import software.amazon.awssdk.transfer.s3.model.CompletedFileDownload
import software.amazon.awssdk.transfer.s3.model.CompletedFileUpload
import software.amazon.awssdk.transfer.s3.model.CompletedUpload
import software.amazon.awssdk.transfer.s3.model.DownloadFileRequest
import software.amazon.awssdk.transfer.s3.model.DownloadRequest
import software.amazon.awssdk.transfer.s3.model.UploadFileRequest
import software.amazon.awssdk.transfer.s3.model.UploadRequest
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertFailsWith

class S3OutputStreamTest {

    @Test
    fun `small payload is uploaded from memory on close`() {
    val operations = RecordingTransferOperations()
        val output = S3OutputStream(
            operations = operations,
            bucket = "bucket",
            key = "docs/readme.txt",
            thresholdBytes = 8,
            contentType = "text/plain",
            metadata = mapOf("source" to "test"),
        )

        output.write("hello".encodeToByteArray())
        operations.uploadedBytes.size shouldBeEqualTo 0

        output.close()

        operations.uploadedBytes.single().decodeToString() shouldBeEqualTo "hello"
        operations.uploadedContentTypes.single() shouldBeEqualTo "text/plain"
        operations.uploadedMetadata.single() shouldBeEqualTo mapOf("source" to "test")
    }

    @Test
    fun `payload over threshold is spooled to a temporary file and cleaned after close`() {
        val operations = RecordingTransferOperations()
        val tempDirectory = Files.createTempDirectory("bluetape-s3-output-")
        try {
            val output = S3OutputStream(
                operations = operations,
                bucket = "bucket",
                key = "large.bin",
                thresholdBytes = 4,
                temporaryDirectory = tempDirectory,
            )
            output.write("large-payload".encodeToByteArray())
            output.flush()
            operations.uploadedFiles.size shouldBeEqualTo 0

            output.close()

            operations.uploadedFileContents.single().decodeToString() shouldBeEqualTo "large-payload"
            Files.list(tempDirectory).use { stream -> stream.count() shouldBeEqualTo 0L }
        } finally {
            Files.deleteIfExists(tempDirectory)
        }
    }

    @Test
    fun `close is idempotent and upload failures still clean temporary files`() {
        val tempDirectory = Files.createTempDirectory("bluetape-s3-output-failure-")
        val operations = RecordingTransferOperations(failure = IllegalStateException("upload failed"))
        try {
            val output = S3OutputStream(
                operations = operations,
                bucket = "bucket",
                key = "large.bin",
                thresholdBytes = 1,
                temporaryDirectory = tempDirectory,
            )
            output.write(byteArrayOf(1, 2))

            assertFailsWith<IllegalStateException> { output.close() }
            output.close()

            Files.list(tempDirectory).use { stream -> stream.count() shouldBeEqualTo 0L }
            operations.uploadedFiles.size shouldBeEqualTo 1
        } finally {
            Files.deleteIfExists(tempDirectory)
        }
    }

    @Test
    fun `output stream uses blocking completion only inside IO boundary`() {
        val operations = RecordingTransferOperations()
        val output = S3OutputStream(operations, "bucket", "key", thresholdBytes = 8)

        runBlocking {
            output.write("ok".encodeToByteArray())
            output.complete()
        }

        operations.uploadedBytes.single().decodeToString() shouldBeEqualTo "ok"
        operations.ioCompletionObserved.shouldBeTrue()
    }

    @Test
    fun `discard drops buffered payload without uploading`() = runBlocking {
        val tempDirectory = Files.createTempDirectory("bluetape-s3-output-discard-")
        val operations = RecordingTransferOperations()
        try {
            val output = S3OutputStream(
                operations = operations,
                bucket = "bucket",
                key = "discarded.bin",
                thresholdBytes = 1,
                temporaryDirectory = tempDirectory,
            )
            output.write("discard me".encodeToByteArray())

            output.discard()
            output.discard()

            operations.uploadedBytes.size shouldBeEqualTo 0
            operations.uploadedFiles.size shouldBeEqualTo 0
            Files.list(tempDirectory).use { stream -> stream.count() shouldBeEqualTo 0L }
            assertFailsWith<IllegalStateException> { output.write(1) }
            Unit
        } finally {
            Files.deleteIfExists(tempDirectory)
        }
    }

    @Test
    fun `temporary file is cleaned when closing the spill output fails`() {
        val tempDirectory = Files.createTempDirectory("bluetape-s3-output-close-failure-")
        try {
            val output = S3OutputStream(
                operations = RecordingTransferOperations(),
                bucket = "bucket",
                key = "close-failure.bin",
                thresholdBytes = 1,
                temporaryDirectory = tempDirectory,
            )
            output.write("spill me".encodeToByteArray())

            val fileOutputField = S3OutputStream::class.java.getDeclaredField("fileOutput").apply {
                isAccessible = true
            }
            val originalFileOutput = fileOutputField.get(output) as OutputStream
            originalFileOutput.close()
            fileOutputField.set(output, FailingCloseOutputStream())

            assertFailsWith<IllegalStateException> { output.close() }

            Files.list(tempDirectory).use { stream -> stream.count() shouldBeEqualTo 0L }
        } finally {
            Files.deleteIfExists(tempDirectory)
        }
    }

    @Test
    fun `upload failure preserves sanitized cleanup failure and retries owned residue`() {
        val tempDirectory = Files.createTempDirectory("bluetape-s3-output-cleanup-")
        val primary = NonCopyableUploadException(Any())
        val operations = RecordingTransferOperations(failure = primary)
        try {
            val output = S3OutputStream(
                operations = operations,
                bucket = "bucket",
                key = "secret-key",
                thresholdBytes = 1,
                temporaryDirectory = tempDirectory,
            )
            output.write("spill me".encodeToByteArray())
            val temporary = Files.list(tempDirectory).use { it.findFirst().orElseThrow() }
            var deleteAttempts = 0
            output.temporaryFileDelete = { path ->
                deleteAttempts++
                throw java.nio.file.FileSystemException(path.toString(), null, "secret-delete-marker")
            }

            val thrown = assertFailsWith<NonCopyableUploadException> { output.close() }

            thrown.shouldBeSameInstanceAs(primary)
            deleteAttempts shouldBeEqualTo 1
            Files.exists(temporary).shouldBeTrue()
            val cleanup = thrown.suppressed.single() as S3TransferCleanupException
            cleanup.operation shouldBeEqualTo S3TransferCleanupOperation.TEMPORARY_FILE_DELETE
            cleanup.message?.contains(temporary.toString()).shouldBeFalse()
            cleanup.message?.contains("secret-delete-marker").shouldBeFalse()

            output.temporaryFileDelete = Files::deleteIfExists
            output.discardBlocking()
            Files.exists(temporary).shouldBeFalse()
        } finally {
            Files.list(tempDirectory).use { paths -> paths.forEach(Files::deleteIfExists) }
            Files.deleteIfExists(tempDirectory)
        }
    }

    @Test
    fun `spill setup failure preserves sanitized cleanup failure and retryable residue`() {
        val tempDirectory = Files.createTempDirectory("bluetape-s3-output-setup-cleanup-")
        val primary = NonCopyableUploadException(Any())
        try {
            val output = S3OutputStream(
                operations = RecordingTransferOperations(),
                bucket = "bucket",
                key = "secret-key",
                thresholdBytes = 1,
                temporaryDirectory = tempDirectory,
            )
            output.temporaryFileOpen = { throw primary }
            output.temporaryFileDelete = { path ->
                throw java.nio.file.FileSystemException(path.toString(), null, "secret-delete-marker")
            }

            val thrown = assertFailsWith<NonCopyableUploadException> {
                output.write("spill me".encodeToByteArray())
            }

            thrown.shouldBeSameInstanceAs(primary)
            val temporary = Files.list(tempDirectory).use { it.findFirst().orElseThrow() }
            Files.exists(temporary).shouldBeTrue()
            val cleanup = thrown.suppressed.single() as S3TransferCleanupException
            cleanup.operation shouldBeEqualTo S3TransferCleanupOperation.OUTPUT_STREAM_DISCARD
            cleanup.attemptFailureTypes shouldBeEqualTo listOf(java.nio.file.FileSystemException::class.qualifiedName)
            cleanup.message?.contains(temporary.toString()).shouldBeFalse()
            cleanup.message?.contains("secret-delete-marker").shouldBeFalse()

            output.temporaryFileDelete = Files::deleteIfExists
            output.discardBlocking()
            Files.exists(temporary).shouldBeFalse()
        } finally {
            Files.list(tempDirectory).use { paths -> paths.forEach(Files::deleteIfExists) }
            Files.deleteIfExists(tempDirectory)
        }
    }

    @Test
    fun `cleanup only failure is retried by the next close without repeating upload`() {
        val tempDirectory = Files.createTempDirectory("bluetape-s3-output-close-retry-")
        val operations = RecordingTransferOperations()
        try {
            val output = S3OutputStream(
                operations = operations,
                bucket = "bucket",
                key = "secret-key",
                thresholdBytes = 1,
                temporaryDirectory = tempDirectory,
            )
            output.write("spill me".encodeToByteArray())
            val temporary = Files.list(tempDirectory).use { it.findFirst().orElseThrow() }
            var deleteAttempts = 0
            output.temporaryFileDelete = { path ->
                deleteAttempts++
                if (deleteAttempts == 1) {
                    throw java.nio.file.FileSystemException(path.toString(), null, "secret-delete-marker")
                }
                Files.deleteIfExists(path)
            }

            val cleanup = assertFailsWith<S3TransferCleanupException> { output.close() }

            cleanup.operation shouldBeEqualTo S3TransferCleanupOperation.TEMPORARY_FILE_DELETE
            cleanup.message?.contains(temporary.toString()).shouldBeFalse()
            Files.exists(temporary).shouldBeTrue()
            operations.uploadedFiles.size shouldBeEqualTo 1

            output.close()

            deleteAttempts shouldBeEqualTo 2
            Files.exists(temporary).shouldBeFalse()
            operations.uploadedFiles.size shouldBeEqualTo 1
        } finally {
            Files.list(tempDirectory).use { paths -> paths.forEach(Files::deleteIfExists) }
            Files.deleteIfExists(tempDirectory)
        }
    }
}

private class NonCopyableUploadException(
    @Suppress("unused") private val identityGuard: Any,
) : RuntimeException("upload failed")

private class FailingCloseOutputStream : OutputStream() {

    override fun write(b: Int) = Unit

    override fun close() {
        throw IllegalStateException("close failed")
    }
}

private class RecordingTransferOperations(
    private val failure: Throwable? = null,
) : S3TransferOperations {
    val uploadedBytes = mutableListOf<ByteArray>()
    val uploadedFiles = mutableListOf<Path>()
    val uploadedFileContents = mutableListOf<ByteArray>()
    val uploadedContentTypes = mutableListOf<String?>()
    val uploadedMetadata = mutableListOf<Map<String, String>>()
    var ioCompletionObserved: Boolean = false

    override suspend fun upload(
        bucket: String,
        key: String,
        bytes: ByteArray,
        configure: UploadRequest.Builder.() -> Unit,
    ): CompletedUpload {
        val request = UploadRequest.builder()
            .requestBody(AsyncRequestBody.fromBytes(bytes))
            .apply(configure)
            .build()
        uploadedBytes += bytes.copyOf()
        uploadedContentTypes += request.putObjectRequest().contentType()
        uploadedMetadata += request.putObjectRequest().metadata().orEmpty()
        ioCompletionObserved = Thread.currentThread().name.contains("DefaultDispatcher") ||
            Thread.currentThread().name.contains("IO")
        failure?.let { throw it }
        return mockk(relaxed = true)
    }

    override suspend fun uploadFile(
        bucket: String,
        key: String,
        source: Path,
        configure: UploadFileRequest.Builder.() -> Unit,
    ): CompletedFileUpload {
        val request = UploadFileRequest.builder()
            .source(source)
            .apply(configure)
            .build()
        uploadedFiles.add(source)
        uploadedFileContents.add(Files.readAllBytes(source))
        uploadedContentTypes.add(request.putObjectRequest().contentType())
        uploadedMetadata.add(request.putObjectRequest().metadata().orEmpty())
        failure?.let { throw it }
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
