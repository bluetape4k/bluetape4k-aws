package io.bluetape4k.aws.spring.modulith

import io.bluetape4k.aws.spring.sqs.SqsAcknowledgement
import io.bluetape4k.aws.spring.sqs.SqsAcknowledgementMode
import io.bluetape4k.aws.spring.sqs.SqsListener
import io.bluetape4k.aws.spring.sqs.SqsReceivedMessage
import kotlinx.coroutines.CancellationException

/** 정상 consumer outcome 뒤에만 SQS manual acknowledgement를 수행합니다. */
internal class AwsModulithSqsEventListener internal constructor(
    private val consumeEvent: suspend (SqsReceivedMessage) -> AwsModulithConsumeOutcome,
    private val metrics: AwsModulithMetrics = AwsModulithMetrics(),
) {
    constructor(consumer: AwsModulithSqsEventConsumer) : this(consumer::consume, consumer.metrics)

    @SqsListener(
        queue = "\${bluetape4k.aws.modulith.events.consumer.queue}",
        acknowledgementMode = SqsAcknowledgementMode.MANUAL,
    )
    @Suppress("TooGenericExceptionCaught")
    suspend fun onMessage(message: SqsReceivedMessage, acknowledgement: SqsAcknowledgement) {
        consumeEvent(message)
        try {
            acknowledgement.acknowledge()
        } catch (failure: Throwable) {
            metrics.record(
                AwsModulithMetricService.SQS,
                AwsModulithFailurePhase.ACK,
                AwsModulithMetricOutcome.FAILURE,
                AwsModulithDiagnosticCode.DISPATCH_ACK,
            )
            throw sanitizeAcknowledgementFailure(failure)
        }
    }

    private fun sanitizeAcknowledgementFailure(failure: Throwable): Throwable = when (failure) {
        is CancellationException -> failure
        is Error -> failure
        else -> AwsModulithAcknowledgementException()
    }
}
