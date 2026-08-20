package io.bluetape4k.aws.spring.s3

import io.bluetape4k.aws.s3.existsBucket
import io.bluetape4k.aws.s3.getAsByteArray
import io.bluetape4k.aws.s3.putAsByteArray
import io.bluetape4k.aws.s3.putAsString
import io.bluetape4k.aws.spring.sqs.SqsExtendedPayloadReadException
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.future.await
import software.amazon.awssdk.core.async.AsyncRequestBody
import software.amazon.awssdk.core.async.AsyncResponseTransformer
import software.amazon.awssdk.services.s3.S3AsyncClient
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.DeleteObjectResponse
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.HeadObjectRequest
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import software.amazon.awssdk.services.s3.model.PutObjectResponse
import software.amazon.awssdk.services.s3.model.S3Object
import software.amazon.awssdk.services.s3.model.S3Exception
import java.io.ByteArrayOutputStream
import software.amazon.awssdk.services.s3.presigner.S3Presigner
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest
import java.net.URL
import java.nio.charset.Charset
import java.time.Duration

private const val BOUNDED_SCRATCH_BUFFER_BYTES = 8 * 1024
private const val HTTP_CONFLICT = 409
private const val HTTP_PRECONDITION_FAILED = 412

/**
 * Spring Boot 애플리케이션에서 S3 작업을 Coroutines API로 제공하는 기본 구현체.
 *
 * ## 동작/계약
 *
 * `S3AsyncClient`로 비동기 객체 작업을 수행하고, `S3Client`로 Spring `Resource`
 * 뷰를 만들며, `S3Presigner`로 presigned GET/PUT URL을 생성합니다. presign duration을
 * 명시하지 않으면 `bluetape4k.aws.s3.presign.duration` 설정값을 사용합니다.
 *
 * LocalStack처럼 `endpointOverride`를 사용하는 S3 호환 엔드포인트에서는 버킷 이름을
 * 호스트가 아닌 경로에 포함하도록 `path-style-access-enabled`를 함께 켜야 합니다.
 *
 * ```kotlin
 * class DocumentStorage(private val s3: S3CoroutinesTemplate) {
 *
 *     suspend fun save(bucket: String, key: String, contents: String) {
 *         s3.upload(bucket, key, contents, contentType = "text/plain; charset=utf-8")
 *     }
 *
 *     fun uploadUrl(bucket: String, key: String): URL =
 *         s3.presignPut(bucket, key, contentType = "text/plain")
 * }
 * ```
 */
@Suppress("TooManyFunctions")
class S3CoroutinesTemplate(
    private val s3AsyncClient: S3AsyncClient,
    private val s3Client: S3Client,
    private val s3Presigner: S3Presigner,
    private val properties: S3Properties,
): S3ObjectMetadataOperations, S3BoundedObjectReadOperations {

    override suspend fun existsBucket(bucket: String): Boolean =
        s3AsyncClient.existsBucket(bucket)

    override suspend fun headObject(bucket: String, key: String): S3ObjectMetadata {
        val response = s3AsyncClient.headObject(
            HeadObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .build(),
        ).await()

        return S3ObjectMetadata(
            sizeBytes = requireNotNull(response.contentLength()) {
                "S3 HEAD response did not include Content-Length."
            },
            etag = response.eTag(),
            contentType = response.contentType(),
            lastModified = response.lastModified(),
        )
    }

    override suspend fun headObjectWithMetadata(bucket: String, key: String): S3HeadMetadata {
        val response = s3AsyncClient.headObject(
            HeadObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .build(),
        ).await()

        return S3HeadMetadata(
            sizeBytes = response.contentLength() ?: 0L,
            etag = response.eTag(),
            contentType = response.contentType(),
            userMetadata = response.metadata().orEmpty().toMap(),
        )
    }

    override suspend fun putObjectIfAbsentWithMetadata(
        bucket: String,
        key: String,
        bytes: ByteArray,
        contentType: String,
        metadata: Map<String, String>,
    ): S3PutIfAbsentResult {
        try {
            s3AsyncClient.putObject(
                PutObjectRequest.builder()
                    .bucket(bucket)
                    .key(key)
                    .contentType(contentType)
                    .metadata(metadata)
                    .ifNoneMatch("*")
                    .build(),
                AsyncRequestBody.fromBytes(bytes),
            ).await()
            return S3PutIfAbsentResult.Created
        } catch (error: S3Exception) {
            if (error.statusCode() == HTTP_CONFLICT || error.statusCode() == HTTP_PRECONDITION_FAILED) {
                return S3PutIfAbsentResult.AlreadyExists(headObjectWithMetadata(bucket, key))
            }
            throw error
        }
    }

    override suspend fun downloadBytesBounded(bucket: String, key: String, maxBytes: Int): ByteArray {
        require(maxBytes in 1..S3BoundedObjectReadOperations.MAX_BYTES) {
            "maxBytes must be between 1 and ${S3BoundedObjectReadOperations.MAX_BYTES}."
        }
        val publisher = s3AsyncClient.getObject(
            GetObjectRequest.builder().bucket(bucket).key(key).build(),
            AsyncResponseTransformer.toPublisher(),
        ).await()
        val output = ByteArrayOutputStream(minOf(maxBytes, BOUNDED_SCRATCH_BUFFER_BYTES))
        var size = 0
        publisher.asFlow().collect { chunk ->
            val copy = chunk.slice()
            val chunkSize = copy.remaining()
            if (chunkSize > maxBytes - size) {
                throw SqsExtendedPayloadReadException.create(pointerPresent = true, retryable = false)
            }
            val bytes = ByteArray(copy.remaining())
            copy.get(bytes)
            output.write(bytes)
            size += chunkSize
        }
        return output.toByteArray()
    }

    override suspend fun upload(
        bucket: String,
        key: String,
        bytes: ByteArray,
        contentType: String?,
    ): PutObjectResponse =
        s3AsyncClient.putAsByteArray(bucket, key, bytes) {
            contentType?.let { contentType(it) }
        }

    override suspend fun upload(
        bucket: String,
        key: String,
        contents: String,
        charset: Charset,
        contentType: String?,
    ): PutObjectResponse =
        s3AsyncClient.putAsString(bucket, key, contents, charset) {
            contentType?.let { contentType(it) }
        }

    override suspend fun downloadBytes(bucket: String, key: String): ByteArray =
        s3AsyncClient.getAsByteArray(bucket, key)

    override suspend fun downloadText(bucket: String, key: String, charset: Charset): String =
        downloadBytes(bucket, key).toString(charset)

    override suspend fun delete(bucket: String, key: String): DeleteObjectResponse =
        s3AsyncClient.deleteObject { it.bucket(bucket).key(key) }.await()

    override suspend fun listPage(
        bucket: String,
        prefix: String?,
        maxKeys: Int,
        continuationToken: String?,
    ): S3ListPage {
        require(maxKeys in 1..1_000) { "maxKeys must be between 1 and 1000." }

        val response = s3AsyncClient.listObjectsV2 {
            it.bucket(bucket)
            prefix?.let(it::prefix)
            it.maxKeys(maxKeys)
            continuationToken?.let(it::continuationToken)
        }.await()

        return S3ListPage(
            objects = response.contents().orEmpty(),
            isTruncated = response.isTruncated == true,
            nextContinuationToken = response.nextContinuationToken(),
            keyCount = response.keyCount() ?: response.contents().orEmpty().size,
        )
    }

    override fun listFlow(bucket: String, prefix: String?, pageSize: Int): Flow<S3Object> = flow {
        var continuationToken: String? = null

        do {
            val page = listPage(
                bucket = bucket,
                prefix = prefix,
                maxKeys = pageSize,
                continuationToken = continuationToken,
            )
            page.objects.forEach { emit(it) }
            continuationToken = page.nextContinuationToken
        } while (continuationToken != null)
    }

    override fun resource(bucket: String, key: String): S3Resource =
        S3Resource(s3Client, S3ObjectLocation(bucket, key))

    override fun presignGet(bucket: String, key: String, duration: Duration?): URL {
        val objectRequest = GetObjectRequest.builder()
            .bucket(bucket)
            .key(key)
            .build()
        val presignRequest = GetObjectPresignRequest.builder()
            .signatureDuration(duration ?: properties.presign.duration)
            .getObjectRequest(objectRequest)
            .build()

        return s3Presigner.presignGetObject(presignRequest).url()
    }

    override fun presignPut(
        bucket: String,
        key: String,
        duration: Duration?,
        contentType: String?,
    ): URL {
        val objectRequest = PutObjectRequest.builder()
            .bucket(bucket)
            .key(key)
            .apply { contentType?.let { contentType(it) } }
            .build()
        val presignRequest = PutObjectPresignRequest.builder()
            .signatureDuration(duration ?: properties.presign.duration)
            .putObjectRequest(objectRequest)
            .build()

        return s3Presigner.presignPutObject(presignRequest).url()
    }
}
