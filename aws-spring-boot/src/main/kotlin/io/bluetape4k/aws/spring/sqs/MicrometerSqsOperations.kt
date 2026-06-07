package io.bluetape4k.aws.spring.sqs

import io.bluetape4k.aws.spring.observability.AwsMicrometerSupport
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tags
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import software.amazon.awssdk.services.sqs.model.ChangeMessageVisibilityResponse
import software.amazon.awssdk.services.sqs.model.DeleteMessageResponse
import software.amazon.awssdk.services.sqs.model.QueueAttributeName
import software.amazon.awssdk.services.sqs.model.SendMessageResponse

/**
 * Micrometer-instrumented [SqsOperations] decorator.
 *
 * The decorator records low-cardinality operation timers and delegates all SQS
 * behavior to the wrapped [delegate].
 */
class MicrometerSqsOperations(
    private val delegate: SqsOperations,
    private val meterRegistry: MeterRegistry,
    private val meterName: String = DEFAULT_METER_NAME,
): SqsOperations {

    override suspend fun getQueueUrl(queueName: String): String =
        record("get_queue_url") {
            delegate.getQueueUrl(queueName)
        }

    override suspend fun createQueue(
        queueName: String,
        attributes: Map<QueueAttributeName, String>,
    ): String =
        record("create_queue") {
            delegate.createQueue(queueName, attributes)
        }

    override suspend fun createConfiguredQueue(queueName: String): String =
        record("create_configured_queue") {
            delegate.createConfiguredQueue(queueName)
        }

    override suspend fun send(queueUrl: String, body: String, delaySeconds: Int?): SendMessageResponse =
        record("send", queueUrl) {
            delegate.send(queueUrl, body, delaySeconds)
        }

    override suspend fun send(request: SqsSendRequest): SendMessageResponse =
        record("send", request.queueUrl) {
            delegate.send(request)
        }

    override suspend fun receive(
        queueUrl: String,
        maxMessages: Int,
        waitTimeSeconds: Int,
        visibilityTimeoutSeconds: Int?,
    ): List<SqsReceivedMessage> =
        record("receive", queueUrl) {
            delegate.receive(queueUrl, maxMessages, waitTimeSeconds, visibilityTimeoutSeconds)
        }

    override suspend fun delete(queueUrl: String, receiptHandle: String): DeleteMessageResponse =
        record("delete", queueUrl) {
            delegate.delete(queueUrl, receiptHandle)
        }

    override suspend fun changeVisibility(
        queueUrl: String,
        receiptHandle: String,
        timeoutSeconds: Int,
    ): ChangeMessageVisibilityResponse =
        record("change_visibility", queueUrl) {
            delegate.changeVisibility(queueUrl, receiptHandle, timeoutSeconds)
        }

    override fun receiveFlow(
        queueUrl: String,
        maxMessages: Int,
        waitTimeSeconds: Int,
        visibilityTimeoutSeconds: Int?,
    ): Flow<SqsReceivedMessage> = flow {
        record("receive_flow", queueUrl) {
            delegate.receiveFlow(queueUrl, maxMessages, waitTimeSeconds, visibilityTimeoutSeconds).collect { emit(it) }
        }
    }

    private suspend fun <T> record(
        operation: String,
        queueUrl: String? = null,
        block: suspend () -> T,
    ): T =
        AwsMicrometerSupport.recordSuspend(meterRegistry, meterName, { outcome, exception ->
            tags(operation, outcome, queueUrl, exception)
        }, block)

    private fun tags(operation: String, outcome: String, queueUrl: String?, exception: Throwable?): Tags =
        AwsMicrometerSupport.tags(
            service = "sqs",
            operation = operation,
            outcome = outcome,
            exception = exception,
            extras = listOf(AwsMicrometerSupport.queueNameTag(queueUrl)),
        )

    companion object {
        const val DEFAULT_METER_NAME: String = "bluetape4k.aws.sqs.operation"
    }
}
