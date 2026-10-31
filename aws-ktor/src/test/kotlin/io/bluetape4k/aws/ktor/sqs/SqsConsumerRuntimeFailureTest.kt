package io.bluetape4k.aws.ktor.sqs

import aws.sdk.kotlin.services.sqs.SqsClient
import aws.sdk.kotlin.services.sqs.model.ChangeMessageVisibilityRequest
import aws.sdk.kotlin.services.sqs.model.ChangeMessageVisibilityResponse
import aws.sdk.kotlin.services.sqs.model.DeleteMessageRequest
import aws.sdk.kotlin.services.sqs.model.DeleteMessageResponse
import aws.sdk.kotlin.services.sqs.model.GetQueueUrlRequest
import aws.sdk.kotlin.services.sqs.model.GetQueueUrlResponse
import aws.sdk.kotlin.services.sqs.model.Message
import aws.sdk.kotlin.services.sqs.model.ReceiveMessageRequest
import aws.sdk.kotlin.services.sqs.model.ReceiveMessageResponse
import aws.sdk.kotlin.services.sqs.model.SendMessageRequest
import aws.sdk.kotlin.services.sqs.model.SendMessageResponse
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeGreaterOrEqualTo
import io.bluetape4k.assertions.shouldBeLessThan
import io.bluetape4k.javatimes.millis
import io.bluetape4k.javatimes.seconds
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.system.measureTimeMillis
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SqsConsumerRuntimeFailureTest {

    companion object: KLoggingChannel()

    private val client = mockk<SqsClient>()

    @BeforeEach
    fun beforeEach() {
        clearMocks(client)
    }

    @Test
    fun `slow handlers apply backpressure to receive loop`() = runSuspendIO {
        val receiveCalls = AtomicInteger()
        val handlerStarted = CountDownLatch(1)
        val message = Message { messageId = "message-1"; receiptHandle = "receipt-1"; body = "slow" }

        coEvery { client.receiveMessage(any<ReceiveMessageRequest>()) } coAnswers {
            val response = if (receiveCalls.incrementAndGet() == 1) {
                ReceiveMessageResponse { messages = listOf(message) }
            } else {
                ReceiveMessageResponse {}
            }
            response
        }
        coEvery { client.deleteMessage(any<DeleteMessageRequest>()) } returns DeleteMessageResponse {}

        val runtime = SqsConsumerRuntime(
            SqsConsumerRuntimeConfig(
                sqsClient = client,
                queueUrl = "https://sqs.local/source",
                coroutines = 1,
                maxMessages = 1,
                waitTimeSeconds = 0,
                shutdownTimeout = 100.millis(),
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
            val releaseHandler = CompletableDeferred<Unit>()
            val message = Message { messageId = "message-1"; receiptHandle = "receipt-1"; body = "slow" }

            coEvery { client.receiveMessage(any<ReceiveMessageRequest>()) } coAnswers {
                receiveCalls.incrementAndGet()
                ReceiveMessageResponse { messages = listOf(message) }
            }
            coEvery { client.deleteMessage(any<DeleteMessageRequest>()) } returns DeleteMessageResponse {}

            val runtime = SqsConsumerRuntime(
                SqsConsumerRuntimeConfig(
                    sqsClient = client,
                    queueUrl = "https://sqs.local/source",
                    coroutines = 1,
                    maxMessages = 1,
                    waitTimeSeconds = 0,
                    shutdownTimeout = 3.seconds(),
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
            val releaseHandler = CompletableDeferred<Unit>()
            val message = Message { messageId = "message-1"; receiptHandle = "receipt-1"; body = "slow" }

            coEvery { client.receiveMessage(any<ReceiveMessageRequest>()) } returns
                ReceiveMessageResponse { messages = listOf(message) }
            coEvery { client.changeMessageVisibility(any<ChangeMessageVisibilityRequest>()) } coAnswers {
                visibilityCalls.incrementAndGet()
                ChangeMessageVisibilityResponse {}
            }
            coEvery { client.deleteMessage(any<DeleteMessageRequest>()) } returns DeleteMessageResponse {}

            val runtime = SqsConsumerRuntime(
                SqsConsumerRuntimeConfig(
                    sqsClient = client,
                    queueUrl = "https://sqs.local/source",
                    coroutines = 1,
                    maxMessages = 1,
                    waitTimeSeconds = 0,
                    visibilityTimeoutSeconds = 5,
                    visibilityHeartbeatSeconds = 1,
                    shutdownTimeout = 3.seconds(),
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
            val neverCompletes = CompletableDeferred<Unit>()
            val message = Message { messageId = "message-1"; receiptHandle = "receipt-1"; body = "stuck" }

            coEvery { client.receiveMessage(any<ReceiveMessageRequest>()) } returns
                ReceiveMessageResponse { messages = listOf(message) }
            coEvery { client.changeMessageVisibility(any<ChangeMessageVisibilityRequest>()) } coAnswers {
                visibilityCalls.incrementAndGet()
                ChangeMessageVisibilityResponse {}
            }
            coEvery { client.deleteMessage(any<DeleteMessageRequest>()) } coAnswers {
                deleteCalls.incrementAndGet()
                DeleteMessageResponse {}
            }

            val runtime = SqsConsumerRuntime(
                SqsConsumerRuntimeConfig(
                    sqsClient = client,
                    queueUrl = "https://sqs.local/source",
                    coroutines = 1,
                    maxMessages = 1,
                    waitTimeSeconds = 0,
                    visibilityTimeoutSeconds = 5,
                    visibilityHeartbeatSeconds = 1,
                    shutdownTimeout = 100.millis(),
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
        val pendingReceive = CompletableDeferred<ReceiveMessageResponse>()
        val message = Message { messageId = "message-1"; receiptHandle = "receipt-1"; body = "ok" }

        coEvery { client.receiveMessage(any<ReceiveMessageRequest>()) } coAnswers {
            val response = if (receiveCalls.incrementAndGet() == 1) {
                ReceiveMessageResponse { messages = listOf(message) }
            } else {
                return@coAnswers pendingReceive.await()
            }
            response
        }
        coEvery { client.deleteMessage(any<DeleteMessageRequest>()) } coAnswers {
            deleteCalls.incrementAndGet()
            throw RuntimeException("delete failed")
        }
        coEvery { client.sendMessage(any<SendMessageRequest>()) } returns SendMessageResponse { messageId = "dlq" }

        val runtime = SqsConsumerRuntime(
            SqsConsumerRuntimeConfig(
                sqsClient = client,
                queueUrl = "https://sqs.local/source",
                coroutines = 1,
                maxMessages = 1,
                waitTimeSeconds = 0,
                deadLetterQueueUrl = "https://sqs.local/dlq",
                pollBackoff = SqsPollBackoff(10.millis(), 10.millis()),
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
            pendingReceive.cancel()
            runtime.stop()
        }

        coVerify(exactly = 0) { client.sendMessage(any<SendMessageRequest>()) }
    }

    @Test
    fun `queue name resolution failure is retried without killing poller`() = runSuspendIO {
        val resolveCalls = AtomicInteger()
        val receiveCalls = AtomicInteger()

        coEvery { client.getQueueUrl(any<GetQueueUrlRequest>()) } coAnswers {
            if (resolveCalls.incrementAndGet() == 1) {
                throw RuntimeException("temporary getQueueUrl failure")
            } else {
                GetQueueUrlResponse { queueUrl = "https://sqs.local/source" }
            }
        }
        coEvery { client.receiveMessage(any<ReceiveMessageRequest>()) } coAnswers {
            receiveCalls.incrementAndGet()
            ReceiveMessageResponse {}
        }

        val runtime = SqsConsumerRuntime(
            SqsConsumerRuntimeConfig(
                sqsClient = client,
                queueName = "source",
                coroutines = 1,
                maxMessages = 1,
                waitTimeSeconds = 0,
                pollBackoff = SqsPollBackoff(10.millis(), 10.millis()),
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
