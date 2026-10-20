package io.bluetape4k.aws.ktor.sqs

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.aws.ktor.observability.KtorMetricContract
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.mockk
import aws.sdk.kotlin.services.sqs.SqsClient
import aws.sdk.kotlin.services.sqs.model.SendMessageRequest
import aws.sdk.kotlin.services.sqs.model.SendMessageResponse
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.time.Duration
import java.util.concurrent.ConcurrentLinkedQueue

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MicrometerSqsConsumerObserverTest {

    companion object: KLogging()

    private val client = mockk<SqsClient>()

    @BeforeEach
    fun beforeEach() {
        clearMocks(client)
    }

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
            .tag(KtorMetricContract.TAG_OPERATION, KtorMetricContract.OPERATION_RECEIVE)
            .tag(KtorMetricContract.TAG_OUTCOME, KtorMetricContract.OUTCOME_SUCCESS)
            .tag(KtorMetricContract.TAG_QUEUE_NAME, "orders")
            .timer()

        timer.shouldNotBeNull()
        timer.count() shouldBeEqualTo 1L
    }

    @Test
    fun `send emits observation for Micrometer bridge`() = runSuspendIO {
        val observations = ConcurrentLinkedQueue<SqsConsumerObservation>()
        coEvery { client.sendMessage(any<SendMessageRequest>()) } returns SendMessageResponse {
            messageId = "message-1"
            sequenceNumber = "fifo-sequence-42"
            md5OfMessageBody = "body-md5"
        }

        val runtime = SqsConsumerRuntime(
            SqsConsumerRuntimeConfig(
                sqsClient = client,
                queueUrl = "https://sqs.ap-northeast-2.amazonaws.com/000000000000/orders",
                observers = listOf(SqsConsumerObserver { observations += it }),
                messageType = String::class,
                messageHandler = {},
            )
        )

        val response = runtime.send("hello", "https://sqs.ap-northeast-2.amazonaws.com/000000000000/orders")

        response.messageId shouldBeEqualTo "message-1"
        response.sequenceNumber shouldBeEqualTo "fifo-sequence-42"
        response.md5OfMessageBody shouldBeEqualTo "body-md5"

        log.debug { "observation=${observations.single()}" }
        observations.single().operation shouldBeEqualTo KtorSqsObservationOperations.SEND
        observations.single().outcome shouldBeEqualTo KtorSqsObservationOutcomes.SUCCESS
    }
}
