package io.bluetape4k.aws.spring.s3

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * S3 TransferManager에 연결되는 blocking [OutputStream]입니다.
 *
 * 작은 payload는 메모리에 보관하고 [thresholdBytes]를 넘으면 임시 파일로 전환합니다.
 * [close]는 업로드를 완료할 때까지 blocking하므로 애플리케이션 코루틴에서는
 * [complete]를 직접 호출하는 것이 좋습니다. close 경계의 blocking 작업은 항상
 * `Dispatchers.IO`에서 실행됩니다.
 */
@Suppress("TooGenericExceptionCaught")
class S3OutputStream(
    private val operations: S3TransferOperations,
    private val bucket: String,
    private val key: String,
    val thresholdBytes: Long = DEFAULT_THRESHOLD_BYTES,
    val partSizeBytes: Long = DEFAULT_PART_SIZE_BYTES,
    private val contentType: String? = null,
    private val metadata: Map<String, String> = emptyMap(),
    private val temporaryDirectory: Path = defaultTemporaryDirectory(),
) : OutputStream() {

    private var memoryBuffer = ByteArrayOutputStream(minOf(thresholdBytes, INITIAL_BUFFER_BYTES.toLong()).toInt())
    private var fileOutput: OutputStream? = null
    private var temporaryFile: Path? = null
    private var completionStarted = false
    private var completionFailure: Throwable? = null

    init {
        require(bucket.isNotBlank()) { "bucket must not be blank." }
        require(key.isNotBlank()) { "key must not be blank." }
        require(thresholdBytes > 0) { "thresholdBytes must be greater than 0." }
        require(partSizeBytes > 0) { "partSizeBytes must be greater than 0." }
    }

    override fun write(b: Int) {
        write(byteArrayOf(b.toByte()), 0, 1)
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        require(off >= 0 && len >= 0 && off <= b.size - len) { "Invalid byte range." }
        if (len == 0) return
        synchronized(this) {
            check(!completionStarted) { "S3OutputStream is already closed." }
            if (fileOutput == null && memoryBuffer.size().toLong() + len <= thresholdBytes) {
                memoryBuffer.write(b, off, len)
            } else {
                ensureFileOutput().write(b, off, len)
            }
        }
    }

    override fun flush() {
        synchronized(this) {
            check(!completionStarted) { "S3OutputStream is already closed." }
            fileOutput?.flush()
        }
    }

    /** 업로드를 현재 coroutine context에서 완료합니다. */
    suspend fun complete() {
        withContext(Dispatchers.IO) {
            completeOnIo()
        }
    }

    /** 업로드하지 않고 buffered payload와 임시 파일을 폐기합니다. */
    internal suspend fun discard() {
        withContext(Dispatchers.IO) {
            discardBlocking()
        }
    }

    /** 업로드를 완료하고 임시 파일을 정리합니다. 두 번 호출해도 안전합니다. */
    override fun close() {
        if (isComplete()) return
        try {
            runBlocking { complete() }
        } catch (error: Throwable) {
            synchronized(this) {
                completionFailure = error
            }
            throw error
        }
    }

    private suspend fun completeOnIo() {
        val bytes: ByteArray?
        val file: Path?
        val output: OutputStream?
        synchronized(this) {
            if (completionStarted) return
            completionStarted = true
            output = fileOutput
            file = temporaryFile
            bytes = if (output == null) memoryBuffer.toByteArray() else null
            fileOutput = null
        }

        var failure: Throwable? = null
        var cleanupFailure: Throwable? = null
        try {
            output?.close()
            if (bytes != null) {
                operations.upload(bucket, key, bytes) {
                    putObjectRequest(buildPutObjectRequest())
                }
            } else {
                operations.uploadFile(bucket, key, requireNotNull(file)) {
                    putObjectRequest(buildPutObjectRequest())
                }
            }
        } catch (error: Throwable) {
            failure = error
            synchronized(this) {
                completionFailure = error
            }
            throw error
        } finally {
            bytes?.fill(0)
            cleanupFailure = runCatching { file?.let { Files.deleteIfExists(it) } }.exceptionOrNull()
            synchronized(this) {
                if (cleanupFailure != null) {
                    val failureToReport = failure
                    if (failureToReport == null) {
                        completionFailure = cleanupFailure
                    } else {
                        failureToReport.addSuppressed(cleanupFailure)
                    }
                }
                memoryBuffer.reset()
                temporaryFile = null
            }
        }
        if (cleanupFailure != null && failure == null) throw cleanupFailure as Throwable
    }

    internal fun discardBlocking() {
        val file: Path?
        val output: OutputStream?
        synchronized(this) {
            if (completionStarted) return
            completionStarted = true
            output = fileOutput
            file = temporaryFile
            fileOutput = null
            memoryBuffer.reset()
            temporaryFile = null
        }
        runCatching { output?.close() }
        file?.let { Files.deleteIfExists(it) }
    }

    private fun ensureFileOutput(): OutputStream {
        fileOutput?.let { return it }
        Files.createDirectories(temporaryDirectory)
        val file = Files.createTempFile(temporaryDirectory, "bluetape-s3-", ".part")
        var output: OutputStream? = null
        var buffered: ByteArray? = null
        try {
            output = Files.newOutputStream(file)
            buffered = memoryBuffer.toByteArray()
            output.write(buffered)
            memoryBuffer.reset()
            temporaryFile = file
            fileOutput = output
            return output
        } catch (error: Throwable) {
            runCatching { output?.close() }.onFailure(error::addSuppressed)
            runCatching { Files.deleteIfExists(file) }.onFailure(error::addSuppressed)
            throw error
        } finally {
            buffered?.fill(0)
        }
    }

    private fun buildPutObjectRequest(): PutObjectRequest =
        PutObjectRequest.builder()
            .bucket(bucket)
            .key(key)
            .apply {
                contentType?.let(::contentType)
                if (metadata.isNotEmpty()) metadata(metadata)
            }
            .build()

    private fun isComplete(): Boolean = synchronized(this) { completionStarted && completionFailure == null }

    companion object {
        const val DEFAULT_THRESHOLD_BYTES: Long = 8 * 1024 * 1024
        const val DEFAULT_PART_SIZE_BYTES: Long = 8 * 1024 * 1024
        private const val INITIAL_BUFFER_BYTES: Int = 8 * 1024

        private fun defaultTemporaryDirectory(): Path =
            Paths.get(System.getProperty("java.io.tmpdir"))
    }
}
