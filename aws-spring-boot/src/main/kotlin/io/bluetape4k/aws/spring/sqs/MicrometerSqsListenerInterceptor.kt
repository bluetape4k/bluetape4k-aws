package io.bluetape4k.aws.spring.sqs

import io.bluetape4k.aws.spring.observability.AwsMicrometerSupport
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.Tag
import io.micrometer.core.instrument.Tags
import io.micrometer.core.instrument.Timer
import io.micrometer.core.instrument.DistributionSummary
import java.util.concurrent.ConcurrentHashMap

/**
 * 수신, 핸들러, 확인 단계용 Micrometer [SqsListenerInterceptor]입니다.
 */
@Suppress("TooManyFunctions")
class MicrometerSqsListenerInterceptor(
    private val meterRegistry: MeterRegistry,
    private val meterName: String = DEFAULT_METER_NAME,
): SqsListenerInterceptor {

    private val receiveStarts = ConcurrentHashMap<String, Long>()
    private val handleStarts = ConcurrentHashMap<String, Long>()
    private val acknowledgementStarts = ConcurrentHashMap<String, Long>()
    private data class BatchMetricKey(
        val listenerId: String,
        val queueUrl: String,
        val correlation: SqsListenerBatchCorrelation,
    )

    private val batchReceiveStarts = ConcurrentHashMap<BatchMetricKey, Long>()
    private val batchHandleStarts = ConcurrentHashMap<BatchMetricKey, Long>()
    private val batchAcknowledgementStarts = ConcurrentHashMap<BatchMetricKey, Long>()

    override suspend fun beforeReceive(listenerId: String, queueUrl: String) {
        receiveStarts[receiveKey(listenerId, queueUrl)] = System.nanoTime()
    }

    override suspend fun beforeReceive(
        listenerId: String,
        queueUrl: String,
        correlation: SqsListenerBatchCorrelation,
    ) {
        batchReceiveStarts[batchMetricKey(listenerId, queueUrl, correlation)] = System.nanoTime()
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
                operation = OPERATION_RECEIVE,
                outcome = outcome(error),
                listenerId = listenerId,
                queueUrl = queueUrl,
                exception = error,
                extras = listOf(Tag.of(TAG_MESSAGE_COUNT, messages.size.toString())),
            ),
            startedAt = startedAt,
        )
    }

    override suspend fun afterReceive(
        listenerId: String,
        queueUrl: String,
        messages: List<SqsReceivedMessage>,
        error: Throwable?,
        correlation: SqsListenerBatchCorrelation,
    ) {
        batchReceiveStarts.remove(batchMetricKey(listenerId, queueUrl, correlation))
        counter(
            BATCH_INVOCATIONS,
            batchTags(listenerId, queueUrl, OPERATION_BATCH_RECEIVE, outcome(error), messages.size),
        ).increment()
        messages.mapNotNull { redeliveryAgeMillis(it) }.maxOrNull()?.let { ageMillis ->
            DistributionSummary.builder(BATCH_REDELIVERY_AGE)
                .tags(batchTags(listenerId, queueUrl, null, outcome(error), messages.size))
                .register(meterRegistry)
                .record(ageMillis.toDouble())
        }
    }

    override suspend fun beforeHandle(context: SqsListenerInvocationContext) {
        handleStarts[contextKey(context)] = System.nanoTime()
    }

    override suspend fun beforeBatchHandle(
        context: SqsListenerInvocationContext,
        correlation: SqsListenerBatchCorrelation,
        batchSize: Int,
    ) {
        batchHandleStarts[batchMetricKey(context.listenerId, context.queueUrl, correlation)] = System.nanoTime()
    }

    override suspend fun afterHandle(context: SqsListenerInvocationContext, error: Throwable?) {
        val key = contextKey(context)
        val startedAt = handleStarts.remove(key) ?: System.nanoTime()
        AwsMicrometerSupport.record(
            meterRegistry = meterRegistry,
            meterName = meterName,
            tags = tags(
                operation = OPERATION_HANDLE,
                outcome = outcome(error),
                listenerId = context.listenerId,
                queueUrl = context.queueUrl,
                exception = error,
            ),
            startedAt = startedAt,
        )
    }

    override suspend fun afterBatchHandle(
        context: SqsListenerInvocationContext,
        error: Throwable?,
        correlation: SqsListenerBatchCorrelation,
        batchSize: Int,
    ) {
        val startedAt = batchHandleStarts.remove(
            batchMetricKey(context.listenerId, context.queueUrl, correlation)
        ) ?: System.nanoTime()
        Timer.builder(BATCH_HANDLER_DURATION)
            .tags(batchTags(context.listenerId, context.queueUrl, null, outcome(error), batchSize))
            .register(meterRegistry)
            .record(java.time.Duration.ofNanos(System.nanoTime() - startedAt))
    }

    override suspend fun beforeAcknowledgement(
        context: SqsListenerInvocationContext,
        action: SqsAcknowledgementAction,
    ) {
        acknowledgementStarts[ackKey(context, action)] = System.nanoTime()
    }

    override suspend fun beforeAcknowledgement(
        context: SqsListenerInvocationContext,
        action: SqsAcknowledgementAction,
        correlation: SqsListenerBatchCorrelation,
        batchSize: Int,
    ) {
        batchAcknowledgementStarts[
            batchMetricKey(context.listenerId, context.queueUrl, correlation)
        ] = System.nanoTime()
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
                operation = OPERATION_ACKNOWLEDGEMENT,
                outcome = outcome(error),
                listenerId = context.listenerId,
                queueUrl = context.queueUrl,
                exception = error,
                extras = listOf(Tag.of(TAG_ACK_ACTION, action.name.lowercase())),
            ),
            startedAt = startedAt,
        )
    }

    override suspend fun afterAcknowledgement(
        context: SqsListenerInvocationContext,
        action: SqsAcknowledgementAction,
        error: Throwable?,
        correlation: SqsListenerBatchCorrelation,
        batchSize: Int,
    ) {
        batchAcknowledgementStarts.remove(batchMetricKey(context.listenerId, context.queueUrl, correlation))
        counter(
            BATCH_ACKNOWLEDGEMENTS,
            batchTags(
                context.listenerId,
                context.queueUrl,
                action.name.lowercase(),
                outcome(error),
                batchSize,
                implementationPath = "fallback",
            ),
        ).increment()
    }

    override suspend fun onBatchAcknowledgementResult(
        context: SqsListenerInvocationContext,
        action: SqsAcknowledgementAction,
        result: SqsBatchAcknowledgementResult,
        correlation: SqsListenerBatchCorrelation,
        batchSize: Int,
    ) {
        if (result.status == SqsBatchAcknowledgementStatus.PARTIAL_FAILURE) {
            counter(
                BATCH_PARTIAL_FAILURES,
                batchTags(
                    context.listenerId,
                    context.queueUrl,
                    action.name.lowercase(),
                    "partial",
                    batchSize,
                    implementationPath = "fallback",
                ),
            ).increment()
        }
        if ((action == SqsAcknowledgementAction.NACK || action == SqsAcknowledgementAction.CHANGE_VISIBILITY) &&
            result.failed.isNotEmpty()
        ) {
            counter(
                BATCH_VISIBILITY_FAILURES,
                batchTags(
                    context.listenerId,
                    context.queueUrl,
                    action.name.lowercase(),
                    "failure",
                    batchSize,
                    implementationPath = "fallback",
                ),
            ).increment()
        }
    }

    override suspend fun onBatchRetry(
        context: SqsListenerInvocationContext,
        correlation: SqsListenerBatchCorrelation,
        batchSize: Int,
        attempt: Int,
        error: Throwable?,
    ) {
        counter(
            BATCH_RETRY,
            batchTags(context.listenerId, context.queueUrl, "retry", outcome(error), batchSize),
        ).increment()
    }

    override suspend fun onBatchCancellation(
        context: SqsListenerInvocationContext,
        correlation: SqsListenerBatchCorrelation,
        batchSize: Int,
    ) {
        counter(
            BATCH_CANCELLATIONS,
            batchTags(
                context.listenerId,
                context.queueUrl,
                "cancellation",
                AwsMicrometerSupport.OUTCOME_CANCELLED,
                batchSize,
            ),
        ).increment()
    }

    private fun counter(name: String, tags: Tags): Counter =
        Counter.builder(name).tags(tags).register(meterRegistry)

    private fun redeliveryAgeMillis(message: SqsReceivedMessage): Long? =
        message.attributes[software.amazon.awssdk.services.sqs.model.MessageSystemAttributeName.APPROXIMATE_FIRST_RECEIVE_TIMESTAMP]
            ?.toLongOrNull()
            ?.let { timestamp -> (System.currentTimeMillis() - timestamp).coerceAtLeast(0L) }

    private fun batchTags(
        listenerId: String,
        queueUrl: String,
        operation: String?,
        outcome: String,
        batchSize: Int,
        implementationPath: String? = null,
    ): Tags = Tags.of(
        AwsMicrometerSupport.listenerIdTag(listenerId),
        AwsMicrometerSupport.queueNameTag(queueUrl),
        Tag.of(TAG_BATCH_SIZE_BUCKET, batchSizeBucket(batchSize)),
    ).and(
        listOfNotNull(
            operation?.let { Tag.of(TAG_OPERATION, it) },
            Tag.of(TAG_OUTCOME, outcome),
            implementationPath?.let { Tag.of(TAG_IMPLEMENTATION_PATH, it) },
        ),
    )

    private fun tags(
        operation: String,
        outcome: String,
        listenerId: String,
        queueUrl: String,
        exception: Throwable?,
        extras: Iterable<Tag> = emptyList(),
    ): Tags =
        AwsMicrometerSupport.tags(
            service = AwsMicrometerSupport.SERVICE_SQS,
            operation = operation,
            outcome = outcome,
            exception = exception,
            extras = listOf(
                AwsMicrometerSupport.listenerIdTag(listenerId),
                AwsMicrometerSupport.queueNameTag(queueUrl),
            ) + extras,
        )

    private fun outcome(error: Throwable?): String =
        if (error == null) {
            AwsMicrometerSupport.OUTCOME_SUCCESS
        } else {
            AwsMicrometerSupport.OUTCOME_FAILURE
        }

    private fun receiveKey(listenerId: String, queueUrl: String): String =
        "$listenerId:$queueUrl"

    private fun contextKey(context: SqsListenerInvocationContext): String =
        "${context.listenerId}:${context.message.messageId}:${context.attempt}"

    private fun ackKey(context: SqsListenerInvocationContext, action: SqsAcknowledgementAction): String =
        "${contextKey(context)}:${action.name}"

    private fun batchMetricKey(
        listenerId: String,
        queueUrl: String,
        correlation: SqsListenerBatchCorrelation,
    ): BatchMetricKey = BatchMetricKey(listenerId, queueUrl, correlation)

    companion object {
        const val DEFAULT_METER_NAME: String = "bluetape4k.aws.sqs.listener"
        const val OPERATION_RECEIVE: String = "receive"
        const val OPERATION_HANDLE: String = "handle"
        const val OPERATION_ACKNOWLEDGEMENT: String = "acknowledgement"
        const val TAG_MESSAGE_COUNT: String = "message.count"
        const val TAG_ACK_ACTION: String = "ack.action"
        const val BATCH_INVOCATIONS: String = "bluetape4k.sqs.batch.invocations"
        const val BATCH_ACKNOWLEDGEMENTS: String = "bluetape4k.sqs.batch.acknowledgements"
        const val BATCH_HANDLER_DURATION: String = "bluetape4k.sqs.batch.handler.duration"
        const val BATCH_RETRY: String = "bluetape4k.sqs.batch.retry"
        const val BATCH_PARTIAL_FAILURES: String = "bluetape4k.sqs.batch.partial.failures"
        const val BATCH_VISIBILITY_FAILURES: String = "bluetape4k.sqs.batch.visibility.failures"
        const val BATCH_CANCELLATIONS: String = "bluetape4k.sqs.batch.cancellations"
        const val BATCH_REDELIVERY_AGE: String = "bluetape4k.sqs.batch.redelivery.age"
        const val TAG_BATCH_SIZE_BUCKET: String = "batch.size.bucket"
        const val TAG_IMPLEMENTATION_PATH: String = "implementation.path"
        const val TAG_OPERATION: String = "operation"
        const val TAG_OUTCOME: String = "outcome"
        const val OPERATION_BATCH_RECEIVE: String = "receive"

        private const val SMALL_BATCH_MIN_SIZE: Int = 2
        private const val SMALL_BATCH_MAX_SIZE: Int = 5

        private fun batchSizeBucket(size: Int): String = when (size) {
            0 -> "0"
            1 -> "1"
            in SMALL_BATCH_MIN_SIZE..SMALL_BATCH_MAX_SIZE -> "2-5"
            else -> "6-10"
        }
    }
}
