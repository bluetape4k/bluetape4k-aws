package io.bluetape4k.aws.spring.sqs

import io.bluetape4k.aws.spring.observability.AwsMetricContract
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.coEvery
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import software.amazon.awssdk.services.sqs.model.SendMessageResponse

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MicrometerSqsOperationsTest {

    @Test
    fun `record SQS send operation timer`() = runSuspendIO {
        val registry = SimpleMeterRegistry()
        val delegate = mockk<SqsOperations>()
        coEvery {
            delegate.send("https://sqs.ap-northeast-2.amazonaws.com/000000000000/orders", "body", null)
        } returns SendMessageResponse.builder().messageId("message-1").build()
        val operations = MicrometerSqsOperations(delegate, registry)

        operations.send("https://sqs.ap-northeast-2.amazonaws.com/000000000000/orders", "body")

        val timer = registry.find(MicrometerSqsOperations.DEFAULT_METER_NAME)
            .tag(AwsMetricContract.TAG_OPERATION, AwsMetricContract.OPERATION_SEND)
            .tag(AwsMetricContract.TAG_OUTCOME, AwsMetricContract.OUTCOME_SUCCESS)
            .tag(AwsMetricContract.TAG_QUEUE_NAME, "orders")
            .timer()
        timer.shouldNotBeNull()
        timer.count() shouldBeEqualTo 1L
    }
}
