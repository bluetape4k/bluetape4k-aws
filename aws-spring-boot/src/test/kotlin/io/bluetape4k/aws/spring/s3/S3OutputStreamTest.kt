package io.bluetape4k.aws.spring.s3

import io.bluetape4k.assertions.shouldBeEqualTo
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
