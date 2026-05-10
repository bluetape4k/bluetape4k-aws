package io.bluetape4k.aws.spring.s3

import org.springframework.core.io.AbstractResource
import software.amazon.awssdk.core.sync.ResponseTransformer
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.HeadObjectRequest
import software.amazon.awssdk.services.s3.model.NoSuchBucketException
import software.amazon.awssdk.services.s3.model.NoSuchKeyException
import software.amazon.awssdk.services.s3.model.S3Exception
import java.io.InputStream

/**
 * S3 객체를 Spring `Resource`로 노출하는 읽기 전용 어댑터.
 */
class S3Resource(
    private val s3Client: S3Client,
    val location: S3ObjectLocation,
): AbstractResource() {

    override fun exists(): Boolean =
        runCatching { headObject(); true }
            .recover { error ->
                when {
                    error.isMissingObjectError() -> false
                    else                         -> throw error
                }
            }
            .getOrThrow()

    override fun getDescription(): String =
        "S3 resource [$location]"

    override fun getFilename(): String =
        location.key.substringAfterLast('/')

    override fun contentLength(): Long =
        headObject().contentLength()

    override fun lastModified(): Long =
        headObject().lastModified().toEpochMilli()

    override fun getInputStream(): InputStream {
        val request = GetObjectRequest.builder()
            .bucket(location.bucket)
            .key(location.key)
            .build()
        return s3Client.getObject(request, ResponseTransformer.toInputStream())
    }

    private fun headObject() =
        s3Client.headObject(
            HeadObjectRequest.builder()
                .bucket(location.bucket)
                .key(location.key)
                .build()
        )

    private fun Throwable.isMissingObjectError(): Boolean =
        when (this) {
            is NoSuchBucketException -> true
            is NoSuchKeyException    -> true
            is S3Exception           -> statusCode() == 404 ||
                    awsErrorDetails()?.errorCode() in setOf("NoSuchBucket", "NoSuchKey", "NotFound")
            else                     -> false
        }
}
