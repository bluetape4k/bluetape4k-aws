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

    @Test
    fun `record bounded batch delete operation path`() = runSuspendIO {
        val registry = SimpleMeterRegistry()
        val delegate = mockk<SqsOperations>()
        coEvery {
            delegate.deleteBatch("https://sqs.ap-northeast-2.amazonaws.com/000000000000/orders", any())
        } returns SqsBatchDeleteResult(listOf("entry-0"), emptyList())
        val operations = MicrometerSqsOperations(delegate, registry)

        operations.deleteBatch(
            "https://sqs.ap-northeast-2.amazonaws.com/000000000000/orders",
            listOf("receipt-1"),
        )

        val timer = registry.find(MicrometerSqsOperations.DEFAULT_METER_NAME)
            .tag(AwsMetricContract.TAG_OPERATION, MicrometerSqsOperations.OPERATION_DELETE_BATCH)
            .tag(AwsMetricContract.TAG_OUTCOME, AwsMetricContract.OUTCOME_SUCCESS)
            .tag(AwsMetricContract.TAG_QUEUE_NAME, "orders")
            .tag(MicrometerSqsOperations.TAG_BATCH_SIZE_BUCKET, "1")
            .tag(MicrometerSqsOperations.TAG_IMPLEMENTATION_PATH, "fallback")
            .timer()
        timer.shouldNotBeNull()
        timer.count() shouldBeEqualTo 1L
    }
}
