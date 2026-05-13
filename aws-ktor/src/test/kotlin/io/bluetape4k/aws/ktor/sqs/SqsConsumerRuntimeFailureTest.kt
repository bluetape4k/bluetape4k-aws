package io.bluetape4k.aws.ktor.sqs

import io.bluetape4k.assertions.shouldBeGreaterOrEqualTo
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.junit5.awaitility.untilSuspending
import io.bluetape4k.junit5.coroutines.runSuspendIO
import kotlinx.coroutines.awaitCancellation
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.awaitility.kotlin.await
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import software.amazon.awssdk.services.sqs.SqsAsyncClient
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest
import software.amazon.awssdk.services.sqs.model.GetQueueUrlRequest
import software.amazon.awssdk.services.sqs.model.GetQueueUrlResponse
import software.amazon.awssdk.services.sqs.model.Message
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest
import software.amazon.awssdk.services.sqs.model.ReceiveMessageResponse
import software.amazon.awssdk.services.sqs.model.SendMessageRequest
import software.amazon.awssdk.services.sqs.model.SendMessageResponse
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.Consumer

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SqsConsumerRuntimeFailureTest {

    @Test
    fun `slow handlers apply backpressure to receive loop`() = runSuspendIO {
        val client = mockk<SqsAsyncClient>()
        val receiveCalls = AtomicInteger()
        val handlerStarted = CountDownLatch(1)
        val message = Message.builder()
            .messageId("message-1")
            .receiptHandle("receipt-1")
            .body("slow")
            .build()

        every { client.receiveMessage(any<Consumer<ReceiveMessageRequest.Builder>>()) } answers {
            val response = if (receiveCalls.incrementAndGet() == 1) {
                ReceiveMessageResponse.builder().messages(message).build()
            } else {
                ReceiveMessageResponse.builder().build()
            }
            CompletableFuture.completedFuture(response)
        }
        every { client.deleteMessage(any<Consumer<DeleteMessageRequest.Builder>>()) } returns
            CompletableFuture.completedFuture(mockk())

        val runtime = SqsConsumerRuntime(
            SqsConsumerRuntimeConfig(
                sqsAsyncClient = client,
                queueUrl = "https://sqs.local/source",
                coroutines = 1,
                maxMessages = 1,
                waitTimeSeconds = 0,
                shutdownTimeout = Duration.ofMillis(100),
                messageType = String::class,
                messageHandler = {
                    handlerStarted.countDown()
                    awaitCancellation()
                },
            )
        )

        try {
            runtime.start()

            await.atMost(Duration.ofSeconds(5)).untilSuspending {
                handlerStarted.count == 0L && receiveCalls.get() == 1
            }
            await.during(Duration.ofMillis(300))
                .atMost(Duration.ofSeconds(2))
                .untilSuspending {
                    receiveCalls.get() == 1
            }
        } finally {
            runtime.stop()
        }
    }

    @Test
    fun `successful handler delete failure does not forward message to dead letter queue`() = runSuspendIO {
        val client = mockk<SqsAsyncClient>()
        val receiveCalls = AtomicInteger()
        val deleteCalls = AtomicInteger()
        val pendingReceive = CompletableFuture<ReceiveMessageResponse>()
        val message = Message.builder()
            .messageId("message-1")
            .receiptHandle("receipt-1")
            .body("ok")
            .build()

        every { client.receiveMessage(any<Consumer<ReceiveMessageRequest.Builder>>()) } answers {
            val response = if (receiveCalls.incrementAndGet() == 1) {
                ReceiveMessageResponse.builder().messages(message).build()
            } else {
                return@answers pendingReceive
            }
            CompletableFuture.completedFuture(response)
        }
        every { client.deleteMessage(any<Consumer<DeleteMessageRequest.Builder>>()) } answers {
            deleteCalls.incrementAndGet()
            CompletableFuture.failedFuture(RuntimeException("delete failed"))
        }
        every { client.sendMessage(any<Consumer<SendMessageRequest.Builder>>()) } returns
            CompletableFuture.completedFuture(SendMessageResponse.builder().messageId("dlq").build())

        val runtime = SqsConsumerRuntime(
            SqsConsumerRuntimeConfig(
                sqsAsyncClient = client,
                queueUrl = "https://sqs.local/source",
                coroutines = 1,
                maxMessages = 1,
                waitTimeSeconds = 0,
                deadLetterQueueUrl = "https://sqs.local/dlq",
                pollBackoff = SqsPollBackoff(Duration.ofMillis(10), Duration.ofMillis(10)),
                messageType = String::class,
                messageHandler = {},
            )
        )

        try {
            runtime.start()

            await.atMost(Duration.ofSeconds(5)).untilAsserted {
                deleteCalls.get() shouldBeGreaterOrEqualTo 1
            }
        } finally {
            pendingReceive.cancel(true)
            runtime.stop()
        }

        verify(exactly = 0) {
            client.sendMessage(any<Consumer<SendMessageRequest.Builder>>())
        }
    }

    @Test
    fun `queue name resolution failure is retried without killing poller`() = runSuspendIO {
        val client = mockk<SqsAsyncClient>()
        val resolveCalls = AtomicInteger()
        val receiveCalls = AtomicInteger()

        every { client.getQueueUrl(any<Consumer<GetQueueUrlRequest.Builder>>()) } answers {
            if (resolveCalls.incrementAndGet() == 1) {
                CompletableFuture.failedFuture(RuntimeException("temporary getQueueUrl failure"))
            } else {
                CompletableFuture.completedFuture(
                    GetQueueUrlResponse.builder()
                        .queueUrl("https://sqs.local/source")
                        .build()
                )
            }
        }
        every { client.receiveMessage(any<Consumer<ReceiveMessageRequest.Builder>>()) } answers {
            receiveCalls.incrementAndGet()
            CompletableFuture.completedFuture(ReceiveMessageResponse.builder().build())
        }

        val runtime = SqsConsumerRuntime(
            SqsConsumerRuntimeConfig(
                sqsAsyncClient = client,
                queueName = "source",
                coroutines = 1,
                maxMessages = 1,
                waitTimeSeconds = 0,
                pollBackoff = SqsPollBackoff(Duration.ofMillis(10), Duration.ofMillis(10)),
                messageType = String::class,
                messageHandler = {},
            )
        )

        try {
            runtime.start()

            await.atMost(Duration.ofSeconds(5)).untilAsserted {
                resolveCalls.get() shouldBeGreaterOrEqualTo 2
                receiveCalls.get() shouldBeGreaterOrEqualTo 1
            }
        } finally {
            runtime.stop()
        }
    }
}
