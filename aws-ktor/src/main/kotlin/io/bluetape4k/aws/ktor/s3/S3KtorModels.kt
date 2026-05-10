package io.bluetape4k.aws.ktor.s3

import io.ktor.http.Headers
import io.ktor.http.Url
import io.ktor.utils.io.ByteReadChannel
import java.time.Instant

/**
 * S3 객체 위치를 표현합니다.
 */
data class S3KtorObjectRef(
    val bucket: String,
    val key: String,
)

/**
 * S3 PutObject 요청입니다.
 */
data class S3KtorPutObjectRequest(
    val bucket: String,
    val key: String,
    val contentType: String? = null,
    val metadata: Map<String, String> = emptyMap(),
    val headers: Map<String, String> = emptyMap(),
)

/**
 * S3 PutObject 응답입니다.
 */
data class S3KtorPutObjectResponse(
    val eTag: String?,
    val versionId: String?,
    val headers: Headers,
)

/**
 * S3 GetObject 응답 body와 metadata입니다.
 */
data class S3KtorGetObjectResponse(
    val bytes: ByteArray,
    val eTag: String?,
    val contentType: String?,
    val contentLength: Long?,
    val metadata: Map<String, String>,
    val headers: Headers,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as S3KtorGetObjectResponse
        return bytes.contentEquals(other.bytes) &&
                eTag == other.eTag &&
                contentType == other.contentType &&
                contentLength == other.contentLength &&
                metadata == other.metadata &&
                headers == other.headers
    }

    override fun hashCode(): Int {
        var result = bytes.contentHashCode()
        result = 31 * result + (eTag?.hashCode() ?: 0)
        result = 31 * result + (contentType?.hashCode() ?: 0)
        result = 31 * result + (contentLength?.hashCode() ?: 0)
        result = 31 * result + metadata.hashCode()
        result = 31 * result + headers.hashCode()
        return result
    }
}

/**
 * S3 GetObject streaming 응답입니다.
 *
 * [body]는 Ktor response channel이므로 caller가 소비를 완료해야 합니다.
 */
data class S3KtorStreamingObjectResponse(
    val body: ByteReadChannel,
    val eTag: String?,
    val contentType: String?,
    val contentLength: Long?,
    val metadata: Map<String, String>,
    val headers: Headers,
)

/**
 * S3 DeleteObject 응답입니다.
 */
data class S3KtorDeleteObjectResponse(
    val deleteMarker: Boolean?,
    val versionId: String?,
    val headers: Headers,
)

/**
 * S3 ListObjectsV2 요청입니다.
 */
data class S3KtorListObjectsRequest(
    val bucket: String,
    val prefix: String? = null,
    val delimiter: String? = null,
    val continuationToken: String? = null,
    val startAfter: String? = null,
    val maxKeys: Int? = null,
    val fetchOwner: Boolean? = null,
)

/**
 * S3 객체 목록 항목입니다.
 */
data class S3KtorObjectSummary(
    val key: String,
    val eTag: String?,
    val size: Long?,
    val lastModified: Instant?,
    val storageClass: String?,
)

/**
 * S3 ListObjectsV2 응답입니다.
 */
data class S3KtorListObjectsResponse(
    val bucket: String?,
    val prefix: String?,
    val delimiter: String?,
    val maxKeys: Int?,
    val keyCount: Int?,
    val isTruncated: Boolean,
    val nextContinuationToken: String?,
    val contents: List<S3KtorObjectSummary>,
    val commonPrefixes: List<String>,
)

/**
 * Multipart upload 시작 결과입니다.
 */
data class S3KtorMultipartUpload(
    val bucket: String,
    val key: String,
    val uploadId: String,
)

/**
 * Multipart upload part 결과입니다.
 */
data class S3KtorCompletedPart(
    val partNumber: Int,
    val eTag: String,
)

/**
 * CompleteMultipartUpload 응답입니다.
 */
data class S3KtorCompleteMultipartUploadResponse(
    val bucket: String?,
    val key: String?,
    val location: String?,
    val eTag: String?,
)

/**
 * Presigned S3 요청 URL입니다.
 */
data class S3KtorPresignedRequest(
    val method: String,
    val url: Url,
)
