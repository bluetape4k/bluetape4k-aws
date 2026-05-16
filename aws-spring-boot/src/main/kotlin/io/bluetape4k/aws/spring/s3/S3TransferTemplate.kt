package io.bluetape4k.aws.spring.s3

import io.bluetape4k.aws.s3.transfer.downloadAsByteArray
import io.bluetape4k.aws.s3.transfer.downloadFile
import io.bluetape4k.aws.s3.transfer.uploadByteArray
import io.bluetape4k.aws.s3.transfer.uploadFile
import software.amazon.awssdk.core.ResponseBytes
import software.amazon.awssdk.services.s3.model.GetObjectResponse
import software.amazon.awssdk.transfer.s3.S3TransferManager
import software.amazon.awssdk.transfer.s3.model.CompletedDownload
import software.amazon.awssdk.transfer.s3.model.CompletedFileDownload
import software.amazon.awssdk.transfer.s3.model.CompletedFileUpload
import software.amazon.awssdk.transfer.s3.model.CompletedUpload
import software.amazon.awssdk.transfer.s3.model.DownloadFileRequest
import software.amazon.awssdk.transfer.s3.model.DownloadRequest
import software.amazon.awssdk.transfer.s3.model.UploadFileRequest
import software.amazon.awssdk.transfer.s3.model.UploadRequest
import java.nio.file.Path

/**
 * Default [S3TransferOperations] implementation that delegates to the `aws`
 * module's coroutine extensions for `S3TransferManager`.
 */
class S3TransferTemplate(
    private val transferManager: S3TransferManager,
): S3TransferOperations {

    override suspend fun upload(
        bucket: String,
        key: String,
        bytes: ByteArray,
        configure: UploadRequest.Builder.() -> Unit,
    ): CompletedUpload =
        transferManager.uploadByteArray(bucket, key, bytes, configure)

    override suspend fun uploadFile(
        bucket: String,
        key: String,
        source: Path,
        configure: UploadFileRequest.Builder.() -> Unit,
    ): CompletedFileUpload =
        transferManager.uploadFile(bucket, key, source, configure)

    override suspend fun downloadBytes(
        bucket: String,
        key: String,
        configure: DownloadRequest.UntypedBuilder.() -> Unit,
    ): CompletedDownload<ResponseBytes<GetObjectResponse>> =
        transferManager.downloadAsByteArray(bucket, key, configure)

    override suspend fun downloadFile(
        bucket: String,
        key: String,
        destination: Path,
        configure: DownloadFileRequest.Builder.() -> Unit,
    ): CompletedFileDownload =
        transferManager.downloadFile(bucket, key, destination, configure)
}
