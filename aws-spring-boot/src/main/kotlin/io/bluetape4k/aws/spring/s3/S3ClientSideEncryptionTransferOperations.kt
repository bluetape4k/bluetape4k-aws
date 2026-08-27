package io.bluetape4k.aws.spring.s3

import io.bluetape4k.support.requireNotBlank
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.future.await
import software.amazon.awssdk.services.s3.S3AsyncClient
import software.amazon.awssdk.transfer.s3.model.DownloadFileRequest
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import javax.crypto.Cipher

/** provider 봉투를 TransferManager 기반 stream/file 작업으로 확장하는 계약입니다. */
interface S3ClientSideEncryptionTransferOperations {
    fun encryptedOutputStream(
        bucket: String,
        key: String,
        contentType: String? = null,
        metadata: Map<String, String> = emptyMap(),
        encryptionContext: Map<String, String> = emptyMap(),
    ): S3EncryptedOutputStream

    suspend fun downloadEncryptedFile(
        bucket: String,
        key: String,
        destination: Path,
        encryptionContext: Map<String, String> = emptyMap(),
    )
}

/** 평문을 provider envelope ciphertext로 변환해 [S3OutputStream]에 전달합니다. */
@Suppress("TooGenericExceptionCaught")
class S3EncryptedOutputStream internal constructor(
    private val delegate: S3OutputStream,
    private val cipher: Cipher,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : OutputStream() {

    override fun write(b: ByteArray, off: Int, len: Int) {
        require(off >= 0 && len >= 0 && off <= b.size - len) { "Invalid byte range." }
        if (len == 0) return
        synchronized(stateLock) {
            check(!terminalStarted) { "S3EncryptedOutputStream is already closed." }
            cipher.update(b, off, len)?.let(::writeCiphertext)
        }
    }

    override fun write(b: Int) {
        write(byteArrayOf(b.toByte()), 0, 1)
    }

    /** GCM final tag를 기록하고 delegate completion을 정확히 한 번 수행합니다. */
    suspend fun complete() {
        try {
            withContext(ioDispatcher) { completeOnIo() }
        } catch (cancelled: CancellationException) {
            val ownsCleanup = synchronized(stateLock) {
                if (completed || terminalStarted) {
                    false
                } else {
                    terminalStarted = true
                    completed = true
                    true
                }
            }
            if (ownsCleanup) cleanupDelegate()
            throw cancelled
        }
    }

    override fun close() {
        runBlocking { complete() }
    }

    private suspend fun completeOnIo() = completionMutex.withLock {
        synchronized(stateLock) {
            if (completed) return@withLock
            check(!terminalStarted) { "S3EncryptedOutputStream completion already started." }
            terminalStarted = true
        }
        try {
            val finalBytes = cipher.doFinal()
            try {
                if (finalBytes.isNotEmpty()) delegate.write(finalBytes)
            } finally {
                finalBytes.fill(0)
            }
            delegate.complete()
        } catch (cancelled: CancellationException) {
            cleanupDelegate()
            throw cancelled
        } catch (error: Throwable) {
            cleanupDelegate()
            throw error
        } finally {
            synchronized(stateLock) { completed = true }
        }
    }

    private fun writeCiphertext(ciphertext: ByteArray) {
        try {
            if (ciphertext.isNotEmpty()) delegate.write(ciphertext)
        } finally {
            ciphertext.fill(0)
        }
    }

    private suspend fun cleanupDelegate() {
        withContext(NonCancellable + ioDispatcher) {
            runCatching { delegate.close() }
        }
    }

    private val completionMutex = Mutex()
    private val stateLock = Any()
    private var terminalStarted: Boolean = false
    private var completed: Boolean = false

    companion object {
        /** provider metadata와 ciphertext delegate를 하나의 streaming 경계로 묶습니다. */
        internal fun create(
            template: S3ClientSideEncryptionProviderTemplate,
            outputStreamProvider: S3OutputStreamProvider,
            bucket: String,
            key: String,
            contentType: String?,
            metadata: Map<String, String>,
            encryptionContext: Map<String, String>,
            ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
        ): S3EncryptedOutputStream {
            val envelope = template.newStreamingEnvelope(encryptionContext)
            var delegate: S3OutputStream? = null
            return try {
                val created = outputStreamProvider.outputStream(
                    bucket,
                    key,
                    contentType,
                    ProviderEnvelope.mergeMetadata(metadata, envelope.metadata),
                )
                delegate = created
                S3EncryptedOutputStream(
                    created,
                    template.newPayloadCipher(envelope),
                    ioDispatcher,
                )
            } catch (cancelled: CancellationException) {
                runCatching { delegate?.close() }
                throw cancelled
            } catch (error: Throwable) {
                runCatching { delegate?.close() }
                throw error
            } finally {
                envelope.dataKey.fill(0)
                envelope.nonce.fill(0)
                envelope.aad.fill(0)
            }
        }
    }
}

/** provider template과 TransferManager를 연결하는 file/stream 구현입니다. */
class S3ClientSideEncryptionTransferTemplate(
    private val s3AsyncClient: S3AsyncClient,
    private val providerTemplate: S3ClientSideEncryptionProviderTemplate,
    private val transferOperations: S3TransferOperations,
    private val outputStreamProvider: S3OutputStreamProvider,
    internal val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : S3ClientSideEncryptionTransferOperations {

    override fun encryptedOutputStream(
        bucket: String,
        key: String,
        contentType: String?,
        metadata: Map<String, String>,
        encryptionContext: Map<String, String>,
    ): S3EncryptedOutputStream =
        S3EncryptedOutputStream.create(
            template = providerTemplate,
            outputStreamProvider = outputStreamProvider,
            bucket = bucket,
            key = key,
            contentType = contentType,
            metadata = metadata,
            encryptionContext = encryptionContext,
            ioDispatcher = ioDispatcher,
        )

    override suspend fun downloadEncryptedFile(
        bucket: String,
        key: String,
        destination: Path,
        encryptionContext: Map<String, String>,
    ) {
        providerTemplate.requireOpen()
        bucket.requireNotBlank("bucket")
        key.requireNotBlank("key")

        val head = s3AsyncClient.headObject { builder ->
            builder.bucket(bucket)
            builder.key(key)
        }.await()
        val remoteSize = requireNotNull(head.contentLength()) {
            "Encrypted S3 object content length is unavailable: s3://$bucket/$key"
        }
        require(remoteSize >= 0) { "Encrypted S3 object content length must not be negative." }
        val remoteETag = requireNotNull(head.eTag()) {
            "Encrypted S3 object ETag is unavailable: s3://$bucket/$key"
        }
        require(remoteSize <= S3BoundedEncryptedReadOperations.MAX_CIPHERTEXT_BYTES) {
            "Encrypted S3 object exceeds max ciphertext size: $remoteSize"
        }

        var temporary: Path? = null
        var plaintext: ByteArray? = null
        try {
            withContext(NonCancellable + ioDispatcher) {
                temporary = Files.createTempFile("bluetape-s3-cse-", ".ciphertext")
            }
            val completed = transferOperations.downloadFile(bucket, key, requireNotNull(temporary)) {
                getObjectRequest { builder ->
                    builder.bucket(bucket)
                    builder.key(key)
                    builder.ifMatch(remoteETag)
                }
            }
            withContext(ioDispatcher) {
                val temporaryPath = requireNotNull(temporary)
                val size = Files.size(temporaryPath)
                require(size <= S3BoundedEncryptedReadOperations.MAX_CIPHERTEXT_BYTES) {
                    "Encrypted S3 object exceeds max ciphertext size: $size"
                }
                val ciphertext = Files.readAllBytes(temporaryPath)
                try {
                    plaintext = providerTemplate.decryptProviderPayload(
                        ciphertext,
                        completed.response().metadata(),
                        encryptionContext,
                    )
                } finally {
                    ciphertext.fill(0)
                }
            }
            withContext(ioDispatcher) {
                Files.write(destination, requireNotNull(plaintext))
            }
        } finally {
            plaintext?.fill(0)
            temporary?.let { path ->
                withContext(NonCancellable + ioDispatcher) {
                    runCatching { Files.deleteIfExists(path) }
                }
            }
        }
    }
}
