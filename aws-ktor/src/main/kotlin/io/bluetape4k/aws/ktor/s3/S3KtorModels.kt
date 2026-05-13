package io.bluetape4k.aws.ktor.s3

import io.ktor.http.Headers
import io.ktor.http.Url
import io.ktor.utils.io.ByteReadChannel
import java.time.Instant

/**
 * S3 객체 위치를 표현합니다.
 *
 * ## 동작/계약
 *
 * [bucket]과 [key]만 보존하는 값 객체입니다. 실제 요청 전 검증은 [S3KtorClient]의 작업
 * 메서드에서 수행합니다.
 *
 * ```kotlin
 * val ref = S3KtorObjectRef(bucket = "demo-bucket", key = "docs/hello.txt")
 * ```
 */
data class S3KtorObjectRef(
    val bucket: String,
    val key: String,
)

/**
 * S3 PutObject 요청입니다.
 *
 * ## 동작/계약
 *
 * [metadata]의 key는 `x-amz-meta-` 접두사 없이 전달합니다. [headers]는 S3 요청에 그대로
 * 추가되므로 `Content-MD5`처럼 명시적으로 제어해야 하는 헤더에 사용합니다.
 *
 * ```kotlin
 * val request = S3KtorPutObjectRequest(
 *     bucket = "demo-bucket",
 *     key = "docs/hello.txt",
 *     contentType = "text/plain; charset=utf-8",
 *     metadata = mapOf("source" to "ktor"),
 * )
 * ```
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
 *
 * ## 동작/계약
 *
 * S3가 반환한 ETag, version id, 원본 응답 headers를 보존합니다. Versioning이 꺼진 bucket에서는
 * [versionId]가 null일 수 있습니다.
 *
 * ```kotlin
 * val response = s3.putObject("demo-bucket", "docs/hello.txt", "hello".encodeToByteArray())
 * check(response.eTag != null)
 * ```
 */
data class S3KtorPutObjectResponse(
    val eTag: String?,
    val versionId: String?,
    val headers: Headers,
)

/**
 * S3 GetObject 응답 body와 metadata입니다.
 *
 * ## 동작/계약
 *
 * [bytes]는 응답 body 전체를 메모리에 적재한 값입니다. 큰 객체를 다룰 때는
 * [S3KtorClient.getObjectStream]을 사용합니다. [metadata] key는 `x-amz-meta-` 접두사를 제거한
 * 형태로 제공됩니다.
 *
 * ```kotlin
 * val response = s3.getObject("demo-bucket", "docs/hello.txt")
 * check(response.bytes.decodeToString() == "hello")
 * ```
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
 * ## 동작/계약
 *
 * [body]는 Ktor response channel이므로 caller가 소비를 완료해야 합니다.
 *
 * ```kotlin
 * val response = s3.getObjectStream("demo-bucket", "large.bin")
 * val channel = response.body
 * ```
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
 *
 * ## 동작/계약
 *
 * 삭제 marker와 version id는 bucket versioning 및 삭제 방식에 따라 null일 수 있습니다.
 *
 * ```kotlin
 * val response = s3.deleteObject("demo-bucket", "docs/hello.txt")
 * check(response.headers.names().isNotEmpty())
 * ```
 */
data class S3KtorDeleteObjectResponse(
    val deleteMarker: Boolean?,
    val versionId: String?,
    val headers: Headers,
)

/**
 * S3 ListObjectsV2 요청입니다.
 *
 * ## 동작/계약
 *
 * null인 필드는 요청 query parameter에 포함하지 않습니다. [maxKeys]는 S3 API 제한에 맞게
 * 1..1000 범위로 지정하는 것을 권장합니다.
 *
 * ```kotlin
 * val request = S3KtorListObjectsRequest(
 *     bucket = "demo-bucket",
 *     prefix = "logs/",
 *     maxKeys = 100,
 * )
 * ```
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
 *
 * ## 동작/계약
 *
 * ListObjectsV2의 `Contents` 요소를 단순 값 객체로 변환합니다. S3 응답에 없는 선택 필드는
 * null로 유지됩니다.
 *
 * ```kotlin
 * val firstKey = page.contents.firstOrNull()?.key
 * ```
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
 *
 * ## 동작/계약
 *
 * S3 XML 응답의 paging 필드와 객체 목록을 보존합니다. [isTruncated]가 `true`이면
 * [nextContinuationToken]으로 다음 페이지를 조회합니다.
 *
 * ```kotlin
 * val page = s3.listObjectsV2(S3KtorListObjectsRequest(bucket = "demo-bucket"))
 * val next = page.nextContinuationToken
 * ```
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
 *
 * ## 동작/계약
 *
 * 이후 part 업로드와 완료/중단 요청에 필요한 [uploadId]를 포함합니다.
 *
 * ```kotlin
 * val upload = s3.createMultipartUpload("demo-bucket", "large.bin")
 * ```
 */
data class S3KtorMultipartUpload(
    val bucket: String,
    val key: String,
    val uploadId: String,
)

/**
 * Multipart upload part 결과입니다.
 *
 * ## 동작/계약
 *
 * CompleteMultipartUpload XML 생성을 위해 part number와 ETag를 보존합니다. 완료 요청에서는
 * part number 순서로 정렬됩니다.
 *
 * ```kotlin
 * val part = s3.uploadPart("demo-bucket", "large.bin", uploadId, 1, bytes)
 * ```
 */
data class S3KtorCompletedPart(
    val partNumber: Int,
    val eTag: String,
)

/**
 * CompleteMultipartUpload 응답입니다.
 *
 * ## 동작/계약
 *
 * S3가 반환한 bucket, key, location, ETag를 보존합니다. S3 호환 endpoint는 일부 필드를
 * 생략할 수 있으므로 nullable로 노출합니다.
 *
 * ```kotlin
 * val completed = s3.completeMultipartUpload("demo-bucket", "large.bin", uploadId, parts)
 * check(completed.eTag != null)
 * ```
 */
data class S3KtorCompleteMultipartUploadResponse(
    val bucket: String?,
    val key: String?,
    val location: String?,
    val eTag: String?,
)

/**
 * Presigned S3 요청 URL입니다.
 *
 * ## 동작/계약
 *
 * [method]는 서명된 HTTP method이고 [url]은 `X-Amz-*` query parameter를 포함합니다.
 * URL 유효 시간은 생성 시 전달한 expires 값과 S3 SigV4 최대 7일 제한을 따릅니다.
 *
 * ```kotlin
 * import java.time.Duration
 *
 * fun createDownloadUrl(s3: S3KtorClient): S3KtorPresignedRequest {
 *     val presigned = s3.presignGetObject("demo-bucket", "docs/hello.txt", Duration.ofMinutes(15))
 *     check(presigned.method == "GET")
 *     return presigned
 * }
 * ```
 */
data class S3KtorPresignedRequest(
    val method: String,
    val url: Url,
)
