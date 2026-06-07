package io.bluetape4k.aws.ktor.observability

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tag
import io.micrometer.core.instrument.Tags
import io.micrometer.core.instrument.Timer
import kotlinx.coroutines.CancellationException
import java.time.Duration

internal object KtorMicrometerSupport {

    fun tags(
        service: String,
        operation: String,
        outcome: String,
        exception: String = "none",
        extras: Iterable<Tag> = emptyList(),
    ): Tags =
        Tags.of(
            "service", service,
            "operation", operation,
            "outcome", outcome,
            "exception", exception.ifBlank { "unknown" }.substringAfterLast('.'),
        ).and(extras)

    fun queueNameTag(queueUrl: String?): Tag =
        Tag.of("queue.name", queueName(queueUrl))

    fun bucketTag(bucket: String, includeBucketTag: Boolean): Iterable<Tag> =
        if (includeBucketTag) {
            listOf(Tag.of("bucket", bucket.ifBlank { "unknown" }))
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
            record(meterRegistry, meterName, tagFactory("success", "none"), durationSince(startedAt))
            result
        } catch (e: CancellationException) {
            record(meterRegistry, meterName, tagFactory("cancelled", e::class.qualifiedName.orEmpty()), durationSince(startedAt))
            throw e
        } catch (e: Exception) {
            record(meterRegistry, meterName, tagFactory("failure", e::class.qualifiedName.orEmpty()), durationSince(startedAt))
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
            record(meterRegistry, meterName, tagFactory("success", "none"), durationSince(startedAt))
            result
        } catch (e: CancellationException) {
            record(meterRegistry, meterName, tagFactory("cancelled", e::class.qualifiedName.orEmpty()), durationSince(startedAt))
            throw e
        } catch (e: Exception) {
            record(meterRegistry, meterName, tagFactory("failure", e::class.qualifiedName.orEmpty()), durationSince(startedAt))
            throw e
        }
    }

    private fun durationSince(startedAt: Long): Duration =
        Duration.ofNanos(System.nanoTime() - startedAt)

    private fun queueName(queueUrl: String?): String =
        queueUrl
            ?.substringAfterLast('/')
            ?.takeIf { it.isNotBlank() && it != queueUrl }
            ?: "unknown"
}
