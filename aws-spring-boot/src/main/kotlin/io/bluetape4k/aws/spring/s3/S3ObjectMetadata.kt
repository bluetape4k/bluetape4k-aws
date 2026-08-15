package io.bluetape4k.aws.spring.s3

import java.io.Serializable
import java.time.Instant

/**
 * S3 객체의 단일 HEAD 응답 snapshot입니다.
 *
 * ETag은 AWS가 반환한 opaque token을 그대로 보존하며 MD5 또는 content hash로
 * 해석하지 않습니다. provider가 필드를 제공하지 않으면 nullable 값은 null입니다.
 */
data class S3ObjectMetadata(
    val sizeBytes: Long,
    val etag: String? = null,
    val contentType: String? = null,
    val lastModified: Instant? = null,
) : Serializable {

    init {
        require(sizeBytes >= 0) { "sizeBytes must be greater than or equal to 0." }
    }

    companion object {
        private const val serialVersionUID: Long = 1L
    }
}
