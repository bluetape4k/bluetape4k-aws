package io.bluetape4k.aws.examples.spring.s3

import io.bluetape4k.aws.spring.s3.S3ClientSideEncryptionOperations
import io.bluetape4k.aws.spring.s3.S3Operations
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.springframework.beans.factory.ObjectProvider
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.net.URL

/**
 * `S3Operations`로 문서 업로드, 다운로드, 목록, presigned URL 발급을 제공하는 WebFlux 컨트롤러 예제입니다.
 *
 * ## 동작/계약
 *
 * bucket과 key는 query parameter로 받습니다. 다운로드 응답은 S3 객체 bytes를 그대로 반환하고,
 * 목록 응답은 `S3Operations.listFlow`를 `Flow<S3DocumentObjectResponse>`로 노출합니다. Presigned URL은
 * `aws-spring-boot` 설정의 기본 만료 시간을 따릅니다.
 *
 * ```kotlin
 * val controller = S3DocumentController(s3Operations)
 * val url = controller.presignedUpload("demo-bucket", "docs/hello.txt", "text/plain")
 * ```
 */
@RestController
@RequestMapping("/s3/documents")
class S3DocumentController(
    private val s3: S3Operations,
    private val encryptedS3Provider: ObjectProvider<S3ClientSideEncryptionOperations>,
) {

    @PutMapping(consumes = [MediaType.APPLICATION_OCTET_STREAM_VALUE])
    suspend fun upload(
        @RequestParam bucket: String,
        @RequestParam key: String,
        @RequestBody bytes: ByteArray,
        @RequestHeader(HttpHeaders.CONTENT_TYPE, required = false) contentType: String?,
    ): S3DocumentUploadResponse {
        val response = s3.upload(bucket = bucket, key = key, bytes = bytes, contentType = contentType)
        return S3DocumentUploadResponse(
            bucket = bucket,
            key = key,
            eTag = response.eTag(),
        )
    }

    @PutMapping("/encrypted", consumes = [MediaType.APPLICATION_OCTET_STREAM_VALUE])
    suspend fun uploadEncrypted(
        @RequestParam bucket: String,
        @RequestParam key: String,
        @RequestParam(required = false) tenant: String?,
        @RequestBody bytes: ByteArray,
        @RequestHeader(HttpHeaders.CONTENT_TYPE, required = false) contentType: String?,
    ): S3DocumentUploadResponse {
        val response = encryptedS3().uploadEncrypted(
            bucket = bucket,
            key = key,
            bytes = bytes,
            contentType = contentType,
            metadata = tenant?.let { mapOf("tenant" to it) }.orEmpty(),
            encryptionContext = tenant?.let { mapOf("tenant" to it) }.orEmpty(),
        )
        return S3DocumentUploadResponse(
            bucket = bucket,
            key = key,
            eTag = response.eTag(),
        )
    }

    @GetMapping
    suspend fun download(
        @RequestParam bucket: String,
        @RequestParam key: String,
    ): ResponseEntity<ByteArray> =
        ResponseEntity.ok()
            .contentType(MediaType.APPLICATION_OCTET_STREAM)
            .body(s3.downloadBytes(bucket, key))

    @GetMapping("/encrypted")
    suspend fun downloadEncrypted(
        @RequestParam bucket: String,
        @RequestParam key: String,
        @RequestParam(required = false) tenant: String?,
    ): ResponseEntity<ByteArray> =
        ResponseEntity.ok()
            .contentType(MediaType.APPLICATION_OCTET_STREAM)
            .body(
                encryptedS3().downloadEncryptedBytes(
                    bucket = bucket,
                    key = key,
                    encryptionContext = tenant?.let { mapOf("tenant" to it) }.orEmpty(),
                )
            )

    @GetMapping("/objects")
    fun listObjects(
        @RequestParam bucket: String,
        @RequestParam(required = false) prefix: String?,
    ): Flow<S3DocumentObjectResponse> =
        s3.listFlow(bucket = bucket, prefix = prefix)
            .map { S3DocumentObjectResponse(key = it.key(), size = it.size()) }

    @GetMapping("/presigned-get")
    fun presignedDownload(
        @RequestParam bucket: String,
        @RequestParam key: String,
    ): S3PresignedUrlResponse =
        S3PresignedUrlResponse(s3.presignGet(bucket, key))

    @GetMapping("/presigned-put")
    fun presignedUpload(
        @RequestParam bucket: String,
        @RequestParam key: String,
        @RequestParam(required = false) contentType: String?,
    ): S3PresignedUrlResponse =
        S3PresignedUrlResponse(s3.presignPut(bucket, key, contentType = contentType))

    @DeleteMapping
    suspend fun delete(
        @RequestParam bucket: String,
        @RequestParam key: String,
    ) {
        s3.delete(bucket, key)
    }

    private fun encryptedS3(): S3ClientSideEncryptionOperations =
        requireNotNull(encryptedS3Provider.getIfAvailable()) {
            "S3 client-side encryption is not configured. Enable bluetape4k.aws.s3.client-side-encryption and provide KmsOperations."
        }
}

/**
 * S3 업로드 결과 응답입니다.
 *
 * ## 동작/계약
 *
 * 업로드 대상 bucket/key와 S3 ETag를 반환합니다. S3 호환 endpoint에 따라 [eTag]는 null일 수 있습니다.
 */
data class S3DocumentUploadResponse(
    val bucket: String,
    val key: String,
    val eTag: String?,
)

/**
 * S3 객체 목록 응답 항목입니다.
 *
 * ## 동작/계약
 *
 * `ListObjectsV2`의 key와 size만 예제 API 표면으로 노출합니다.
 */
data class S3DocumentObjectResponse(
    val key: String,
    val size: Long?,
)

/**
 * Presigned URL 응답입니다.
 *
 * ## 동작/계약
 *
 * [url]은 클라이언트가 직접 호출할 수 있는 S3 presigned GET/PUT URL입니다.
 */
data class S3PresignedUrlResponse(
    val url: URL,
)
