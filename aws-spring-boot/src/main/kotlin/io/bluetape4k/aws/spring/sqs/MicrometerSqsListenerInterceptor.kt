package io.bluetape4k.aws.spring.sqs

import io.bluetape4k.aws.spring.observability.AwsMicrometerSupport
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tag
import io.micrometer.core.instrument.Tags
import java.util.concurrent.ConcurrentHashMap

/**
 * Micrometer [SqsListenerInterceptor] for receive, handler, and acknowledgement phases.
 */
class MicrometerSqsListenerInterceptor(
    private val meterRegistry: MeterRegistry,
    private val meterName: String = DEFAULT_METER_NAME,
): SqsListenerInterceptor {

    private val receiveStarts = ConcurrentHashMap<String, Long>()
    private val handleStarts = ConcurrentHashMap<String, Long>()
    private val acknowledgementStarts = ConcurrentHashMap<String, Long>()

    override suspend fun beforeReceive(listenerId: String, queueUrl: String) {
        receiveStarts[receiveKey(listenerId, queueUrl)] = System.nanoTime()
    }

    override suspend fun afterReceive(
        listenerId: String,
        queueUrl: String,
        messages: List<SqsReceivedMessage>,
        error: Throwable?,
    ) {
        val key = receiveKey(listenerId, queueUrl)
        val startedAt = receiveStarts.remove(key) ?: System.nanoTime()
        AwsMicrometerSupport.record(
            meterRegistry = meterRegistry,
            meterName = meterName,
            tags = tags(
                operation = "receive",
                outcome = if (error == null) "success" else "failure",
                listenerId = listenerId,
                queueUrl = queueUrl,
                exception = error,
                extras = listOf(Tag.of("message.count", messages.size.toString())),
            ),
            startedAt = startedAt,
        )
    }

    override suspend fun beforeHandle(context: SqsListenerInvocationContext) {
        handleStarts[contextKey(context)] = System.nanoTime()
    }

    override suspend fun afterHandle(context: SqsListenerInvocationContext, error: Throwable?) {
        val key = contextKey(context)
        val startedAt = handleStarts.remove(key) ?: System.nanoTime()
        AwsMicrometerSupport.record(
            meterRegistry = meterRegistry,
            meterName = meterName,
            tags = tags(
                operation = "handle",
                outcome = if (error == null) "success" else "failure",
                listenerId = context.listenerId,
                queueUrl = context.queueUrl,
                exception = error,
            ),
            startedAt = startedAt,
        )
    }

    override suspend fun beforeAcknowledgement(
        context: SqsListenerInvocationContext,
        action: SqsAcknowledgementAction,
    ) {
        acknowledgementStarts[ackKey(context, action)] = System.nanoTime()
    }

    override suspend fun afterAcknowledgement(
        context: SqsListenerInvocationContext,
        action: SqsAcknowledgementAction,
        error: Throwable?,
    ) {
        val key = ackKey(context, action)
        val startedAt = acknowledgementStarts.remove(key) ?: System.nanoTime()
        AwsMicrometerSupport.record(
            meterRegistry = meterRegistry,
            meterName = meterName,
            tags = tags(
                operation = "acknowledgement",
                outcome = if (error == null) "success" else "failure",
                listenerId = context.listenerId,
                queueUrl = context.queueUrl,
                exception = error,
                extras = listOf(Tag.of("ack.action", action.name.lowercase())),
            ),
            startedAt = startedAt,
        )
    }

    private fun tags(
        operation: String,
        outcome: String,
        listenerId: String,
        queueUrl: String,
        exception: Throwable?,
        extras: Iterable<Tag> = emptyList(),
    ): Tags =
        AwsMicrometerSupport.tags(
            service = "sqs",
            operation = operation,
            outcome = outcome,
            exception = exception,
            extras = listOf(
                AwsMicrometerSupport.listenerIdTag(listenerId),
                AwsMicrometerSupport.queueNameTag(queueUrl),
            ) + extras,
        )

    private fun receiveKey(listenerId: String, queueUrl: String): String =
        "$listenerId:$queueUrl"

    private fun contextKey(context: SqsListenerInvocationContext): String =
        "${context.listenerId}:${context.message.messageId}:${context.attempt}"

    private fun ackKey(context: SqsListenerInvocationContext, action: SqsAcknowledgementAction): String =
        "${contextKey(context)}:${action.name}"

    companion object {
        const val DEFAULT_METER_NAME: String = "bluetape4k.aws.sqs.listener"
    }
}
