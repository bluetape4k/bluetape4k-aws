package io.bluetape4k.aws.spring.s3

import kotlinx.coroutines.flow.Flow
import software.amazon.awssdk.services.s3.model.DeleteObjectResponse
import software.amazon.awssdk.services.s3.model.PutObjectResponse
import software.amazon.awssdk.services.s3.model.S3Object
import java.net.URL
import java.nio.charset.Charset
import java.time.Duration

/**
 * Spring 애플리케이션에서 사용하는 Coroutines 기반 S3 작업 계약.
 *
 * ```kotlin
 * import java.net.URL
 *
 * class DocumentStorage(private val s3: S3Operations) {
 *
 *     suspend fun save(bucket: String, key: String, contents: String) {
 *         s3.upload(bucket, key, contents, contentType = "text/plain")
 *     }
 *
 *     suspend fun read(bucket: String, key: String): String =
 *         s3.downloadText(bucket, key)
 *
 *     fun presignedUpload(bucket: String, key: String): URL =
 *         s3.presignPut(bucket, key, contentType = "application/json")
 * }
 * ```
 */
@Suppress("TooManyFunctions")
interface S3Operations {

    /**
     * bucket/key 객체의 metadata를 단일 HEAD snapshot으로 조회합니다.
     *
     * 기존 custom 구현체가 갑자기 source/ABI에서 깨지지 않도록 기본 구현은
     * unsupported로 fail closed합니다. 지원하지 않는 구현체는 resource 또는
     * list fallback으로 성공한 것처럼 응답하지 않아야 합니다.
     */
    suspend fun headObject(bucket: String, key: String): S3ObjectMetadata =
        throw UnsupportedOperationException(
            "S3Operations.headObject is not supported by this implementation",
        )

    /**
     * [bucket]이 존재하면 `true`, 존재하지 않으면 `false`를 반환합니다.
     */
    suspend fun existsBucket(bucket: String): Boolean

    /**
     * [bytes]를 [bucket]/[key] 객체로 업로드합니다.
     *
     * [contentType]이 null이 아니면 `PutObjectRequest.contentType`에 설정합니다.
     */
    suspend fun upload(
        bucket: String,
        key: String,
        bytes: ByteArray,
        contentType: String? = null,
    ): PutObjectResponse

    /**
     * [contents] 문자열을 [charset]으로 인코딩해 [bucket]/[key] 객체로 업로드합니다.
     *
     * 기본 [contentType]은 `text/plain`과 [charset]을 포함합니다.
     */
    suspend fun upload(
        bucket: String,
        key: String,
        contents: String,
        charset: Charset = Charsets.UTF_8,
        contentType: String? = "text/plain; charset=${charset.name()}",
    ): PutObjectResponse

    /**
     * [bucket]/[key] 객체 내용을 [ByteArray]로 다운로드합니다.
     */
    suspend fun downloadBytes(bucket: String, key: String): ByteArray

    /**
     * [bucket]/[key] 객체 내용을 [charset]으로 디코딩해 문자열로 반환합니다.
     */
    suspend fun downloadText(
        bucket: String,
        key: String,
        charset: Charset = Charsets.UTF_8,
    ): String

    /**
     * [bucket]/[key] 객체를 삭제합니다.
     */
    suspend fun delete(bucket: String, key: String): DeleteObjectResponse

    /**
     * [bucket] 객체 목록을 한 페이지 조회합니다.
     *
     * [maxKeys]는 AWS S3 `ListObjectsV2` 제한에 맞춰 1..1000 범위여야 합니다.
     */
    suspend fun listPage(
        bucket: String,
        prefix: String? = null,
        maxKeys: Int = 1_000,
        continuationToken: String? = null,
    ): S3ListPage

    /**
     * [bucket] 객체 목록을 차가운 [Flow]로 제공합니다.
     *
     * Flow 수집이 시작될 때 페이지 조회가 실행되며, [pageSize] 단위로 다음 페이지를 요청합니다.
     */
    fun listFlow(
        bucket: String,
        prefix: String? = null,
        pageSize: Int = 1_000,
    ): Flow<S3Object>

    /**
     * [bucket]/[key] 객체를 Spring `Resource`로 노출합니다.
     */
    fun resource(bucket: String, key: String): S3Resource

    /**
     * [bucket]/[key] 객체 다운로드용 presigned GET URL을 생성합니다.
     *
     * [duration]이 null이면 `bluetape4k.aws.s3.presign.duration` 값을 사용합니다.
     */
    fun presignGet(
        bucket: String,
        key: String,
        duration: Duration? = null,
    ): URL

    /**
     * [bucket]/[key] 객체 업로드용 presigned PUT URL을 생성합니다.
     *
     * [contentType]을 지정하면 서명 대상 `PutObjectRequest`에도 같은 값을 포함합니다.
     */
    fun presignPut(
        bucket: String,
        key: String,
        duration: Duration? = null,
        contentType: String? = null,
    ): URL
}
