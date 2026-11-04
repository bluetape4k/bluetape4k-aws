package io.bluetape4k.aws.ktor.sqs

import aws.sdk.kotlin.services.sqs.SqsClient
import aws.sdk.kotlin.services.sqs.model.ChangeMessageVisibilityRequest
import aws.sdk.kotlin.services.sqs.model.ChangeMessageVisibilityResponse
import aws.sdk.kotlin.services.sqs.model.DeleteMessageRequest
import aws.sdk.kotlin.services.sqs.model.DeleteMessageResponse
import aws.sdk.kotlin.services.sqs.model.Message
import aws.sdk.kotlin.services.sqs.model.ReceiveMessageRequest
import aws.sdk.kotlin.services.sqs.model.ReceiveMessageResponse
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.javatimes.millis
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.mockk
import org.awaitility.kotlin.atMost
import org.awaitility.kotlin.await
import org.awaitility.kotlin.untilAsserted
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import kotlin.reflect.KClass
import kotlin.time.Duration.Companion.seconds

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SqsConsumerRuntimeAdvancedTest {

    companion object: KLoggingChannel()

    private val client = mockk<SqsClient>()

    @BeforeEach
    fun beforeEach() {
        clearMocks(client)
    }

    @Test
    fun `conversion failure delete policy acknowledges source message`() = runSuspendIO {

        val deleteCalls = AtomicInteger()
        val handlerCalls = AtomicInteger()
        val message = message("message-1", "receipt-1", "not-json")

        stubReceives(client, message)
        coEvery { client.deleteMessage(any<DeleteMessageRequest>()) } coAnswers {
            deleteCalls.incrementAndGet()
            DeleteMessageResponse {}
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

            await atMost 5.seconds untilAsserted {
                deleteCalls.get() shouldBeEqualTo 1
                handlerCalls.get() shouldBeEqualTo 0
            }
        } finally {
            runtime.stop()
        }
    }

    @Test
    fun `manual nack and ack APIs call visibility and delete once`() = runSuspendIO {
        val visibilityRequests = ConcurrentLinkedQueue<ChangeMessageVisibilityRequest>()
        val deleteRequests = ConcurrentLinkedQueue<DeleteMessageRequest>()
        val message = message("message-1", "receipt-1", "manual")

        stubReceives(client, message)

        coEvery { client.changeMessageVisibility(any<ChangeMessageVisibilityRequest>()) } coAnswers {
            visibilityRequests += firstArg<ChangeMessageVisibilityRequest>()
            ChangeMessageVisibilityResponse {}
        }
        coEvery { client.deleteMessage(any<DeleteMessageRequest>()) } coAnswers {
            deleteRequests += firstArg<DeleteMessageRequest>()
            DeleteMessageResponse {}
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

            await atMost 5.seconds untilAsserted {
                visibilityRequests.map { it.visibilityTimeout } shouldBeEqualTo listOf(0)
                deleteRequests.size shouldBeEqualTo 1
            }
            visibilityRequests.single().queueUrl shouldBeEqualTo "https://sqs.local/source"
            visibilityRequests.single().receiptHandle shouldBeEqualTo "receipt-1"
            deleteRequests.single().queueUrl shouldBeEqualTo "https://sqs.local/source"
            deleteRequests.single().receiptHandle shouldBeEqualTo "receipt-1"
        } finally {
            runtime.stop()
        }
    }

    @Test
    fun `poll request keeps long polling concurrency and system attributes`() = runSuspendIO {
        val requests = ConcurrentLinkedQueue<ReceiveMessageRequest>()
        coEvery { client.receiveMessage(any<ReceiveMessageRequest>()) } coAnswers {
            requests += firstArg<ReceiveMessageRequest>()
            ReceiveMessageResponse {}
        }

        val runtime = SqsConsumerRuntime(
            SqsConsumerRuntimeConfig(
                sqsClient = client,
                queueUrl = "https://sqs.local/orders.fifo",
                coroutines = 1,
                maxMessages = 3,
                waitTimeSeconds = 7,
                visibilityTimeoutSeconds = 19,
                messageType = String::class,
                messageHandler = {},
            ),
        )

        try {
            runtime.start()
            await atMost 5.seconds untilAsserted {
                requests.isNotEmpty().shouldBeTrue()
            }

            val request = requests.first()
            request.queueUrl shouldBeEqualTo "https://sqs.local/orders.fifo"
            request.maxNumberOfMessages shouldBeEqualTo 3
            request.waitTimeSeconds shouldBeEqualTo 7
            request.visibilityTimeout shouldBeEqualTo 19
            request.messageAttributeNames shouldBeEqualTo listOf("All")
            request.messageSystemAttributeNames shouldBeEqualTo listOf(
                aws.sdk.kotlin.services.sqs.model.MessageSystemAttributeName.All,
            )
        } finally {
            runtime.stop()
        }
    }

    @Test
    fun `interceptors preserve receive invoke and auto ack ordering`() = runSuspendIO {
        val events = ConcurrentLinkedQueue<String>()
        val message = message("message-1", "receipt-1", "ordered")

        stubReceives(client, message)

        coEvery { client.deleteMessage(any<DeleteMessageRequest>()) } returns DeleteMessageResponse {}

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

            await atMost 5.seconds untilAsserted {
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
        val visibilityTimeouts = ConcurrentLinkedQueue<Int>()
        val observations = ConcurrentLinkedQueue<SqsConsumerObservation>()
        val message = message("message-1", "receipt-1", "fail")

        stubReceives(client, message)

        coEvery { client.changeMessageVisibility(any<ChangeMessageVisibilityRequest>()) } coAnswers {
            visibilityTimeouts += firstArg<ChangeMessageVisibilityRequest>().visibilityTimeout ?: -1
            ChangeMessageVisibilityResponse {}
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

            await atMost 5.seconds untilAsserted {
                visibilityTimeouts.toList() shouldBeEqualTo listOf(7)
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
        client: SqsClient,
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
                sqsClient = client,
                queueUrl = "https://sqs.local/source",
                coroutines = 1,
                maxMessages = 1,
                waitTimeSeconds = 0,
                shutdownTimeout = 100.millis(),
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

    private fun stubReceives(client: SqsClient, firstMessage: Message) {
        val receiveCalls = AtomicInteger()
        coEvery { client.receiveMessage(any<ReceiveMessageRequest>()) } coAnswers {
            val messages = if (receiveCalls.incrementAndGet() == 1) listOf(firstMessage) else emptyList()
            ReceiveMessageResponse { this.messages = messages }
        }
    }

    private fun message(id: String, receiptHandle: String, body: String): Message =
        Message {
            messageId = id
            this.receiptHandle = receiptHandle
            this.body = body
        }

    private object ThrowingConverter: SqsMessageConverter {
        override fun <T: Any> convert(message: Message, targetType: KClass<T>): T =
            throw IllegalArgumentException("conversion failed")
    }
}
