package io.bluetape4k.aws.ktor.sqs

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.mockk.every
import io.mockk.mockk
import org.awaitility.kotlin.await
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import software.amazon.awssdk.services.sqs.SqsAsyncClient
import software.amazon.awssdk.services.sqs.model.ChangeMessageVisibilityRequest
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest
import software.amazon.awssdk.services.sqs.model.Message
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest
import software.amazon.awssdk.services.sqs.model.ReceiveMessageResponse
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.Consumer
import kotlin.reflect.KClass

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SqsConsumerRuntimeAdvancedTest {

    @Test
    fun `conversion failure delete policy acknowledges source message`() = runSuspendIO {
        val client = mockk<SqsAsyncClient>()
        val deleteCalls = AtomicInteger()
        val handlerCalls = AtomicInteger()
        val message = message("message-1", "receipt-1", "not-json")

        stubReceives(client, message)
        every { client.deleteMessage(any<Consumer<DeleteMessageRequest.Builder>>()) } answers {
            deleteCalls.incrementAndGet()
            completed(mockk())
        }

        val runtime = runtime(
            client = client,
            converter = ThrowingConverter,
            conversionFailurePolicy = SqsConversionFailurePolicy.Delete,
        ) {
            handlerCalls.incrementAndGet()
        }

        try {
            runtime.start()

            await.atMost(Duration.ofSeconds(5)).untilAsserted {
                deleteCalls.get() shouldBeEqualTo 1
                handlerCalls.get() shouldBeEqualTo 0
            }
        } finally {
            runtime.stop()
        }
    }

    @Test
    fun `manual nack and ack APIs call visibility and delete once`() = runSuspendIO {
        val client = mockk<SqsAsyncClient>()
        val visibilityTimeouts = CopyOnWriteArrayList<Int>()
        val deleteCalls = AtomicInteger()
        val message = message("message-1", "receipt-1", "manual")

        stubReceives(client, message)
        every { client.changeMessageVisibility(any<Consumer<ChangeMessageVisibilityRequest.Builder>>()) } answers {
            val request = ChangeMessageVisibilityRequest.builder().also(firstArg<Consumer<ChangeMessageVisibilityRequest.Builder>>()::accept).build()
            visibilityTimeouts += request.visibilityTimeout()
            completed(mockk())
        }
        every { client.deleteMessage(any<Consumer<DeleteMessageRequest.Builder>>()) } answers {
            deleteCalls.incrementAndGet()
            completed(mockk())
        }

        val runtime = runtime(
            client = client,
            deleteOnSuccess = false,
        ) {
            nack(0)
            ack()
            ack()
        }

        try {
            runtime.start()

            await.atMost(Duration.ofSeconds(5)).untilAsserted {
                visibilityTimeouts shouldBeEqualTo listOf(0)
                deleteCalls.get() shouldBeEqualTo 1
            }
        } finally {
            runtime.stop()
        }
    }

    @Test
    fun `interceptors preserve receive invoke and auto ack ordering`() = runSuspendIO {
        val client = mockk<SqsAsyncClient>()
        val events = CopyOnWriteArrayList<String>()
        val message = message("message-1", "receipt-1", "ordered")

        stubReceives(client, message)
        every { client.deleteMessage(any<Consumer<DeleteMessageRequest.Builder>>()) } returns completed(mockk())

        val runtime = runtime(
            client = client,
            interceptors = listOf(
                object: SqsConsumerInterceptor {
                    override suspend fun beforeReceive(queueUrl: String) {
                        events += "beforeReceive"
                    }

                    override suspend fun afterReceive(queueUrl: String, messages: List<Message>) {
                        if (messages.isNotEmpty()) events += "afterReceive"
                    }

                    override suspend fun beforeInvoke(context: SqsMessageContext) {
                        events += "beforeInvoke"
                    }

                    override suspend fun afterInvoke(context: SqsMessageContext) {
                        events += "afterInvoke"
                    }

                    override suspend fun beforeAck(context: SqsMessageContext) {
                        events += "beforeAck"
                    }

                    override suspend fun afterAck(context: SqsMessageContext) {
                        events += "afterAck"
                    }
                }
            ),
        ) {
            events += "handler"
        }

        try {
            runtime.start()

            await.atMost(Duration.ofSeconds(5)).untilAsserted {
                events.contains("afterAck").shouldBeTrue()
            }
            events.filter { it != "beforeReceive" }.take(5) shouldBeEqualTo
                listOf("afterReceive", "beforeInvoke", "handler", "afterInvoke", "beforeAck")
            events shouldContain "afterAck"
        } finally {
            runtime.stop()
        }
    }

    @Test
    fun `failure visibility strategy and observers record handler failure`() = runSuspendIO {
        val client = mockk<SqsAsyncClient>()
        val visibilityTimeouts = CopyOnWriteArrayList<Int>()
        val observations = CopyOnWriteArrayList<SqsConsumerObservation>()
        val message = message("message-1", "receipt-1", "fail")

        stubReceives(client, message)
        every { client.changeMessageVisibility(any<Consumer<ChangeMessageVisibilityRequest.Builder>>()) } answers {
            val request = ChangeMessageVisibilityRequest.builder().also(firstArg<Consumer<ChangeMessageVisibilityRequest.Builder>>()::accept).build()
            visibilityTimeouts += request.visibilityTimeout()
            completed(mockk())
        }

        val runtime = runtime(
            client = client,
            failureVisibilityStrategy = SqsFixedFailureVisibilityStrategy(timeoutSeconds = 7),
            observers = listOf(SqsConsumerObserver { observations += it }),
        ) {
            error("boom")
        }

        try {
            runtime.start()

            await.atMost(Duration.ofSeconds(5)).untilAsserted {
                visibilityTimeouts shouldBeEqualTo listOf(7)
                observations.map { it.operation } shouldContain KtorSqsObservationOperations.INVOKE
            }
            observations
                .first { it.operation == KtorSqsObservationOperations.INVOKE }
                .outcome shouldBeEqualTo KtorSqsObservationOutcomes.FAILURE
        } finally {
            runtime.stop()
        }
    }

    private fun runtime(
        client: SqsAsyncClient,
        deleteOnSuccess: Boolean = true,
        converter: SqsMessageConverter = StringOrByteArraySqsMessageConverter,
        conversionFailurePolicy: SqsConversionFailurePolicy = SqsConversionFailurePolicy.HandleAsFailure,
        failureVisibilityStrategy: SqsFailureVisibilityStrategy? = null,
        interceptors: List<SqsConsumerInterceptor> = emptyList(),
        observers: List<SqsConsumerObserver> = emptyList(),
        handler: suspend SqsMessageContext.(Any) -> Unit,
    ): SqsConsumerRuntime =
        SqsConsumerRuntime(
            SqsConsumerRuntimeConfig(
                sqsAsyncClient = client,
                queueUrl = "https://sqs.local/source",
                coroutines = 1,
                maxMessages = 1,
                waitTimeSeconds = 0,
                shutdownTimeout = Duration.ofMillis(100),
                deleteOnSuccess = deleteOnSuccess,
                converter = converter,
                conversionFailurePolicy = conversionFailurePolicy,
                failureVisibilityStrategy = failureVisibilityStrategy,
                interceptors = interceptors,
                observers = observers,
                messageType = String::class,
                messageHandler = handler,
            )
        )

    private fun stubReceives(client: SqsAsyncClient, firstMessage: Message) {
        val receiveCalls = AtomicInteger()
        every { client.receiveMessage(any<Consumer<ReceiveMessageRequest.Builder>>()) } answers {
            val messages = if (receiveCalls.incrementAndGet() == 1) listOf(firstMessage) else emptyList()
            completed(ReceiveMessageResponse.builder().messages(messages).build())
        }
    }

    private fun message(id: String, receiptHandle: String, body: String): Message =
        Message.builder()
            .messageId(id)
            .receiptHandle(receiptHandle)
            .body(body)
            .build()

    private fun <T> completed(value: T): CompletableFuture<T> =
        CompletableFuture.completedFuture(value)

    private object ThrowingConverter: SqsMessageConverter {
        override fun <T: Any> convert(message: Message, targetType: KClass<T>): T =
            throw IllegalArgumentException("conversion failed")
    }
}
