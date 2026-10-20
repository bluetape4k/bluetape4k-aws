package io.bluetape4k.aws.ktor.sqs

import aws.sdk.kotlin.runtime.auth.credentials.StaticCredentialsProvider
import aws.sdk.kotlin.services.sqs.SqsClient
import aws.sdk.kotlin.services.sqs.model.CreateQueueRequest
import aws.sdk.kotlin.services.sqs.model.DeleteMessageRequest
import aws.sdk.kotlin.services.sqs.model.DeleteQueueRequest
import aws.sdk.kotlin.services.sqs.model.Message
import aws.sdk.kotlin.services.sqs.model.MessageSystemAttributeName
import aws.sdk.kotlin.services.sqs.model.ReceiveMessageRequest
import aws.smithy.kotlin.runtime.net.url.Url
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.aws.kotlin.sqs.sqsClientOf
import io.bluetape4k.junit5.awaitility.untilSuspending
import io.bluetape4k.junit5.coroutines.SuspendedJobTester
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.testcontainers.aws.AwsEmulatorServer
import io.bluetape4k.testcontainers.aws.FlociServer
import io.bluetape4k.testcontainers.aws.LocalStackServer
import io.bluetape4k.utils.Runtimex
import io.ktor.server.application.install
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import org.awaitility.kotlin.atMost
import org.awaitility.kotlin.await
import org.awaitility.kotlin.untilAsserted
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.time.Duration
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SqsConsumerRuntimeLocalStackTest {

    companion object: KLoggingChannel()

    private val awsEmulator: AwsEmulatorServer by lazy { awsEmulator("sqs") }

    private val sqs: SqsClient by lazy {
        sqsClientOf(
            endpointUrl = Url.parse(awsEmulator.awsEndpoint.toString()),
            region = awsEmulator.regionName,
            credentialsProvider = StaticCredentialsProvider {
                accessKeyId = awsEmulator.awsAccessKey
                secretAccessKey = awsEmulator.awsSecretKey
            },
        )
    }

    @Test
    fun `runtime consumes messages from multithreaded coroutine publishers`() = runSuspendIO {
        val queueUrl = createQueue("ktor-sqs-concurrency")
        val received = ConcurrentHashMap.newKeySet<String>()

        val runtime = runtime(queueUrl, coroutines = 4) {
            val body = it as String
            received += body
        }

        try {
            runtime.start()

            val index = AtomicInteger()

            SuspendedJobTester()
                .rounds(Runtimex.availableProcessors * 4)
                .add {
                    runtime.send("message-${index.getAndIncrement()}", queueUrl)
                }
                .run()

            await atMost 30.seconds untilAsserted {
                received.size shouldBeEqualTo Runtimex.availableProcessors * 4
            }
        } finally {
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

            await atMost 10.seconds untilAsserted {
                handlerStarted.count shouldBeEqualTo 0L
            }

            runtime.stop()
            await atMost 5.seconds untilAsserted {
                handlerCancelled.get().shouldBeTrue()
            }

            await atMost 30.seconds untilSuspending {
                val message = receiveOne(queueUrl)
                if (message?.body == "slow-message") {
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

            await atMost 30.seconds untilSuspending {
                val deadLetter = receiveOne(deadLetterQueueUrl)
                val receivedExpectedDeadLetter = deadLetter?.body == "failed-message" &&
                        deadLetter.messageAttributes.orEmpty().containsKey("bluetape4k-original-queue-url")

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
                sqsClient = sqs
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

            await atMost 30.seconds untilAsserted {
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
                sqsClient = sqs,
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
        requireNotNull(sqs.createQueue(CreateQueueRequest { queueName = "$prefix-${UUID.randomUUID()}" }).queueUrl)

    private suspend fun deleteQueue(queueUrl: String) {
        sqs.deleteQueue(DeleteQueueRequest { this.queueUrl = queueUrl })
    }

    private suspend fun receiveOne(queueUrl: String): Message? =
        sqs.receiveMessage(
            ReceiveMessageRequest {
                this.queueUrl = queueUrl
                maxNumberOfMessages = 1
                waitTimeSeconds = 1
                messageAttributeNames = listOf("All")
                messageSystemAttributeNames = listOf(MessageSystemAttributeName.All)
            },
        ).messages.orEmpty().singleOrNull()

    private suspend fun deleteMessage(queueUrl: String, message: Message) {
        sqs.deleteMessage(
            DeleteMessageRequest {
                this.queueUrl = queueUrl
                receiptHandle = requireNotNull(message.receiptHandle)
            },
        )
    }

    private fun awsEmulator(vararg services: String): AwsEmulatorServer =
        when (val emulator = System.getProperty("bluetape4k.aws.emulator", "floci").trim().lowercase()) {
            "floci" -> FlociServer.Launcher.floci
            "localstack" -> LocalStackServer.Launcher.getLocalStack(*services)
            else -> error("Unsupported AWS emulator: $emulator. Use floci or localstack.")
        }
}
