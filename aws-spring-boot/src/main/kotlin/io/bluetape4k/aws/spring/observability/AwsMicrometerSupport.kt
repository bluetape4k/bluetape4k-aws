package io.bluetape4k.aws.spring.observability

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tag
import io.micrometer.core.instrument.Tags
import io.micrometer.core.instrument.Timer
import kotlinx.coroutines.CancellationException
import java.time.Duration

internal object AwsMicrometerSupport {

    fun tags(
        service: String,
        operation: String,
        outcome: String,
        exception: Throwable? = null,
        extras: Iterable<Tag> = emptyList(),
    ): Tags =
        Tags.of(
            "service", service,
            "operation", operation,
            "outcome", outcome,
            "exception", exceptionName(exception),
        ).and(extras)

    fun queueNameTag(queueUrl: String?): Tag =
        Tag.of("queue.name", queueName(queueUrl))

    fun listenerIdTag(listenerId: String): Tag =
        Tag.of("listener.id", listenerId.ifBlank { "unknown" })

    fun bucketTag(bucket: String, includeBucketTag: Boolean): Iterable<Tag> =
        if (includeBucketTag) {
            listOf(Tag.of("bucket", bucket.ifBlank { "unknown" }))
        } else {
            emptyList()
        }

    fun record(meterRegistry: MeterRegistry, meterName: String, tags: Tags, startedAt: Long) {
        Timer.builder(meterName)
            .tags(tags)
            .register(meterRegistry)
            .record(Duration.ofNanos(System.nanoTime() - startedAt))
    }

    suspend fun <T> recordSuspend(
        meterRegistry: MeterRegistry,
        meterName: String,
        tagFactory: (String, Throwable?) -> Tags,
        block: suspend () -> T,
    ): T {
        val startedAt = System.nanoTime()
        return try {
            val result = block()
            record(meterRegistry, meterName, tagFactory("success", null), startedAt)
            result
        } catch (e: CancellationException) {
            record(meterRegistry, meterName, tagFactory("cancelled", e), startedAt)
            throw e
        } catch (e: Exception) {
            record(meterRegistry, meterName, tagFactory("failure", e), startedAt)
            throw e
        }
    }

    fun <T> recordBlocking(
        meterRegistry: MeterRegistry,
        meterName: String,
        tagFactory: (String, Throwable?) -> Tags,
        block: () -> T,
    ): T {
        val startedAt = System.nanoTime()
        return try {
            val result = block()
            record(meterRegistry, meterName, tagFactory("success", null), startedAt)
            result
        } catch (e: CancellationException) {
            record(meterRegistry, meterName, tagFactory("cancelled", e), startedAt)
            throw e
        } catch (e: Exception) {
            record(meterRegistry, meterName, tagFactory("failure", e), startedAt)
            throw e
        }
    }

    private fun queueName(queueUrl: String?): String =
        queueUrl
            ?.substringAfterLast('/')
            ?.takeIf { it.isNotBlank() && it != queueUrl }
            ?: "unknown"

    private fun exceptionName(exception: Throwable?): String =
        exception?.javaClass?.simpleName ?: "none"
}
