package io.bluetape4k.aws.ktor.s3

import io.ktor.http.Headers
import io.ktor.http.Url
import io.ktor.utils.io.ByteReadChannel
import java.io.Serializable
import java.time.Instant

/**
 * Represents an S3 object location.
 *
 * ## Behavior/Contract
 *
 * A value object that keeps only [bucket] and [key]. Operation methods on [S3KtorClient]
 * perform validation before sending real requests.
 *
 * ```kotlin
 * val ref = S3KtorObjectRef(bucket = "demo-bucket", key = "docs/hello.txt")
 * ```
 */
data class S3KtorObjectRef(
    val bucket: String,
    val key: String,
): Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

/**
 * S3 PutObject request.
 *
 * ## Behavior/Contract
 *
 * Pass [metadata] keys without the `x-amz-meta-` prefix. [headers] are added to the S3
 * request as-is, so use them for headers that must be controlled explicitly, such as `Content-MD5`.
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
): Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

/**
 * S3 PutObject response.
 *
 * ## Behavior/Contract
 *
 * Keeps the ETag, version id, and raw response headers returned by S3. [versionId] may be
 * null for buckets without versioning.
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
): Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

/**
 * S3 GetObject response body and metadata.
 *
 * ## Behavior/Contract
 *
 * [bytes] contains the full response body loaded into memory. Use
 * [S3KtorClient.getObjectStream] for large objects. [metadata] keys are exposed without the
 * `x-amz-meta-` prefix.
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
) : Serializable {
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

    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

/**
 * S3 GetObject streaming response.
 *
 * ## Behavior/Contract
 *
 * [body] is the Ktor response channel, so the caller must finish consuming it.
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
): Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

/**
 * S3 DeleteObject response.
 *
 * ## Behavior/Contract
 *
 * The delete marker and version id may be null depending on bucket versioning and delete mode.
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
): Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

/**
 * S3 ListObjectsV2 request.
 *
 * ## Behavior/Contract
 *
 * Null fields are omitted from request query parameters. [maxKeys] should be within the S3 API
 * limit range of 1..1000.
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
): Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

/**
 * S3 object list item.
 *
 * ## Behavior/Contract
 *
 * Converts a ListObjectsV2 `Contents` element into a simple value object. Optional fields missing
 * from the S3 response remain null.
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
): Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

/**
 * S3 ListObjectsV2 response.
 *
 * ## Behavior/Contract
 *
 * Keeps paging fields and the object list from the S3 XML response. When [isTruncated] is `true`,
 * use [nextContinuationToken] to fetch the next page.
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
): Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

/**
 * Multipart upload initiation result.
 *
 * ## Behavior/Contract
 *
 * Includes the [uploadId] required for subsequent part upload and complete/abort requests.
 *
 * ```kotlin
 * val upload = s3.createMultipartUpload("demo-bucket", "large.bin")
 * ```
 */
data class S3KtorMultipartUpload(
    val bucket: String,
    val key: String,
    val uploadId: String,
): Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

/**
 * Multipart upload part result.
 *
 * ## Behavior/Contract
 *
 * Keeps the part number and ETag for CompleteMultipartUpload XML generation. Complete requests
 * sort parts by part number.
 *
 * ```kotlin
 * val part = s3.uploadPart("demo-bucket", "large.bin", uploadId, 1, bytes)
 * ```
 */
data class S3KtorCompletedPart(
    val partNumber: Int,
    val eTag: String,
): Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

/**
 * CompleteMultipartUpload response.
 *
 * ## Behavior/Contract
 *
 * Keeps the bucket, key, location, and ETag returned by S3. S3-compatible endpoints may omit
 * some fields, so they are exposed as nullable values.
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
): Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

/**
 * Presigned S3 request URL.
 *
 * ## Behavior/Contract
 *
 * [method] is the signed HTTP method, and [url] includes `X-Amz-*` query parameters.
 * URL validity follows the expires value passed at creation time and the S3 SigV4 7-day maximum.
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
): Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}
