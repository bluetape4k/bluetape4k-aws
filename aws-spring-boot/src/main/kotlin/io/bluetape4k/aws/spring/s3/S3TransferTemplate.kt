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
 * `aws` 모듈의 `S3TransferManager` 코루틴 확장에 위임하는 기본
 * [S3TransferOperations] 구현입니다.
 */
class S3TransferTemplate(
    private val transferManager: S3TransferManager,
    private val properties: S3Properties = S3Properties(),
    private val contentTypeResolver: S3ObjectContentTypeResolver = DefaultS3ObjectContentTypeResolver(),
): S3TransferOperations, S3OutputStreamProvider, S3ObjectOperations {

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

    override fun outputStream(
        bucket: String,
        key: String,
        contentType: String?,
        metadata: Map<String, String>,
    ): S3OutputStream =
        S3OutputStream(
            operations = this,
            bucket = bucket,
            key = key,
            thresholdBytes = properties.transfer.outputStreamThresholdBytes,
            partSizeBytes = properties.transfer.outputStreamPartSizeBytes,
            contentType = contentTypeResolver.resolve(key, contentType, metadata),
            metadata = metadata,
        )

    override suspend fun <T : Any> uploadObject(
        bucket: String,
        key: String,
        value: T,
        converter: S3ObjectConverter<T>,
        contentType: String?,
        metadata: Map<String, String>,
    ): CompletedUpload =
        upload(bucket, key, converter.write(value)) {
            putObjectRequest(
                bucket = bucket,
                key = key,
                contentType = contentTypeResolver.resolve(key, contentType ?: converter.contentType, metadata),
                metadata = metadata,
            )
        }

    override suspend fun <T : Any> downloadObject(
        bucket: String,
        key: String,
        targetType: Class<T>,
        converter: S3ObjectConverter<T>,
    ): T =
        converter.read(downloadBytes(bucket, key).result().asByteArray(), targetType)

    private fun UploadRequest.Builder.putObjectRequest(
        bucket: String,
        key: String,
        contentType: String,
        metadata: Map<String, String>,
    ) {
        putObjectRequest { builder ->
            builder.bucket(bucket)
            builder.key(key)
            builder.contentType(contentType)
            if (metadata.isNotEmpty()) builder.metadata(metadata)
        }
    }
}
