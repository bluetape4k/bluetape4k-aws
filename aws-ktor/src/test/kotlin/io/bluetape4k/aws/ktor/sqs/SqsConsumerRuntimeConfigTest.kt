package io.bluetape4k.aws.ktor.sqs

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.aws.ktor.AwsKtorDefaults
import io.bluetape4k.aws.ktor.AwsKtorSqsAsyncClientCustomizer
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.ktor.http.Url
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.awaitility.kotlin.await
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import software.amazon.awssdk.services.sqs.SqsAsyncClient
import software.amazon.awssdk.services.sqs.model.Message
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest
import software.amazon.awssdk.services.sqs.model.ReceiveMessageResponse
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.Consumer

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SqsConsumerRuntimeConfigTest {

    private val client = mockk<SqsAsyncClient>()

    @Test
    fun `requires exactly one source queue identity`() {
        assertFailsWith<IllegalArgumentException> {
            runtimeConfig(queueUrl = null, queueName = null)
        }

        assertFailsWith<IllegalArgumentException> {
            runtimeConfig(queueUrl = "https://sqs.local/queue", queueName = "queue")
        }
    }

    @Test
    fun `validates concurrency receive and shutdown ranges`() {
        assertFailsWith<IllegalArgumentException> {
            runtimeConfig(coroutines = 0)
        }
        assertFailsWith<IllegalArgumentException> {
            runtimeConfig(maxMessages = 11)
        }
        assertFailsWith<IllegalArgumentException> {
            runtimeConfig(waitTimeSeconds = 21)
        }
        assertFailsWith<IllegalArgumentException> {
            runtimeConfig(shutdownTimeout = Duration.ZERO)
        }
    }

    @Test
    fun `manual dead letter queue and failure visibility are mutually exclusive`() {
        assertFailsWith<IllegalArgumentException> {
            runtimeConfig(
                deadLetterQueueUrl = "https://sqs.local/dlq",
                failureVisibilityTimeoutSeconds = 0,
            )
        }

        assertFailsWith<IllegalArgumentException> {
            runtimeConfig(
                deadLetterQueueUrl = "https://sqs.local/dlq",
                failureVisibilityStrategy = SqsFixedFailureVisibilityStrategy(timeoutSeconds = 0),
            )
        }
    }

    @Test
    fun `fixed failure visibility and strategy are mutually exclusive`() {
        assertFailsWith<IllegalArgumentException> {
            runtimeConfig(
                failureVisibilityTimeoutSeconds = 0,
                failureVisibilityStrategy = SqsFixedFailureVisibilityStrategy(timeoutSeconds = 1),
            )
        }
    }

    @Test
    fun `linear failure visibility strategy uses receive count`() {
        val strategy = SqsLinearFailureVisibilityStrategy(
            baseTimeoutSeconds = 3,
            maxTimeoutSeconds = 10,
            jitterRatio = 0.0,
        )
        val message = Message.builder()
            .messageId("message-1")
            .receiptHandle("receipt-1")
            .body("body")
            .attributesWithStrings(mapOf("ApproximateReceiveCount" to Int.MAX_VALUE.toString()))
            .build()
        val context = SqsConsumerFailureContext(
            queueUrl = "https://sqs.local/source",
            message = message,
            cause = IllegalStateException("boom"),
            phase = SqsConsumerFailurePhase.Handler,
        )

        strategy.visibilityTimeoutSeconds(context) shouldBeEqualTo 10
    }

    @Test
    fun `default converter supports string byte array and raw message`() {
        val message = Message.builder()
            .body("hello")
            .receiptHandle("receipt")
            .build()

        StringOrByteArraySqsMessageConverter.convert(message, String::class) shouldBeEqualTo "hello"
        StringOrByteArraySqsMessageConverter.convert(message, ByteArray::class).decodeToString() shouldBeEqualTo "hello"
        StringOrByteArraySqsMessageConverter.convert(message, Message::class) shouldBeEqualTo message
    }

    @Test
    fun `shared AWS defaults create plugin owned SQS client`() {
        val config = SqsConsumerPluginConfig().apply {
            queueName = "orders"
            onMessage<String> {}
        }.toRuntimeConfig(
            AwsKtorDefaults(
                region = "ap-northeast-2",
                endpointOverride = Url("http://localhost:4566"),
            )
        )

        config.ownsClient shouldBeEqualTo true
        config.queueName shouldBeEqualTo "orders"

        config.sqsAsyncClient.close()
    }

    @Test
    fun `service SQS customizer runs after shared customizer`() {
        val order = mutableListOf<String>()
        val config = SqsConsumerPluginConfig().apply {
            queueName = "orders"
            sqsAsyncClient { order += "service" }
            onMessage<String> {}
        }.toRuntimeConfig(
            AwsKtorDefaults(
                region = "ap-northeast-2",
                sqsAsyncClientCustomizers = listOf(AwsKtorSqsAsyncClientCustomizer { order += "shared" }),
            )
        )

        order shouldBeEqualTo listOf("shared", "service")

        config.sqsAsyncClient.close()
    }

    @Test
    fun `runtime closes plugin owned SQS client once`() = runSuspendIO {
        val client = mockk<SqsAsyncClient>(relaxed = true)
        every { client.close() } returns Unit
        val runtime = SqsConsumerRuntime(
            runtimeConfig(client = client, ownsClient = true)
        )

        runtime.stop()
        runtime.stop()

        verify(exactly = 1) { client.close() }
    }

    @Test
    fun `stop before start rejects restart and never polls with closed owned client`() = runSuspendIO {
        val client = mockk<SqsAsyncClient>(relaxed = true)
        val receiveCalls = AtomicInteger()
        every { client.close() } returns Unit
        every { client.receiveMessage(any<Consumer<ReceiveMessageRequest.Builder>>()) } answers {
            receiveCalls.incrementAndGet()
            CompletableFuture.completedFuture(ReceiveMessageResponse.builder().build())
        }
        val runtime = SqsConsumerRuntime(
            runtimeConfig(client = client, ownsClient = true)
        )

        runtime.stop()

        assertFailsWith<IllegalStateException> { runtime.start() }
        runtime.isRunning shouldBeEqualTo false
        receiveCalls.get() shouldBeEqualTo 0
        verify(exactly = 1) { client.close() }
    }

    @Test
    fun `stopped runtime rejects restart without reusing closed owned client`() = runSuspendIO {
        val client = mockk<SqsAsyncClient>(relaxed = true)
        val receiveCalls = AtomicInteger()
        val receiveStarted = CountDownLatch(1)
        val pendingReceive = CompletableFuture<ReceiveMessageResponse>()
        every { client.close() } returns Unit
        every { client.receiveMessage(any<Consumer<ReceiveMessageRequest.Builder>>()) } answers {
            receiveCalls.incrementAndGet()
            receiveStarted.countDown()
            pendingReceive
        }
        val runtime = SqsConsumerRuntime(
            runtimeConfig(client = client, ownsClient = true)
        )

        try {
            runtime.start()
            await.atMost(Duration.ofSeconds(2)).untilAsserted {
                receiveStarted.count shouldBeEqualTo 0L
            }

            runtime.stop()
            val receiveCallsAfterStop = receiveCalls.get()

            assertFailsWith<IllegalStateException> { runtime.start() }
            await.during(Duration.ofMillis(200))
                .atMost(Duration.ofSeconds(2))
                .untilAsserted {
                    receiveCalls.get() shouldBeEqualTo receiveCallsAfterStop
                }
            verify(exactly = 1) { client.close() }
        } finally {
            pendingReceive.cancel(true)
            runtime.stop()
        }
    }

    @Test
    fun `duplicate start creates one poller and injected client stays open after duplicate stop`() = runSuspendIO {
        val client = mockk<SqsAsyncClient>(relaxed = true)
        val receiveCalls = AtomicInteger()
        val receiveStarted = CountDownLatch(1)
        val pendingReceive = CompletableFuture<ReceiveMessageResponse>()
        every { client.receiveMessage(any<Consumer<ReceiveMessageRequest.Builder>>()) } answers {
            receiveCalls.incrementAndGet()
            receiveStarted.countDown()
            pendingReceive
        }
        val runtime = SqsConsumerRuntime(
            runtimeConfig(client = client, ownsClient = false)
        )

        try {
            runtime.start()
            runtime.start()
            await.atMost(Duration.ofSeconds(2)).untilAsserted {
                receiveStarted.count shouldBeEqualTo 0L
            }
            await.during(Duration.ofMillis(200))
                .atMost(Duration.ofSeconds(2))
                .untilAsserted {
                    receiveCalls.get() shouldBeEqualTo 1
                }

            runtime.stop()
            runtime.stop()
            verify(exactly = 0) { client.close() }
        } finally {
            pendingReceive.cancel(true)
            runtime.stop()
        }
    }

    @Test
    fun `invalid runtime configuration closes plugin owned client`() {
        val ownedClient = mockk<SqsAsyncClient>(relaxed = true)
        every { ownedClient.close() } returns Unit

        assertFailsWith<IllegalArgumentException> {
            SqsConsumerPluginConfig().apply {
                queueUrl = null
                queueName = null
                onMessage<String> {}
            }.toRuntimeConfig(clientFactory = { ownedClient })
        }

        verify(exactly = 1) { ownedClient.close() }
    }

    @Test
    fun `invalid runtime configuration leaves injected client open`() {
        val injectedClient = mockk<SqsAsyncClient>(relaxed = true)

        assertFailsWith<IllegalArgumentException> {
            SqsConsumerPluginConfig().apply {
                sqsAsyncClient = injectedClient
                queueUrl = null
                queueName = null
                onMessage<String> {}
            }.toRuntimeConfig()
        }

        verify(exactly = 0) { injectedClient.close() }
    }

    private fun runtimeConfig(
        client: SqsAsyncClient = this.client,
        ownsClient: Boolean = false,
        queueUrl: String? = "https://sqs.local/source",
        queueName: String? = null,
        coroutines: Int = 1,
        maxMessages: Int = 10,
        waitTimeSeconds: Int = 20,
        shutdownTimeout: Duration = Duration.ofSeconds(30),
        deadLetterQueueUrl: String? = null,
        failureVisibilityTimeoutSeconds: Int? = null,
        failureVisibilityStrategy: SqsFailureVisibilityStrategy? = null,
    ): SqsConsumerRuntimeConfig =
        SqsConsumerRuntimeConfig(
            sqsAsyncClient = client,
            ownsClient = ownsClient,
            queueUrl = queueUrl,
            queueName = queueName,
            coroutines = coroutines,
            maxMessages = maxMessages,
            waitTimeSeconds = waitTimeSeconds,
            shutdownTimeout = shutdownTimeout,
            deadLetterQueueUrl = deadLetterQueueUrl,
            failureVisibilityTimeoutSeconds = failureVisibilityTimeoutSeconds,
            failureVisibilityStrategy = failureVisibilityStrategy,
            messageType = String::class,
            messageHandler = {},
        )
}
