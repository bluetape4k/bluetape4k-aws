package io.bluetape4k.aws.spring.s3

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
import java.nio.file.Path

/**
 * Coroutine-first S3 transfer operations backed by the AWS SDK `S3TransferManager`.
 *
 * Use this contract for large files, multipart transfers, progress listeners, and
 * TransferManager-specific request customization. Basic object operations remain
 * available through [S3Operations].
 */
interface S3TransferOperations {

    /**
     * Uploads [bytes] to [bucket]/[key] through `S3TransferManager`.
     */
    suspend fun upload(
        bucket: String,
        key: String,
        bytes: ByteArray,
        configure: UploadRequest.Builder.() -> Unit = {},
    ): CompletedUpload

    /**
     * Uploads [source] to [bucket]/[key] through `S3TransferManager`.
     */
    suspend fun uploadFile(
        bucket: String,
        key: String,
        source: Path,
        configure: UploadFileRequest.Builder.() -> Unit = {},
    ): CompletedFileUpload

    /**
     * Downloads [bucket]/[key] as bytes through `S3TransferManager`.
     */
    suspend fun downloadBytes(
        bucket: String,
        key: String,
        configure: DownloadRequest.UntypedBuilder.() -> Unit = {},
    ): CompletedDownload<ResponseBytes<GetObjectResponse>>

    /**
     * Downloads [bucket]/[key] into [destination] through `S3TransferManager`.
     */
    suspend fun downloadFile(
        bucket: String,
        key: String,
        destination: Path,
        configure: DownloadFileRequest.Builder.() -> Unit = {},
    ): CompletedFileDownload
}
