package io.bluetape4k.aws.ktor.sqs

import io.bluetape4k.aws.ktor.observability.KtorMicrometerSupport
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import software.amazon.awssdk.services.sqs.SqsAsyncClient
import software.amazon.awssdk.services.sqs.model.SendMessageRequest
import software.amazon.awssdk.services.sqs.model.SendMessageResponse
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.function.Consumer

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MicrometerSqsConsumerObserverTest {

    @Test
    fun `bridge consumer observation to Micrometer timer`() {
        val registry = SimpleMeterRegistry()
        val observer = MicrometerSqsConsumerObserver(registry)

        observer.observe(
            SqsConsumerObservation(
                operation = KtorSqsObservationOperations.RECEIVE,
                outcome = KtorSqsObservationOutcomes.SUCCESS,
                queueUrl = "https://sqs.ap-northeast-2.amazonaws.com/000000000000/orders",
                duration = Duration.ofMillis(3),
            )
        )

        val timer = registry.find(MicrometerSqsConsumerObserver.DEFAULT_METER_NAME)
            .tag(KtorMicrometerSupport.TAG_OPERATION, KtorSqsObservationOperations.RECEIVE)
            .tag(KtorMicrometerSupport.TAG_OUTCOME, KtorSqsObservationOutcomes.SUCCESS)
            .tag(KtorMicrometerSupport.TAG_QUEUE_NAME, "orders")
            .timer()
        timer.shouldNotBeNull()
        timer.count() shouldBeEqualTo 1L
    }

    @Test
    fun `send emits observation for Micrometer bridge`() = runSuspendIO {
        val client = mockk<SqsAsyncClient>()
        val observations = CopyOnWriteArrayList<SqsConsumerObservation>()
        every { client.sendMessage(any<Consumer<SendMessageRequest.Builder>>()) } returns
            CompletableFuture.completedFuture(SendMessageResponse.builder().messageId("message-1").build())
        val runtime = SqsConsumerRuntime(
            SqsConsumerRuntimeConfig(
                sqsAsyncClient = client,
                queueUrl = "https://sqs.ap-northeast-2.amazonaws.com/000000000000/orders",
                observers = listOf(SqsConsumerObserver { observations += it }),
                messageType = String::class,
                messageHandler = {},
            )
        )

        runtime.send("hello", "https://sqs.ap-northeast-2.amazonaws.com/000000000000/orders")

        observations.single().operation shouldBeEqualTo KtorSqsObservationOperations.SEND
        observations.single().outcome shouldBeEqualTo KtorSqsObservationOutcomes.SUCCESS
    }
}
