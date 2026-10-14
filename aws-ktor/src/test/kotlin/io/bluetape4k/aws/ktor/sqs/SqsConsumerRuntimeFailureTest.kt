package io.bluetape4k.aws.ktor.sqs

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeGreaterOrEqualTo
import io.bluetape4k.assertions.shouldBeLessThan
import io.bluetape4k.concurrent.completableFutureOf
import io.bluetape4k.concurrent.failedCompletableFutureOf
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.future.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.awaitility.kotlin.atMost
import org.awaitility.kotlin.await
import org.awaitility.kotlin.during
import org.awaitility.kotlin.untilAsserted
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import software.amazon.awssdk.services.sqs.SqsAsyncClient
import software.amazon.awssdk.services.sqs.model.ChangeMessageVisibilityRequest
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
import kotlin.system.measureTimeMillis
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SqsConsumerRuntimeFailureTest {

    companion object: KLoggingChannel()

    private val client = mockk<SqsAsyncClient>()

    @BeforeEach
    fun beforeEach() {
        clearMocks(client)
    }

    @Test
    fun `slow handlers apply backpressure to receive loop`() = runSuspendIO {
        val receiveCalls = AtomicInteger()
        val handlerStarted = CountDownLatch(1)
        val message = Message.builder().messageId("message-1").receiptHandle("receipt-1").body("slow").build()

        every {
            client.receiveMessage(any<Consumer<ReceiveMessageRequest.Builder>>())
        } answers {
            val response = if (receiveCalls.incrementAndGet() == 1) {
                ReceiveMessageResponse.builder().messages(message).build()
            } else {
                ReceiveMessageResponse.builder().build()
            }
            completableFutureOf(response)
        }
        every {
            client.deleteMessage(any<Consumer<DeleteMessageRequest.Builder>>())
        } returns completableFutureOf(mockk())

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

            await atMost 5.seconds untilAsserted {
                handlerStarted.count shouldBeEqualTo 0L
                receiveCalls.get() shouldBeEqualTo 1
            }
            await during 300.milliseconds atMost 2.seconds untilAsserted {
                receiveCalls.get() shouldBeEqualTo 1
            }
        } finally {
            runtime.stop()
        }
    }

    @Test
    fun `start is ignored while stop is draining handlers`() = runSuspendIO {
        coroutineScope {
            val receiveCalls = AtomicInteger()
            val handlerStarted = CountDownLatch(1)
            val releaseHandler = CompletableFuture<Unit>()
            val message = Message.builder().messageId("message-1").receiptHandle("receipt-1").body("slow").build()

            every {
                client.receiveMessage(any<Consumer<ReceiveMessageRequest.Builder>>())
            } answers {
                receiveCalls.incrementAndGet()
                completableFutureOf(ReceiveMessageResponse.builder().messages(message).build())
            }
            every {
                client.deleteMessage(any<Consumer<DeleteMessageRequest.Builder>>())
            } returns completableFutureOf(mockk())

            val runtime = SqsConsumerRuntime(
                SqsConsumerRuntimeConfig(
                    sqsAsyncClient = client,
                    queueUrl = "https://sqs.local/source",
                    coroutines = 1,
                    maxMessages = 1,
                    waitTimeSeconds = 0,
                    shutdownTimeout = Duration.ofSeconds(3),
                    messageType = String::class,
                    messageHandler = {
                        handlerStarted.countDown()
                        releaseHandler.await()
                    },
                )
            )

            try {
                runtime.start()
                await atMost 5.seconds untilAsserted {
                    handlerStarted.count shouldBeEqualTo 0L
                    receiveCalls.get() shouldBeEqualTo 1
                }

                val stopJob = launch {
                    runtime.stop()
                }
                await atMost 2.seconds untilAsserted {
                    runtime.isRunning.shouldBeFalse()
                }

                runtime.start()
                await during 300.milliseconds atMost 2.seconds untilAsserted {
                    receiveCalls.get() shouldBeEqualTo 1
                }

                releaseHandler.complete(Unit)
                stopJob.join()
            } finally {
                releaseHandler.complete(Unit)
                runtime.stop()
            }
        }
    }

    @Test
    fun `visibility heartbeat continues while stop drains running handler`() = runSuspendIO {
        coroutineScope {
            val visibilityCalls = AtomicInteger()
            val handlerStarted = CountDownLatch(1)
            val releaseHandler = CompletableFuture<Unit>()
            val message = Message.builder().messageId("message-1").receiptHandle("receipt-1").body("slow").build()

            every {
                client.receiveMessage(any<Consumer<ReceiveMessageRequest.Builder>>())
            } returns completableFutureOf(ReceiveMessageResponse.builder().messages(message).build())
            every {
                client.changeMessageVisibility(any<Consumer<ChangeMessageVisibilityRequest.Builder>>())
            } answers {
                visibilityCalls.incrementAndGet()
                completableFutureOf(mockk())
            }
            every {
                client.deleteMessage(any<Consumer<DeleteMessageRequest.Builder>>())
            } returns completableFutureOf(mockk())

            val runtime = SqsConsumerRuntime(
                SqsConsumerRuntimeConfig(
                    sqsAsyncClient = client,
                    queueUrl = "https://sqs.local/source",
                    coroutines = 1,
                    maxMessages = 1,
                    waitTimeSeconds = 0,
                    visibilityTimeoutSeconds = 5,
                    visibilityHeartbeatSeconds = 1,
                    shutdownTimeout = Duration.ofSeconds(3),
                    messageType = String::class,
                    messageHandler = {
                        handlerStarted.countDown()
                        releaseHandler.await()
                    },
                )
            )

            try {
                runtime.start()
                await atMost 5.seconds untilAsserted {
                    handlerStarted.count shouldBeEqualTo 0L
                }

                val stopJob = launch {
                    runtime.stop()
                }

                await atMost 2.seconds untilAsserted {
                    visibilityCalls.get() shouldBeGreaterOrEqualTo 1
                }

                releaseHandler.complete(Unit)
                stopJob.join()
            } finally {
                releaseHandler.complete(Unit)
                runtime.stop()
            }
        }
    }

    @Test
    fun `stop timeout cancels heartbeat without waiting for non cooperative handler`() = runSuspendIO {
        coroutineScope {
            val visibilityCalls = AtomicInteger()
            val deleteCalls = AtomicInteger()
            val handlerStarted = CountDownLatch(1)
            val neverCompletes = CompletableFuture<Unit>()
            val message = Message.builder().messageId("message-1").receiptHandle("receipt-1").body("stuck").build()

            every {
                client.receiveMessage(any<Consumer<ReceiveMessageRequest.Builder>>())
            } returns completableFutureOf(ReceiveMessageResponse.builder().messages(message).build())
            every {
                client.changeMessageVisibility(any<Consumer<ChangeMessageVisibilityRequest.Builder>>())
            } answers {
                visibilityCalls.incrementAndGet()
                completableFutureOf(mockk())
            }
            every {
                client.deleteMessage(any<Consumer<DeleteMessageRequest.Builder>>())
            } answers {
                deleteCalls.incrementAndGet()
                completableFutureOf(mockk())
            }

            val runtime = SqsConsumerRuntime(
                SqsConsumerRuntimeConfig(
                    sqsAsyncClient = client,
                    queueUrl = "https://sqs.local/source",
                    coroutines = 1,
                    maxMessages = 1,
                    waitTimeSeconds = 0,
                    visibilityTimeoutSeconds = 5,
                    visibilityHeartbeatSeconds = 1,
                    shutdownTimeout = Duration.ofMillis(100),
                    messageType = String::class,
                    messageHandler = {
                        handlerStarted.countDown()
                        withContext(NonCancellable) {
                            neverCompletes.await()
                        }
                    },
                )
            )

            try {
                runtime.start()
                await atMost 5.seconds untilAsserted {
                    handlerStarted.count shouldBeEqualTo 0L
                }
                await atMost 2.seconds untilAsserted {
                    visibilityCalls.get() shouldBeGreaterOrEqualTo 1
                }

                val stopElapsedMillis = measureTimeMillis {
                    withTimeout(500.milliseconds) {
                        runtime.stop()
                    }
                }
                stopElapsedMillis shouldBeLessThan 500L

                val callsAfterStop = visibilityCalls.get()

                await during 1200.milliseconds atMost 2.seconds untilAsserted {
                    visibilityCalls.get() shouldBeEqualTo callsAfterStop
                }

                neverCompletes.complete(Unit)
                await during 300.milliseconds atMost 2.seconds untilAsserted {
                    deleteCalls.get() shouldBeEqualTo 0
                }
            } finally {
                neverCompletes.complete(Unit)
                runtime.stop()
            }
        }
    }

    @Test
    fun `successful handler delete failure does not forward message to dead letter queue`() = runSuspendIO {
        val receiveCalls = AtomicInteger()
        val deleteCalls = AtomicInteger()
        val pendingReceive = CompletableFuture<ReceiveMessageResponse>()
        val message = Message.builder().messageId("message-1").receiptHandle("receipt-1").body("ok").build()

        every {
            client.receiveMessage(any<Consumer<ReceiveMessageRequest.Builder>>())
        } answers {
            val response = if (receiveCalls.incrementAndGet() == 1) {
                ReceiveMessageResponse.builder().messages(message).build()
            } else {
                return@answers pendingReceive
            }
            completableFutureOf(response)
        }
        every {
            client.deleteMessage(any<Consumer<DeleteMessageRequest.Builder>>())
        } answers {
            deleteCalls.incrementAndGet()
            failedCompletableFutureOf(RuntimeException("delete failed"))
        }
        every {
            client.sendMessage(any<Consumer<SendMessageRequest.Builder>>())
        } returns completableFutureOf(SendMessageResponse.builder().messageId("dlq").build())

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

            await atMost 5.seconds untilAsserted {
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
        val resolveCalls = AtomicInteger()
        val receiveCalls = AtomicInteger()

        every {
            client.getQueueUrl(any<Consumer<GetQueueUrlRequest.Builder>>())
        } answers {
            if (resolveCalls.incrementAndGet() == 1) {
                failedCompletableFutureOf(RuntimeException("temporary getQueueUrl failure"))
            } else {
                completableFutureOf(
                    GetQueueUrlResponse.builder().queueUrl("https://sqs.local/source").build()
                )
            }
        }
        every {
            client.receiveMessage(any<Consumer<ReceiveMessageRequest.Builder>>())
        } answers {
            receiveCalls.incrementAndGet()
            completableFutureOf(ReceiveMessageResponse.builder().build())
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

            await atMost 5.seconds untilAsserted {
                resolveCalls.get() shouldBeGreaterOrEqualTo 2
                receiveCalls.get() shouldBeGreaterOrEqualTo 1
            }
        } finally {
            runtime.stop()
        }
    }
}
