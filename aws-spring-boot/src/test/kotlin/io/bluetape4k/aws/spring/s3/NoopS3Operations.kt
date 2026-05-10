package io.bluetape4k.aws.spring.s3

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import software.amazon.awssdk.services.s3.model.DeleteObjectResponse
import software.amazon.awssdk.services.s3.model.PutObjectResponse
import software.amazon.awssdk.services.s3.model.S3Object
import java.net.URI
import java.net.URL
import java.nio.charset.Charset
import java.time.Duration

object NoopS3Operations: S3Operations {
    override suspend fun existsBucket(bucket: String): Boolean = false

    override suspend fun upload(
        bucket: String,
        key: String,
        bytes: ByteArray,
        contentType: String?,
    ): PutObjectResponse =
        PutObjectResponse.builder().build()

    override suspend fun upload(
        bucket: String,
        key: String,
        contents: String,
        charset: Charset,
        contentType: String?,
    ): PutObjectResponse =
        PutObjectResponse.builder().build()

    override suspend fun downloadBytes(bucket: String, key: String): ByteArray = ByteArray(0)

    override suspend fun downloadText(bucket: String, key: String, charset: Charset): String = ""

    override suspend fun delete(bucket: String, key: String): DeleteObjectResponse =
        DeleteObjectResponse.builder().build()

    override suspend fun listPage(
        bucket: String,
        prefix: String?,
        maxKeys: Int,
        continuationToken: String?,
    ): S3ListPage =
        S3ListPage(emptyList(), isTruncated = false, nextContinuationToken = null, keyCount = 0)

    override fun listFlow(bucket: String, prefix: String?, pageSize: Int): Flow<S3Object> =
        emptyFlow()

    override fun resource(bucket: String, key: String): S3Resource =
        throw UnsupportedOperationException("NoopS3Operations does not create resources.")

    override fun presignGet(bucket: String, key: String, duration: Duration?): URL =
        URI("https://example.com/$bucket/$key").toURL()

    override fun presignPut(bucket: String, key: String, duration: Duration?, contentType: String?): URL =
        URI("https://example.com/$bucket/$key").toURL()
}
