package io.bluetape4k.aws.spring.s3

/** S3 HEAD 응답에서 Extended Client가 사용하는 제한된 metadata입니다. */
data class S3HeadMetadata(
    val sizeBytes: Long,
    val etag: String?,
    val contentType: String?,
    val userMetadata: Map<String, String> = emptyMap(),
) {
    init {
        require(sizeBytes >= 0) { "sizeBytes must not be negative." }
    }
}

sealed interface S3PutIfAbsentResult {
    data object Created : S3PutIfAbsentResult

    data class AlreadyExists(val metadata: S3HeadMetadata) : S3PutIfAbsentResult
}

/** ACK marker의 conditional metadata write와 HEAD capability입니다. */
interface S3ObjectMetadataOperations : S3Operations {
    suspend fun headObjectWithMetadata(bucket: String, key: String): S3HeadMetadata

    suspend fun putObjectIfAbsentWithMetadata(
        bucket: String,
        key: String,
        bytes: ByteArray,
        contentType: String,
        metadata: Map<String, String>,
    ): S3PutIfAbsentResult
}

/** plaintext payload를 max+1 probe로 읽는 bounded capability입니다. */
interface S3BoundedObjectReadOperations : S3Operations {
    suspend fun downloadBytesBounded(bucket: String, key: String, maxBytes: Int): ByteArray

    companion object {
        const val MAX_BYTES: Int = 67_108_864
    }
}

/** encrypted payload를 ciphertext 상한으로 읽는 bounded capability입니다. */
interface S3BoundedEncryptedReadOperations : S3ClientSideEncryptionOperations {
    suspend fun downloadEncryptedBytesBounded(
        bucket: String,
        key: String,
        encryptionContext: Map<String, String>,
        maxCiphertextBytes: Int,
    ): ByteArray

    companion object {
        const val MAX_CIPHERTEXT_BYTES: Int = 67_108_880
    }
}

/** S3 client-side encryption delegate가 제공하는 canonical key identity입니다. */
interface S3ClientSideEncryptionIdentity {
    val canonicalKeyIdentity: String
    val keyFingerprint: String
}
