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
        putObjectRecord(bucket) {
            delegate.putObject(bucket, key, bytes, contentType, metadata, headers)
        }

    suspend fun putObject(
        request: S3KtorPutObjectRequest,
        body: OutgoingContent,
    ): S3KtorPutObjectResponse =
        putObjectRecord(request.bucket) {
            delegate.putObject(request, body)
        }

    suspend fun getObjectBytes(bucket: String, key: String): ByteArray =
        getObjectRecord(bucket) {
            delegate.getObjectBytes(bucket, key)
        }

    suspend fun getObject(bucket: String, key: String): S3KtorGetObjectResponse =
        getObjectRecord(bucket) {
            delegate.getObject(bucket, key)
        }

    suspend fun deleteObject(bucket: String, key: String): S3KtorDeleteObjectResponse =
        deleteObjectRecord(bucket) {
            delegate.deleteObject(bucket, key)
        }

    suspend fun listObjectsV2(request: S3KtorListObjectsRequest): S3KtorListObjectsResponse =
        listObjectsV2Record(request.bucket) {
            delegate.listObjectsV2(request)
        }

    fun presignGetObject(bucket: String, key: String, expires: Duration): S3KtorPresignedRequest =
        presignGetObjectRecord(bucket) {
            delegate.presignGetObject(bucket, key, expires)
        }

    fun presignPutObject(bucket: String, key: String, expires: Duration): S3KtorPresignedRequest =
        presignPutObjectRecord(bucket) {
            delegate.presignPutObject(bucket, key, expires)
        }

    override fun close() {
        delegate.close()
    }

    private suspend fun <T> record(operation: String, bucket: String, block: suspend () -> T): T =
        KtorMicrometerSupport.recordSuspend(meterRegistry, meterName, { outcome, exception ->
            tags(operation, outcome, bucket, exception)
        }, block)

    private suspend fun <T> putObjectRecord(bucket: String, block: suspend () -> T): T =
        record(OPERATION_PUT_OBJECT, bucket, block)

    private suspend fun <T> getObjectRecord(bucket: String, block: suspend () -> T): T =
        record(OPERATION_GET_OBJECT, bucket, block)

    private suspend fun <T> deleteObjectRecord(bucket: String, block: suspend () -> T): T =
        record(OPERATION_DELETE_OBJECT, bucket, block)

    private suspend fun <T> listObjectsV2Record(bucket: String, block: suspend () -> T): T =
        record(OPERATION_LIST_OBJECTS_V2, bucket, block)

    private fun <T> recordBlocking(operation: String, bucket: String, block: () -> T): T =
        KtorMicrometerSupport.recordBlocking(meterRegistry, meterName, { outcome, exception ->
            tags(operation, outcome, bucket, exception)
        }, block)

    private fun <T> presignGetObjectRecord(bucket: String, block: () -> T): T =
        recordBlocking(OPERATION_PRESIGN_GET_OBJECT, bucket, block)

    private fun <T> presignPutObjectRecord(bucket: String, block: () -> T): T =
        recordBlocking(OPERATION_PRESIGN_PUT_OBJECT, bucket, block)

    private fun tags(operation: String, outcome: String, bucket: String, exception: String): Tags =
        KtorMicrometerSupport.tags(
            service = KtorMicrometerSupport.SERVICE_S3,
            operation = operation,
            outcome = outcome,
            exception = exception,
            extras = KtorMicrometerSupport.bucketTag(bucket, includeBucketTag),
        )

    companion object {
        const val DEFAULT_METER_NAME: String = "bluetape4k.aws.ktor.s3.operation"
        const val OPERATION_PUT_OBJECT: String = "put_object"
        const val OPERATION_GET_OBJECT: String = "get_object"
        const val OPERATION_DELETE_OBJECT: String = "delete_object"
        const val OPERATION_LIST_OBJECTS_V2: String = "list_objects_v2"
        const val OPERATION_PRESIGN_GET_OBJECT: String = "presign_get_object"
        const val OPERATION_PRESIGN_PUT_OBJECT: String = "presign_put_object"
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
