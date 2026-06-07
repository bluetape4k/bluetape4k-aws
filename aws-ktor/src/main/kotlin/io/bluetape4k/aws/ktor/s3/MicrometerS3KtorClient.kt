package io.bluetape4k.aws.ktor.s3

import io.bluetape4k.aws.ktor.observability.KtorMicrometerSupport
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tags
import io.ktor.http.content.OutgoingContent
import java.time.Duration

/**
 * Opt-in Micrometer wrapper for selected [S3KtorClient] operations.
 */
class MicrometerS3KtorClient(
    private val delegate: S3KtorClient,
    private val meterRegistry: MeterRegistry,
    private val meterName: String = DEFAULT_METER_NAME,
    private val includeBucketTag: Boolean = false,
): AutoCloseable {

    suspend fun putObject(
        bucket: String,
        key: String,
        bytes: ByteArray,
        contentType: String? = null,
        metadata: Map<String, String> = emptyMap(),
        headers: Map<String, String> = emptyMap(),
    ): S3KtorPutObjectResponse =
        record("put_object", bucket) {
            delegate.putObject(bucket, key, bytes, contentType, metadata, headers)
        }

    suspend fun putObject(
        request: S3KtorPutObjectRequest,
        body: OutgoingContent,
    ): S3KtorPutObjectResponse =
        record("put_object", request.bucket) {
            delegate.putObject(request, body)
        }

    suspend fun getObjectBytes(bucket: String, key: String): ByteArray =
        record("get_object", bucket) {
            delegate.getObjectBytes(bucket, key)
        }

    suspend fun getObject(bucket: String, key: String): S3KtorGetObjectResponse =
        record("get_object", bucket) {
            delegate.getObject(bucket, key)
        }

    suspend fun deleteObject(bucket: String, key: String): S3KtorDeleteObjectResponse =
        record("delete_object", bucket) {
            delegate.deleteObject(bucket, key)
        }

    suspend fun listObjectsV2(request: S3KtorListObjectsRequest): S3KtorListObjectsResponse =
        record("list_objects_v2", request.bucket) {
            delegate.listObjectsV2(request)
        }

    fun presignGetObject(bucket: String, key: String, expires: Duration): S3KtorPresignedRequest =
        recordBlocking("presign_get_object", bucket) {
            delegate.presignGetObject(bucket, key, expires)
        }

    fun presignPutObject(bucket: String, key: String, expires: Duration): S3KtorPresignedRequest =
        recordBlocking("presign_put_object", bucket) {
            delegate.presignPutObject(bucket, key, expires)
        }

    override fun close() {
        delegate.close()
    }

    private suspend fun <T> record(operation: String, bucket: String, block: suspend () -> T): T =
        KtorMicrometerSupport.recordSuspend(meterRegistry, meterName, { outcome, exception ->
            tags(operation, outcome, bucket, exception)
        }, block)

    private fun <T> recordBlocking(operation: String, bucket: String, block: () -> T): T =
        KtorMicrometerSupport.recordBlocking(meterRegistry, meterName, { outcome, exception ->
            tags(operation, outcome, bucket, exception)
        }, block)

    private fun tags(operation: String, outcome: String, bucket: String, exception: String): Tags =
        KtorMicrometerSupport.tags(
            service = "s3",
            operation = operation,
            outcome = outcome,
            exception = exception,
            extras = KtorMicrometerSupport.bucketTag(bucket, includeBucketTag),
        )

    companion object {
        const val DEFAULT_METER_NAME: String = "bluetape4k.aws.ktor.s3.operation"
    }
}

/**
 * Wraps this S3 Ktor client with Micrometer instrumentation.
 */
fun S3KtorClient.withMicrometer(
    meterRegistry: MeterRegistry,
    meterName: String = MicrometerS3KtorClient.DEFAULT_METER_NAME,
    includeBucketTag: Boolean = false,
): MicrometerS3KtorClient =
    MicrometerS3KtorClient(this, meterRegistry, meterName, includeBucketTag)
