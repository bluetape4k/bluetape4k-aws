package io.bluetape4k.aws.spring.sqs

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
            .tag("operation", "acknowledgement")
            .tag("outcome", "success")
            .tag("listener.id", "orders-listener")
            .tag("queue.name", "orders")
            .tag("ack.action", "ack")
            .timer()
        timer.shouldNotBeNull()
        timer.count() shouldBeEqualTo 1L
    }
}
