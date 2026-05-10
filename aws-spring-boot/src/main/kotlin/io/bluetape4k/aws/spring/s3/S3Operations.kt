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
 */
interface S3Operations {

    suspend fun existsBucket(bucket: String): Boolean

    suspend fun upload(
        bucket: String,
        key: String,
        bytes: ByteArray,
        contentType: String? = null,
    ): PutObjectResponse

    suspend fun upload(
        bucket: String,
        key: String,
        contents: String,
        charset: Charset = Charsets.UTF_8,
        contentType: String? = "text/plain; charset=${charset.name()}",
    ): PutObjectResponse

    suspend fun downloadBytes(bucket: String, key: String): ByteArray

    suspend fun downloadText(
        bucket: String,
        key: String,
        charset: Charset = Charsets.UTF_8,
    ): String

    suspend fun delete(bucket: String, key: String): DeleteObjectResponse

    suspend fun listPage(
        bucket: String,
        prefix: String? = null,
        maxKeys: Int = 1_000,
        continuationToken: String? = null,
    ): S3ListPage

    fun listFlow(
        bucket: String,
        prefix: String? = null,
        pageSize: Int = 1_000,
    ): Flow<S3Object>

    fun resource(bucket: String, key: String): S3Resource

    fun presignGet(
        bucket: String,
        key: String,
        duration: Duration? = null,
    ): URL

    fun presignPut(
        bucket: String,
        key: String,
        duration: Duration? = null,
        contentType: String? = null,
    ): URL
}
