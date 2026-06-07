package io.bluetape4k.aws.ktor.observability

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tag
import io.micrometer.core.instrument.Tags
import io.micrometer.core.instrument.Timer
import kotlinx.coroutines.CancellationException
import java.time.Duration

internal object KtorMicrometerSupport {

    const val SERVICE_S3: String = "s3"
    const val SERVICE_SQS: String = "sqs"

    const val TAG_SERVICE: String = "service"
    const val TAG_OPERATION: String = "operation"
    const val TAG_OUTCOME: String = "outcome"
    const val TAG_EXCEPTION: String = "exception"
    const val TAG_QUEUE_NAME: String = "queue.name"
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
        exception: String = EXCEPTION_NONE,
        extras: Iterable<Tag> = emptyList(),
    ): Tags =
        Tags.of(
            TAG_SERVICE, service,
            TAG_OPERATION, operation,
            TAG_OUTCOME, outcome,
            TAG_EXCEPTION, exception.ifBlank { UNKNOWN }.substringAfterLast('.'),
        ).and(extras)

    fun queueNameTag(queueUrl: String?): Tag =
        Tag.of(TAG_QUEUE_NAME, queueName(queueUrl))

    fun bucketTag(bucket: String, includeBucketTag: Boolean): Iterable<Tag> =
        if (includeBucketTag) {
            listOf(Tag.of(TAG_BUCKET, bucket.ifBlank { UNKNOWN }))
        } else {
            emptyList()
        }

    fun record(meterRegistry: MeterRegistry, meterName: String, tags: Tags, duration: Duration) {
        Timer.builder(meterName)
            .tags(tags)
            .register(meterRegistry)
            .record(duration)
    }

    suspend fun <T> recordSuspend(
        meterRegistry: MeterRegistry,
        meterName: String,
        tagFactory: (String, String) -> Tags,
        block: suspend () -> T,
    ): T {
        val startedAt = System.nanoTime()
        return try {
            val result = block()
            record(meterRegistry, meterName, tagFactory(OUTCOME_SUCCESS, EXCEPTION_NONE), durationSince(startedAt))
            result
        } catch (e: CancellationException) {
            record(meterRegistry, meterName, tagFactory(OUTCOME_CANCELLED, e::class.qualifiedName.orEmpty()), durationSince(startedAt))
            throw e
        } catch (e: Exception) {
            record(meterRegistry, meterName, tagFactory(OUTCOME_FAILURE, e::class.qualifiedName.orEmpty()), durationSince(startedAt))
            throw e
        }
    }

    fun <T> recordBlocking(
        meterRegistry: MeterRegistry,
        meterName: String,
        tagFactory: (String, String) -> Tags,
        block: () -> T,
    ): T {
        val startedAt = System.nanoTime()
        return try {
            val result = block()
            record(meterRegistry, meterName, tagFactory(OUTCOME_SUCCESS, EXCEPTION_NONE), durationSince(startedAt))
            result
        } catch (e: CancellationException) {
            record(meterRegistry, meterName, tagFactory(OUTCOME_CANCELLED, e::class.qualifiedName.orEmpty()), durationSince(startedAt))
            throw e
        } catch (e: Exception) {
            record(meterRegistry, meterName, tagFactory(OUTCOME_FAILURE, e::class.qualifiedName.orEmpty()), durationSince(startedAt))
            throw e
        }
    }

    private fun durationSince(startedAt: Long): Duration =
        Duration.ofNanos(System.nanoTime() - startedAt)

    private fun queueName(queueUrl: String?): String =
        queueUrl
            ?.substringAfterLast('/')
            ?.takeIf { it.isNotBlank() && it != queueUrl }
            ?: UNKNOWN
}
