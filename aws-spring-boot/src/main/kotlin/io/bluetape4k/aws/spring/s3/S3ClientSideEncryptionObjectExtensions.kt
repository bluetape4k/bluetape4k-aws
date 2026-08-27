package io.bluetape4k.aws.spring.s3

import software.amazon.awssdk.services.s3.model.PutObjectResponse

/** converter로 값을 직렬화한 뒤 S3 provider 봉투로 암호화해 업로드합니다. */
suspend fun <T : Any> S3ClientSideEncryptionOperations.uploadEncryptedObject(
    bucket: String,
    key: String,
    value: T,
    converter: S3ObjectConverter<T>,
    contentType: String? = null,
    metadata: Map<String, String> = emptyMap(),
    encryptionContext: Map<String, String> = emptyMap(),
): PutObjectResponse {
    val serialized = converter.write(value)
    return try {
        uploadEncrypted(
            bucket = bucket,
            key = key,
            bytes = serialized,
            contentType = contentType ?: converter.contentType,
            metadata = metadata,
            encryptionContext = encryptionContext,
        )
    } finally {
        serialized.fill(0)
    }
}

/** S3 provider 봉투를 복호화한 뒤 converter로 typed 값을 복원합니다. */
suspend fun <T : Any> S3ClientSideEncryptionOperations.downloadEncryptedObject(
    bucket: String,
    key: String,
    targetType: Class<T>,
    converter: S3ObjectConverter<T>,
    encryptionContext: Map<String, String> = emptyMap(),
): T {
    val plaintext = downloadEncryptedBytes(bucket, key, encryptionContext)
    return try {
        converter.read(plaintext, targetType)
    } finally {
        plaintext.fill(0)
    }
}
