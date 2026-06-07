package io.bluetape4k.aws.spring.s3

import io.bluetape4k.aws.spring.observability.AwsMicrometerSupport
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tags
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import software.amazon.awssdk.services.s3.model.DeleteObjectResponse
import software.amazon.awssdk.services.s3.model.PutObjectResponse
import software.amazon.awssdk.services.s3.model.S3Object
import java.net.URL
import java.nio.charset.Charset
import java.time.Duration

/**
 * Micrometer-instrumented [S3Operations] decorator.
 */
class MicrometerS3Operations(
    private val delegate: S3Operations,
    private val meterRegistry: MeterRegistry,
    private val meterName: String = DEFAULT_METER_NAME,
    private val includeBucketTag: Boolean = false,
): S3Operations {

    override suspend fun existsBucket(bucket: String): Boolean =
        record(OPERATION_EXISTS_BUCKET, bucket) {
            delegate.existsBucket(bucket)
        }

    override suspend fun upload(
        bucket: String,
        key: String,
        bytes: ByteArray,
        contentType: String?,
    ): PutObjectResponse =
        record(OPERATION_UPLOAD, bucket) {
            delegate.upload(bucket, key, bytes, contentType)
        }

    override suspend fun upload(
        bucket: String,
        key: String,
        contents: String,
        charset: Charset,
        contentType: String?,
    ): PutObjectResponse =
        record(OPERATION_UPLOAD, bucket) {
            delegate.upload(bucket, key, contents, charset, contentType)
        }

    override suspend fun downloadBytes(bucket: String, key: String): ByteArray =
        record(OPERATION_DOWNLOAD, bucket) {
            delegate.downloadBytes(bucket, key)
        }

    override suspend fun downloadText(bucket: String, key: String, charset: Charset): String =
        record(OPERATION_DOWNLOAD, bucket) {
            delegate.downloadText(bucket, key, charset)
        }

    override suspend fun delete(bucket: String, key: String): DeleteObjectResponse =
        record(OPERATION_DELETE, bucket) {
            delegate.delete(bucket, key)
        }

    override suspend fun listPage(
        bucket: String,
        prefix: String?,
        maxKeys: Int,
        continuationToken: String?,
    ): S3ListPage =
        record(OPERATION_LIST, bucket) {
            delegate.listPage(bucket, prefix, maxKeys, continuationToken)
        }

    override fun listFlow(bucket: String, prefix: String?, pageSize: Int): Flow<S3Object> = flow {
        record(OPERATION_LIST_FLOW, bucket) {
            delegate.listFlow(bucket, prefix, pageSize).collect { emit(it) }
        }
    }

    override fun resource(bucket: String, key: String): S3Resource =
        recordBlocking(OPERATION_RESOURCE, bucket) {
            delegate.resource(bucket, key)
        }

    override fun presignGet(bucket: String, key: String, duration: Duration?): URL =
        recordBlocking(OPERATION_PRESIGN_GET, bucket) {
            delegate.presignGet(bucket, key, duration)
        }

    override fun presignPut(bucket: String, key: String, duration: Duration?, contentType: String?): URL =
        recordBlocking(OPERATION_PRESIGN_PUT, bucket) {
            delegate.presignPut(bucket, key, duration, contentType)
        }

    private suspend fun <T> record(operation: String, bucket: String, block: suspend () -> T): T =
        AwsMicrometerSupport.record(meterRegistry, meterName, { outcome, exception ->
            tags(operation, outcome, bucket, exception)
        }, block)

    private fun <T> recordBlocking(operation: String, bucket: String, block: () -> T): T =
        AwsMicrometerSupport.record(meterRegistry, meterName, { outcome, exception ->
            tags(operation, outcome, bucket, exception)
        }, block)

    private fun tags(operation: String, outcome: String, bucket: String, exception: Throwable?): Tags =
        AwsMicrometerSupport.tags(
            service = AwsMicrometerSupport.SERVICE_S3,
            operation = operation,
            outcome = outcome,
            exception = exception,
            extras = AwsMicrometerSupport.bucketTag(bucket, includeBucketTag),
        )

    companion object {
        const val DEFAULT_METER_NAME: String = "bluetape4k.aws.s3.operation"
        const val OPERATION_EXISTS_BUCKET: String = "exists_bucket"
        const val OPERATION_UPLOAD: String = "upload"
        const val OPERATION_DOWNLOAD: String = "download"
        const val OPERATION_DELETE: String = "delete"
        const val OPERATION_LIST: String = "list"
        const val OPERATION_LIST_FLOW: String = "list_flow"
        const val OPERATION_RESOURCE: String = "resource"
        const val OPERATION_PRESIGN_GET: String = "presign_get"
        const val OPERATION_PRESIGN_PUT: String = "presign_put"
    }
}
