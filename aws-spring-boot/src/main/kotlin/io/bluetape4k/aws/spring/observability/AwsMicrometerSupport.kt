package io.bluetape4k.aws.spring.observability

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tag
import io.micrometer.core.instrument.Tags
import io.micrometer.core.instrument.Timer
import kotlinx.coroutines.CancellationException
import java.time.Duration

internal object AwsMicrometerSupport {

    const val SERVICE_S3: String = "s3"
    const val SERVICE_SQS: String = "sqs"

    const val TAG_SERVICE: String = "service"
    const val TAG_OPERATION: String = "operation"
    const val TAG_OUTCOME: String = "outcome"
    const val TAG_EXCEPTION: String = "exception"
    const val TAG_QUEUE_NAME: String = "queue.name"
    const val TAG_LISTENER_ID: String = "listener.id"
    const val TAG_BUCKET: String = "bucket"

    const val OUTCOME_SUCCESS: String = "success"
    const val OUTCOME_CANCELLED: String = "cancelled"
    const val OUTCOME_FAILURE: String = "failure"

    const val EXCEPTION_NONE: String = "none"
    const val UNKNOWN: String = "unknown"

    fun tags(
        service: String,
        operation: String,
        outcome: String,
        exception: Throwable? = null,
        extras: Iterable<Tag> = emptyList(),
    ): Tags =
        Tags.of(
            TAG_SERVICE, service,
            TAG_OPERATION, operation,
            TAG_OUTCOME, outcome,
            TAG_EXCEPTION, exceptionName(exception),
        ).and(extras)

    fun queueNameTag(queueUrl: String?): Tag =
        Tag.of(TAG_QUEUE_NAME, queueName(queueUrl))

    fun listenerIdTag(listenerId: String): Tag =
        Tag.of(TAG_LISTENER_ID, listenerId.ifBlank { UNKNOWN })

    fun bucketTag(bucket: String, includeBucketTag: Boolean): Iterable<Tag> =
        if (includeBucketTag) {
            listOf(Tag.of(TAG_BUCKET, bucket.ifBlank { UNKNOWN }))
        } else {
            emptyList()
        }

    fun record(meterRegistry: MeterRegistry, meterName: String, tags: Tags, startedAt: Long) {
        Timer.builder(meterName)
            .tags(tags)
            .register(meterRegistry)
            .record(Duration.ofNanos(System.nanoTime() - startedAt))
    }

    suspend inline fun <T> record(
        meterRegistry: MeterRegistry,
        meterName: String,
        crossinline tagFactory: (String, Throwable?) -> Tags,
        crossinline block: suspend () -> T,
    ): T {
        val startedAt = System.nanoTime()
        return try {
            val result = block()
            record(meterRegistry, meterName, tagFactory(OUTCOME_SUCCESS, null), startedAt)
            result
        } catch (e: CancellationException) {
            record(meterRegistry, meterName, tagFactory(OUTCOME_CANCELLED, e), startedAt)
            throw e
        } catch (e: Exception) {
            record(meterRegistry, meterName, tagFactory(OUTCOME_FAILURE, e), startedAt)
            throw e
        }
    }

    inline fun <T> record(
        meterRegistry: MeterRegistry,
        meterName: String,
        crossinline tagFactory: (String, Throwable?) -> Tags,
        crossinline block: () -> T,
    ): T {
        val startedAt = System.nanoTime()
        return try {
            val result = block()
            record(meterRegistry, meterName, tagFactory(OUTCOME_SUCCESS, null), startedAt)
            result
        } catch (e: CancellationException) {
            record(meterRegistry, meterName, tagFactory(OUTCOME_CANCELLED, e), startedAt)
            throw e
        } catch (e: Exception) {
            record(meterRegistry, meterName, tagFactory(OUTCOME_FAILURE, e), startedAt)
            throw e
        }
    }

    private fun queueName(queueUrl: String?): String =
        queueUrl
            ?.substringAfterLast('/')
            ?.takeIf { it.isNotBlank() && it != queueUrl }
            ?: UNKNOWN

    private fun exceptionName(exception: Throwable?): String =
        exception?.javaClass?.simpleName ?: EXCEPTION_NONE
}
