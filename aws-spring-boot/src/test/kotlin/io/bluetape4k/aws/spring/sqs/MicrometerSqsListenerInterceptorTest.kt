package io.bluetape4k.aws.spring.sqs

import io.bluetape4k.aws.spring.observability.AwsMetricContract
import io.bluetape4k.aws.spring.observability.AwsMicrometerSupport
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import software.amazon.awssdk.services.sqs.model.Message

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MicrometerSqsListenerInterceptorTest {

    @Test
    fun `record listener acknowledgement timer`() = runSuspendIO {
        val registry = SimpleMeterRegistry()
        val interceptor = MicrometerSqsListenerInterceptor(registry)
        val context = SqsListenerInvocationContext(
            listenerId = "orders-listener",
            queueUrl = "https://sqs.ap-northeast-2.amazonaws.com/000000000000/orders",
            message = SqsReceivedMessage(
                queueUrl = "https://sqs.ap-northeast-2.amazonaws.com/000000000000/orders",
                message = Message.builder().messageId("message-1").receiptHandle("receipt-1").build(),
            ),
            attempt = 1,
        )

        interceptor.beforeAcknowledgement(context, SqsAcknowledgementAction.ACK)
        interceptor.afterAcknowledgement(context, SqsAcknowledgementAction.ACK, null)

        val timer = registry.find(MicrometerSqsListenerInterceptor.DEFAULT_METER_NAME)
            .tag(AwsMetricContract.TAG_OPERATION, AwsMetricContract.OPERATION_ACKNOWLEDGEMENT)
            .tag(AwsMetricContract.TAG_OUTCOME, AwsMetricContract.OUTCOME_SUCCESS)
            .tag(AwsMetricContract.TAG_LISTENER_ID, "orders-listener")
            .tag(AwsMetricContract.TAG_QUEUE_NAME, "orders")
            .tag(AwsMetricContract.TAG_ACK_ACTION, AwsMetricContract.ACK_ACTION_ACK)
            .timer()
        timer.shouldNotBeNull()
        timer.count() shouldBeEqualTo 1L
    }

    @Test
    @Suppress("LongMethod")
    fun `record bounded batch metrics without ids`() = runSuspendIO {
        val registry = SimpleMeterRegistry()
        val interceptor = MicrometerSqsListenerInterceptor(registry)
        val queueUrl = "https://sqs.ap-northeast-2.amazonaws.com/000000000000/orders"
        val message = SqsReceivedMessage(
            queueUrl = queueUrl,
            message = Message.builder()
                .messageId("secret-message-id")
                .receiptHandle("secret-receipt-handle")
                .body("secret-body")
                .build(),
        )
        val context = SqsListenerInvocationContext("orders-listener", queueUrl, message, attempt = 1)
        val correlation = SqsListenerBatchCorrelation(generation = 7, pollerId = 2, batchSequence = 11)
        val result = SqsBatchAcknowledgementResult(
            operation = SqsBatchAcknowledgementOperation.ACKNOWLEDGE,
            status = SqsBatchAcknowledgementStatus.PARTIAL_FAILURE,
            successfulMessageIds = listOf("secret-message-id"),
            failed = listOf(SqsBatchAcknowledgementFailure("secret-message-id-2", "failed", "hidden", false)),
        )

        interceptor.beforeReceive("orders-listener", queueUrl, correlation)
        interceptor.afterReceive("orders-listener", queueUrl, listOf(message), null, correlation)
        interceptor.beforeBatchHandle(context, correlation, batchSize = 2)
        interceptor.afterBatchHandle(context, null, correlation, batchSize = 2)
        interceptor.beforeAcknowledgement(context, SqsAcknowledgementAction.ACK, correlation, batchSize = 2)
        interceptor.onBatchAcknowledgementResult(
            context,
            SqsAcknowledgementAction.ACK,
            result,
            correlation,
            batchSize = 2,
        )
        interceptor.afterAcknowledgement(context, SqsAcknowledgementAction.ACK, null, correlation, batchSize = 2)
        interceptor.onBatchRetry(context, correlation, batchSize = 2, attempt = 2, error = null)
        interceptor.onBatchCancellation(context, correlation, batchSize = 2)

        registry.get(MicrometerSqsListenerInterceptor.BATCH_INVOCATIONS)
            .tag(MicrometerSqsListenerInterceptor.TAG_BATCH_SIZE_BUCKET, "1")
            .tag(AwsMicrometerSupport.TAG_LISTENER_ID, "orders-listener")
            .tag(AwsMicrometerSupport.TAG_QUEUE_NAME, "orders")
            .counter()
            .count() shouldBeEqualTo 1.0
        registry.get(MicrometerSqsListenerInterceptor.BATCH_PARTIAL_FAILURES)
            .tag(MicrometerSqsListenerInterceptor.TAG_BATCH_SIZE_BUCKET, "2-5")
            .tag(AwsMicrometerSupport.TAG_LISTENER_ID, "orders-listener")
            .tag(AwsMicrometerSupport.TAG_QUEUE_NAME, "orders")
            .counter()
            .count() shouldBeEqualTo 1.0
        registry.get(MicrometerSqsListenerInterceptor.BATCH_HANDLER_DURATION)
            .tag(MicrometerSqsListenerInterceptor.TAG_BATCH_SIZE_BUCKET, "2-5")
            .tag(AwsMicrometerSupport.TAG_LISTENER_ID, "orders-listener")
            .tag(AwsMicrometerSupport.TAG_QUEUE_NAME, "orders")
            .timer()
            .count() shouldBeEqualTo 1L

        registry.meters.forEach { meter ->
            meter.id.tags.none { tag ->
                tag.value.contains("secret-message-id") ||
                    tag.value.contains("secret-receipt-handle") ||
                    tag.value.contains("secret-body")
            }.shouldBeEqualTo(true)
        }
    }
}
