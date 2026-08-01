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
 * AWS SDK `S3TransferManager`를 사용하는 코루틴 우선 S3 전송 작업입니다.
 *
 * 대용량 파일, 멀티파트 전송, 진행률 리스너, TransferManager 전용 요청 사용자 정의에
 * 이 계약을 사용하세요. 기본 객체 작업은 [S3Operations]에서 계속 제공합니다.
 */
interface S3TransferOperations {

    /**
     * `S3TransferManager`로 [bytes]를 [bucket]/[key]에 업로드합니다.
     */
    suspend fun upload(
        bucket: String,
        key: String,
        bytes: ByteArray,
        configure: UploadRequest.Builder.() -> Unit = {},
    ): CompletedUpload

    /**
     * `S3TransferManager`로 [source]를 [bucket]/[key]에 업로드합니다.
     */
    suspend fun uploadFile(
        bucket: String,
        key: String,
        source: Path,
        configure: UploadFileRequest.Builder.() -> Unit = {},
    ): CompletedFileUpload

    /**
     * `S3TransferManager`로 [bucket]/[key]를 바이트로 다운로드합니다.
     */
    suspend fun downloadBytes(
        bucket: String,
        key: String,
        configure: DownloadRequest.UntypedBuilder.() -> Unit = {},
    ): CompletedDownload<ResponseBytes<GetObjectResponse>>

    /**
     * `S3TransferManager`로 [bucket]/[key]를 [destination]에 다운로드합니다.
     */
    suspend fun downloadFile(
        bucket: String,
        key: String,
        destination: Path,
        configure: DownloadFileRequest.Builder.() -> Unit = {},
    ): CompletedFileDownload
}
