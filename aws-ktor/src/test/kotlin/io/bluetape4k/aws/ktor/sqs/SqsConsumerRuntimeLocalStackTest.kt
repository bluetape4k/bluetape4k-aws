package io.bluetape4k.aws.ktor.sqs

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeGreaterOrEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.aws.sqs.SqsClientFactory
import io.bluetape4k.junit5.awaitility.untilSuspending
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.testcontainers.aws.getCredentialProvider
import io.ktor.server.application.install
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.future.await
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.awaitility.kotlin.await
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.sqs.SqsAsyncClient
import software.amazon.awssdk.services.sqs.model.Message
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SqsConsumerRuntimeLocalStackTest {

    @Suppress("DEPRECATION")
    private val localStack: io.bluetape4k.testcontainers.aws.LocalStackServer by lazy {
        io.bluetape4k.testcontainers.aws.LocalStackServer.Launcher.getLocalStack("sqs")
    }

    private val sqs: SqsAsyncClient by lazy {
        SqsClientFactory.Async.create(
            endpointOverride = localStack.awsEndpoint,
            region = Region.of(localStack.regionName),
            credentialsProvider = localStack.getCredentialProvider(),
        )
    }

    @Test
    fun `runtime consumes messages from multithreaded coroutine publishers`() = runSuspendIO {
        val queueUrl = createQueue("ktor-sqs-concurrency")
        val received = ConcurrentHashMap.newKeySet<String>()
        val handlerThreads = ConcurrentHashMap.newKeySet<String>()
        val publisherThreads = ConcurrentHashMap.newKeySet<String>()
        val publisherDispatcher = Executors.newFixedThreadPool(4).asCoroutineDispatcher()
        val runtime = runtime(queueUrl, coroutines = 4) {
            val body = it as String
            handlerThreads += Thread.currentThread().name
            received += body
        }

        try {
            runtime.start()
            withContext(publisherDispatcher) {
                val jobs = List(40) { index ->
                    launch {
                        publisherThreads += Thread.currentThread().name
                        runtime.send("message-$index", queueUrl)
                    }
                }
                jobs.joinAll()
            }

            await.atMost(Duration.ofSeconds(30)).untilAsserted {
                received.size shouldBeEqualTo 40
            }
            publisherThreads.size shouldBeGreaterOrEqualTo 2
            handlerThreads.size shouldBeGreaterOrEqualTo 1
        } finally {
            publisherDispatcher.close()
            runtime.stop()
            deleteQueue(queueUrl)
        }
    }

    @Test
    fun `stop cancels long running coroutine handler without deleting message`() = runSuspendIO {
        val queueUrl = createQueue("ktor-sqs-shutdown")
        val handlerStarted = CountDownLatch(1)
        val handlerCancelled = AtomicBoolean(false)
        val runtime = runtime(
            queueUrl = queueUrl,
            coroutines = 1,
            visibilityTimeoutSeconds = 1,
            shutdownTimeout = Duration.ofMillis(200),
        ) {
            handlerStarted.countDown()
            try {
                awaitCancellation()
            } catch (e: CancellationException) {
                handlerCancelled.set(true)
                throw e
            }
        }

        try {
            runtime.start()
            runtime.send("slow-message", queueUrl)

            await.atMost(Duration.ofSeconds(10)).untilAsserted {
                (handlerStarted.count == 0L).shouldBeTrue()
            }

            runtime.stop()
            await.atMost(Duration.ofSeconds(5)).untilAsserted {
                handlerCancelled.get().shouldBeTrue()
            }

            await.atMost(Duration.ofSeconds(30)).untilSuspending {
                val message = receiveOne(queueUrl)
                if (message?.body() == "slow-message") {
                    deleteMessage(queueUrl, message)
                    true
                } else {
                    false
                }
            }
        } finally {
            runtime.stop()
            deleteQueue(queueUrl)
        }
    }

    @Test
    fun `failed handler forwards message to manual dead letter queue`() = runSuspendIO {
        val queueUrl = createQueue("ktor-sqs-source")
        val deadLetterQueueUrl = createQueue("ktor-sqs-dlq")
        val runtime = runtime(
            queueUrl = queueUrl,
            deadLetterQueueUrl = deadLetterQueueUrl,
        ) {
            error("boom")
        }

        try {
            runtime.start()
            runtime.send("failed-message", queueUrl)

            await.atMost(Duration.ofSeconds(30)).untilSuspending {
                val deadLetter = receiveOne(deadLetterQueueUrl)
                val receivedExpectedDeadLetter = deadLetter?.body() == "failed-message" &&
                    deadLetter.messageAttributes().containsKey("bluetape4k-original-queue-url")

                if (receivedExpectedDeadLetter) {
                    deleteMessage(deadLetterQueueUrl, deadLetter)
                    true
                } else {
                    false
                }
            }
        } finally {
            runtime.stop()
            deleteQueue(queueUrl)
            deleteQueue(deadLetterQueueUrl)
        }
    }

    @Test
    fun `Ktor plugin starts runtime on application started event`() = testApplication {
        val queueUrl = createQueue("ktor-sqs-plugin")
        val received = ConcurrentHashMap.newKeySet<String>()

        application {
            install(SqsConsumer) {
                sqsAsyncClient = sqs
                this.queueUrl = queueUrl
                coroutines = 2
                maxMessages = 2
                waitTimeSeconds = 1
                visibilityTimeoutSeconds = 5
                onMessage<String> {
                    received += it
                }
            }
        }

        try {
            startApplication()
            application.sqsConsumer().isRunning.shouldBeTrue()
            application.sqsConsumer().send("plugin-message", queueUrl)

            await.atMost(Duration.ofSeconds(30)).untilAsserted {
                received.contains("plugin-message").shouldBeTrue()
            }
        } finally {
            application.sqsConsumer().stop()
            deleteQueue(queueUrl)
        }
    }

    private fun runtime(
        queueUrl: String,
        coroutines: Int = 1,
        visibilityTimeoutSeconds: Int = 5,
        shutdownTimeout: Duration = Duration.ofSeconds(30),
        deadLetterQueueUrl: String? = null,
        handler: suspend SqsMessageContext.(Any) -> Unit,
    ): SqsConsumerRuntime =
        SqsConsumerRuntime(
            SqsConsumerRuntimeConfig(
                sqsAsyncClient = sqs,
                queueUrl = queueUrl,
                coroutines = coroutines,
                maxMessages = 10,
                waitTimeSeconds = 1,
                visibilityTimeoutSeconds = visibilityTimeoutSeconds,
                shutdownTimeout = shutdownTimeout,
                deadLetterQueueUrl = deadLetterQueueUrl,
                messageType = String::class,
                messageHandler = handler,
            )
        )

    private suspend fun createQueue(prefix: String): String =
        sqs.createQueue {
            it.queueName("$prefix-${UUID.randomUUID()}")
        }.await().queueUrl()

    private suspend fun deleteQueue(queueUrl: String) {
        sqs.deleteQueue {
            it.queueUrl(queueUrl)
        }.await()
    }

    private suspend fun receiveOne(queueUrl: String): Message? =
        sqs.receiveMessage {
            it.queueUrl(queueUrl)
            it.maxNumberOfMessages(1)
            it.waitTimeSeconds(1)
            it.messageAttributeNames("All")
            it.messageSystemAttributeNamesWithStrings("All")
        }.await().messages().orEmpty().singleOrNull()

    private suspend fun deleteMessage(queueUrl: String, message: Message) {
        sqs.deleteMessage {
            it.queueUrl(queueUrl)
            it.receiptHandle(message.receiptHandle())
        }.await()
    }
}
