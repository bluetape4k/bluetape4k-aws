package io.bluetape4k.aws.spring.s3

import software.amazon.awssdk.transfer.s3.model.CompletedUpload

/** TransferManager 기반 typed S3 객체 작업입니다. */
interface S3ObjectOperations {

    /** converter로 [value]를 직렬화해 S3 객체로 업로드합니다. */
    suspend fun <T : Any> uploadObject(
        bucket: String,
        key: String,
        value: T,
        converter: S3ObjectConverter<T>,
        contentType: String? = null,
        metadata: Map<String, String> = emptyMap(),
    ): CompletedUpload

    /** S3 객체를 다운로드해 [converter]로 [targetType] typed 객체로 복원합니다. */
    suspend fun <T : Any> downloadObject(
        bucket: String,
        key: String,
        targetType: Class<T>,
        converter: S3ObjectConverter<T>,
    ): T
}
