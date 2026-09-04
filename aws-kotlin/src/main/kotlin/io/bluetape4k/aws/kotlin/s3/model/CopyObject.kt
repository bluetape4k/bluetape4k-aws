package io.bluetape4k.aws.kotlin.s3.model

import aws.sdk.kotlin.services.s3.model.CopyObjectRequest
import aws.sdk.kotlin.services.s3.model.ObjectCannedAcl
import io.bluetape4k.support.requireNotBlank

private const val HEX_DIGITS = "0123456789ABCDEF"
private const val BYTE_MASK = 0xFF
private const val HEX_SHIFT = 4
private const val LOW_NIBBLE_MASK = 0x0F

/**
 * 버킷/키 정보를 받아 RFC 3986으로 percent-encoded copy source를 생성한 뒤 [CopyObjectRequest] 를 생성합니다.
 *
 * ```kotlin
 * val request = copyObjectRequestOf(
 *     srcBucket = "src-bucket",
 *     srcKey = "path/to/src-object.txt",
 *     destBucket = "dest-bucket",
 *     destKey = "path/to/dest-object.txt"
 * )
 * s3Client.copyObject(request)
 * ```
 *
 * @param srcBucket 원본 버킷 이름
 * @param srcKey 원본 객체 키
 * @param destBucket 대상 버킷 이름
 * @param destKey 대상 객체 키
 * @param acl 접근 제어 목록
 * @return [CopyObjectRequest] 인스턴스
 */
inline fun copyObjectRequestOf(
    srcBucket: String,
    srcKey: String,
    destBucket: String,
    destKey: String,
    acl: ObjectCannedAcl? = null,
    crossinline builder: CopyObjectRequest.Builder.() -> Unit = {},
): CopyObjectRequest {
    srcBucket.requireNotBlank("srcBucket")
    srcKey.requireNotBlank("srcKey")
    destBucket.requireNotBlank("destBucket")
    destKey.requireNotBlank("destKey")

    return CopyObjectRequest {
        this.copySource = "$srcBucket/$srcKey".encodeRfc3986()
        this.bucket = destBucket
        this.key = destKey
        this.acl = acl

        builder()
    }
}

/**
 * 이미 URL-encoded된 copy source 문자열을 그대로 사용해 [CopyObjectRequest]를 생성합니다.
 *
 * ```kotlin
 * val request = copyObjectRequestOf(
 *     copySource = "src-bucket%2Fpath%2Fto%2Fsrc-object.txt",
 *     destBucket = "dest-bucket",
 *     destKey = "path/to/dest-object.txt"
 * )
 * s3Client.copyObject(request)
 * ```
 *
 * @param copySource 이미 URL-encoded된 copy source 문자열 (예: "src-bucket%2Fsrc-key")
 * @param destBucket 대상 버킷 이름
 * @param destKey 대상 객체 키
 * @param acl 접근 제어 목록
 * @return [CopyObjectRequest] 인스턴스
 */
inline fun copyObjectRequestOf(
    copySource: String,
    destBucket: String,
    destKey: String,
    acl: ObjectCannedAcl? = null,
    crossinline builder: CopyObjectRequest.Builder.() -> Unit = {},
): CopyObjectRequest {
    copySource.requireNotBlank("copySource")
    destBucket.requireNotBlank("destBucket")
    destKey.requireNotBlank("destKey")

    return CopyObjectRequest {
        this.copySource = copySource
        this.bucket = destBucket
        this.key = destKey
        this.acl = acl

        builder()
    }
}

/**
 * S3 `x-amz-copy-source` 헤더에 사용할 RFC 3986 percent-encoded 문자열을 생성합니다.
 *
 * CopyObject는 bucket과 key를 하나의 헤더 값으로 받으므로 bucket/key 경계와 key 내부의
 * slash도 `%2F`로 인코딩합니다. 이 함수는 raw bucket/key 입력을 대상으로 하며, 이미
 * 인코딩된 값은 [copyObjectRequestOf]의 `copySource` overload로 전달해야 합니다.
 */
@PublishedApi
internal fun String.encodeRfc3986(): String {
    val bytes = toByteArray(Charsets.UTF_8)
    return buildString(bytes.size) {
        bytes.forEach { byte ->
            val value = byte.toInt() and BYTE_MASK
            if (value.isRfc3986Unreserved()) {
                append(value.toChar())
            } else {
                append('%')
                append(HEX_DIGITS[value ushr HEX_SHIFT])
                append(HEX_DIGITS[value and LOW_NIBBLE_MASK])
            }
        }
    }
}

private fun Int.isRfc3986Unreserved(): Boolean =
    when {
        this in 'A'.code..'Z'.code -> true
        this in 'a'.code..'z'.code -> true
        this in '0'.code..'9'.code -> true
        this == '-'.code || this == '.'.code || this == '_'.code || this == '~'.code -> true
        else -> false
    }
