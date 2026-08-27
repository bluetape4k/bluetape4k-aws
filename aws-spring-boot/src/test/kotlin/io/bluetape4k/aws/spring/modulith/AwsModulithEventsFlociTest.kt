package io.bluetape4k.aws.spring.modulith

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeInstanceOf
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldHaveSize
import io.bluetape4k.assertions.shouldNotBeBlank
import io.bluetape4k.aws.spring.AwsAutoConfiguration
import io.bluetape4k.aws.spring.sns.SnsAutoConfiguration
import io.bluetape4k.aws.spring.sns.SnsHttpMessageParser
import io.bluetape4k.aws.spring.sns.SnsHttpMessageVerifier
import io.bluetape4k.aws.spring.sqs.SqsAutoConfiguration
import io.bluetape4k.aws.spring.sqs.SqsAcknowledgement
import io.bluetape4k.aws.spring.sqs.SqsOperations
import io.bluetape4k.aws.spring.test.AwsSpringBootTestEmulator
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.testcontainers.aws.getCredentialProvider
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.future.await
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestReporter
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.event.EventListener
import org.springframework.modulith.events.EventExternalizationConfiguration
import org.springframework.modulith.events.RoutingTarget
import org.springframework.modulith.events.core.EventSerializer
import org.springframework.modulith.events.support.EventExternalizationTransport
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.sns.SnsAsyncClient
import software.amazon.awssdk.services.sqs.SqsAsyncClient
import software.amazon.awssdk.services.sqs.model.QueueAttributeName
import java.time.Duration
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** 실제 AWS 계정 없이 Floci가 제공하는 Modulith SNS/SQS 경계를 검증합니다. */
@Suppress("LargeClass", "TooManyFunctions")
class AwsModulithEventsFlociTest {

    @Test
    fun `Floci DIRECT round trip preserves envelope and acknowledges exactly once`() {
        val queueName = uniqueName("modulith-direct")
        withSqsResources { sqsClient, cleanup ->
            val queueUrl = sqsClient.createQueue { it.queueName(queueName) }.await().queueUrl()
            cleanup.register { sqsClient.deleteQueue { it.queueUrl(queueUrl) }.await() }

            directContext(queueName, queueName).run { context ->
                context.startupFailure.shouldBeNull()
                val operations = context.getBean(SqsOperations::class.java)
                val transport = context.getBean(EventExternalizationTransport::class.java)
                val listener = context.getBean(AwsModulithSqsEventListener::class.java)
                val handler = context.getBean(FlociEventHandler::class.java)
                val event = FlociEvent("direct-${UUID.randomUUID()}", "payload", "trace-direct")

                runSuspendIO {
                    transport.externalize(event, RoutingTarget.forTarget(TARGET_ALIAS).withoutKey()).join()
                    val message = operations.receive(queueUrl, maxMessages = 1, waitTimeSeconds = 5).single()
                    message.messageAttributes.getValue(DefaultAwsModulithEventCodec.SYSTEM_EVENT_TYPE)
                        .stringValue() shouldBeEqualTo EVENT_TYPE
                    message.messageAttributes.getValue("trace").stringValue() shouldBeEqualTo event.trace

                    val acknowledgement = deletingAcknowledgement(operations, message.queueUrl, message.receiptHandle)
                    listener.onMessage(message, acknowledgement)

                    handler.events shouldBeEqualTo listOf(event)
                    acknowledgement.acknowledgeCalls.get() shouldBeEqualTo 1
                    operations.receive(queueUrl, maxMessages = 1, waitTimeSeconds = 1).size shouldBeEqualTo 0

                    val duplicateAcknowledgement = RecordingAcknowledgement {}
                    listener.onMessage(message, duplicateAcknowledgement)
                    handler.events shouldBeEqualTo listOf(event)
                    duplicateAcknowledgement.acknowledgeCalls.get() shouldBeEqualTo 1
                }
            }
        }
    }

    @Test
    fun `Floci SNS fanout signature-not-proven reaches the local handler through SQS`() {
        val queueName = uniqueName("modulith-fanout")
        val topicName = uniqueName("modulith-fanout")
        withSnsSqsResources { snsClient, sqsClient, cleanup ->
            val queueUrl = sqsClient.createQueue { it.queueName(queueName) }.await().queueUrl()
            cleanup.register { sqsClient.deleteQueue { it.queueUrl(queueUrl) }.await() }
            val queueArn = queueArn(sqsClient, queueUrl)
            val topicArn = snsClient.createTopic { it.name(topicName) }.await().topicArn()
            cleanup.register { snsClient.deleteTopic { it.topicArn(topicArn) }.await() }
            sqsClient.setQueueAttributes {
                it.queueUrl(queueUrl)
                it.attributes(mapOf(QueueAttributeName.POLICY to queuePolicy(queueArn, topicArn)))
            }.await()
            val subscriptionArn = snsClient.subscribe {
                it.topicArn(topicArn)
                it.protocol("sqs")
                it.endpoint(queueArn)
                it.returnSubscriptionArn(true)
            }.await().subscriptionArn()
            if (!subscriptionArn.isNullOrBlank() && subscriptionArn != "pending confirmation") {
                cleanup.register { snsClient.unsubscribe { it.subscriptionArn(subscriptionArn) }.await() }
            }

            val testVerifier = mockk<SnsHttpMessageVerifier>()
            every { testVerifier.verify(any(), any(), any()) } answers {
                SnsHttpMessageParser.parse(firstArg())
            }
            snsContext(queueName, topicName, topicArn, testVerifier).run { context ->
                context.startupFailure.shouldBeNull()
                val operations = context.getBean(SqsOperations::class.java)
                val transport = context.getBean(EventExternalizationTransport::class.java)
                val listener = context.getBean(AwsModulithSqsEventListener::class.java)
                val handler = context.getBean(FlociEventHandler::class.java)
                val event = FlociEvent("sns-${UUID.randomUUID()}", "fanout", "trace-sns")

                runSuspendIO {
                    transport.externalize(event, RoutingTarget.forTarget(TARGET_ALIAS).withoutKey()).join()
                    val message = operations.receive(queueUrl, maxMessages = 1, waitTimeSeconds = 5).single()
                    val acknowledgement = deletingAcknowledgement(operations, message.queueUrl, message.receiptHandle)
                    listener.onMessage(message.withFlociSignatureFixture(), acknowledgement)

                    handler.events shouldBeEqualTo listOf(event)
                    acknowledgement.acknowledgeCalls.get() shouldBeEqualTo 1
                }
            }
        }
    }

    @Test
    fun `Floci DIRECT FIFO preserves group and deterministic deduplication`() {
        val queueName = "${uniqueName("modulith-fifo")}.fifo"
        withSqsResources { sqsClient, cleanup ->
            val queueUrl = sqsClient.createQueue {
                it.queueName(queueName)
                it.attributes(mapOf(QueueAttributeName.FIFO_QUEUE to "true"))
            }.await().queueUrl()
            cleanup.register { sqsClient.deleteQueue { it.queueUrl(queueUrl) }.await() }

            directContext(queueName, queueName).run { context ->
                context.startupFailure.shouldBeNull()
                val operations = context.getBean(SqsOperations::class.java)
                val transport = context.getBean(EventExternalizationTransport::class.java)
                val event = FlociEvent("fifo-${UUID.randomUUID()}", "fifo", "trace-fifo")

                runSuspendIO {
                    transport.externalize(
                        event,
                        RoutingTarget.forTarget(TARGET_ALIAS).andKey("orders"),
                    ).join()
                    val message = operations.receive(queueUrl, maxMessages = 1, waitTimeSeconds = 5).single()

                    message.messageGroupId shouldBeEqualTo "orders"
                    message.messageDeduplicationId.shouldNotBeBlank()
                    message.sequenceNumber.shouldNotBeBlank()
                    operations.delete(queueUrl, message.receiptHandle)
                }
            }
        }
    }

    @Test
    fun `Floci malformed delivery is never acknowledged and records redrive capability deterministically`(
        reporter: TestReporter,
    ) {
        val deadLetterQueueName = uniqueName("modulith-dlq")
        val queueName = uniqueName("modulith-redrive")
        withSqsResources { sqsClient, cleanup ->
            val deadLetterQueueUrl = sqsClient.createQueue { it.queueName(deadLetterQueueName) }.await().queueUrl()
            cleanup.register { sqsClient.deleteQueue { it.queueUrl(deadLetterQueueUrl) }.await() }
            val deadLetterQueueArn = queueArn(sqsClient, deadLetterQueueUrl)
            val queueUrl = sqsClient.createQueue {
                it.queueName(queueName)
                it.attributes(
                    mapOf(
                        QueueAttributeName.VISIBILITY_TIMEOUT to "1",
                        QueueAttributeName.REDRIVE_POLICY to redrivePolicy(deadLetterQueueArn, 2),
                    )
                )
            }.await().queueUrl()
            cleanup.register { sqsClient.deleteQueue { it.queueUrl(queueUrl) }.await() }

            consumerContext(queueName, redriveRequired = true).run { context ->
                context.startupFailure.shouldBeNull()
                val operations = context.getBean(SqsOperations::class.java)
                val consumer = context.getBean(AwsModulithSqsEventConsumer::class.java)

                runSuspendIO {
                    sqsClient.sendMessage { it.queueUrl(queueUrl).messageBody("not-an-envelope") }.await()
                    var lastReceiveCount = 0
                    repeat(4) {
                        val source = operations.receive(
                            queueUrl,
                            maxMessages = 1,
                            waitTimeSeconds = 1,
                            visibilityTimeoutSeconds = 0,
                        ).singleOrNull() ?: return@repeat
                        lastReceiveCount = source.approximateReceiveCount ?: lastReceiveCount
                        kotlin.runCatching { consumer.consume(source) }.exceptionOrNull()
                            .shouldBeInstanceOf<AwsModulithEventException>()
                    }

                    val deadLetters = awaitMessages(operations, deadLetterQueueUrl)
                    if (deadLetters.isNotEmpty()) {
                        reporter.publishEntry("floci.redrive", "supported")
                        deadLetters shouldHaveSize 1
                        deadLetters.single().body shouldBeEqualTo "not-an-envelope"
                        operations.delete(deadLetterQueueUrl, deadLetters.single().receiptHandle)
                    } else {
                        reporter.publishEntry("floci.redrive", "fallback-policy-and-receive-count")
                        val policy = sqsClient.getQueueAttributes {
                            it.queueUrl(queueUrl)
                            it.attributeNames(QueueAttributeName.REDRIVE_POLICY)
                        }.await().attributes()[QueueAttributeName.REDRIVE_POLICY]
                        policy shouldBeEqualTo redrivePolicy(deadLetterQueueArn, 2)
                        (lastReceiveCount >= 2).shouldBeTrue()
                    }
                }
            }
        }
    }

    @Test
    fun `cleanup stack is LIFO leak free and preserves the primary failure`() = runTest {
        val order = mutableListOf<String>()
        val primary = IllegalStateException("primary")
        val cleanup = FlociCleanupStack()
        cleanup.register { order += "queue" }
        cleanup.register {
            order += "topic"
            throw IllegalStateException("cleanup")
        }

        cleanup.close(primary)

        order shouldBeEqualTo listOf("topic", "queue")
        primary.suppressed shouldHaveSize 1
        cleanup.pendingCount shouldBeEqualTo 0
    }

    @Test
    fun `cleanup stack bounds a stuck resource without retaining actions`() = runTest {
        val cleanup = FlociCleanupStack(Duration.ofMillis(10))
        cleanup.register { awaitCancellation() }

        kotlin.runCatching { cleanup.close() }.exceptionOrNull()
            .shouldBeInstanceOf<FlociCleanupException>()
        cleanup.pendingCount shouldBeEqualTo 0
    }

    private fun directContext(queueName: String, destination: String): ApplicationContextRunner =
        baseContext(queueName)
            .withPropertyValues(
                "bluetape4k.aws.modulith.events.producer.enabled=true",
                "bluetape4k.aws.modulith.events.targets.$TARGET_ALIAS.service=sqs",
                "bluetape4k.aws.modulith.events.targets.$TARGET_ALIAS.destination=$destination",
                "bluetape4k.aws.modulith.events.consumer.source-mode=direct",
                "bluetape4k.aws.modulith.events.consumer.redrive-required=false",
            )

    private fun snsContext(
        queueName: String,
        topicName: String,
        topicArn: String,
        verifier: SnsHttpMessageVerifier,
    ): ApplicationContextRunner = baseContext(queueName)
        .withBean(SnsHttpMessageVerifier::class.java, { verifier })
        .withPropertyValues(
            "bluetape4k.aws.modulith.events.producer.enabled=true",
            "bluetape4k.aws.modulith.events.targets.$TARGET_ALIAS.service=sns",
            "bluetape4k.aws.modulith.events.targets.$TARGET_ALIAS.destination=$topicName",
            "bluetape4k.aws.modulith.events.consumer.source-mode=sns",
            "bluetape4k.aws.modulith.events.consumer.expected-topic-arns[0]=$topicArn",
            "bluetape4k.aws.modulith.events.consumer.redrive-required=false",
        )

    private fun consumerContext(queueName: String, redriveRequired: Boolean): ApplicationContextRunner =
        baseContext(queueName)
            .withPropertyValues(
                "bluetape4k.aws.modulith.events.consumer.source-mode=direct",
                "bluetape4k.aws.modulith.events.consumer.redrive-required=$redriveRequired",
            )

    private fun baseContext(queueName: String): ApplicationContextRunner = ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                AwsAutoConfiguration::class.java,
                SnsAutoConfiguration::class.java,
                SqsAutoConfiguration::class.java,
                AwsModulithEventsAutoConfiguration::class.java,
            )
        )
        .withBean(AwsCredentialsProvider::class.java, { awsEmulator.getCredentialProvider() })
        .withBean(AwsModulithEventTypeRegistry::class.java, { eventRegistry() })
        .withBean(EventSerializer::class.java, { FlociEventSerializer })
        .withBean(
            EventExternalizationConfiguration::class.java,
            { EventExternalizationConfiguration.disabled() },
        )
        .withBean(FlociEventHandler::class.java)
        .withPropertyValues(
            "bluetape4k.aws.sns.region=${awsEmulator.regionName}",
            "bluetape4k.aws.sns.endpoint-override=${awsEmulator.awsEndpoint}",
            "bluetape4k.aws.sqs.region=${awsEmulator.regionName}",
            "bluetape4k.aws.sqs.endpoint-override=${awsEmulator.awsEndpoint}",
            "bluetape4k.aws.sqs.listener.enabled=false",
            "bluetape4k.aws.modulith.events.enabled=true",
            "bluetape4k.aws.modulith.events.consumer.enabled=true",
            "bluetape4k.aws.modulith.events.consumer.queue=$queueName",
        )

    private fun eventRegistry(): AwsModulithEventTypeRegistry = AwsModulithEventTypeRegistry.of(
        AwsModulithEventTypeRegistration(
            type = EVENT_TYPE,
            version = 1,
            eventClass = FlociEvent::class.java,
            eventId = FlociEvent::id,
            allowedHeaderNames = setOf("trace"),
            headers = { mapOf("trace" to it.trace) },
        )
    )

    private fun deletingAcknowledgement(
        operations: SqsOperations,
        queueUrl: String,
        receiptHandle: String,
    ): RecordingAcknowledgement = RecordingAcknowledgement {
        operations.delete(queueUrl, receiptHandle)
    }

    private suspend fun awaitMessages(operations: SqsOperations, queueUrl: String) = withTimeout(10_000) {
        var received = operations.receive(queueUrl, maxMessages = 1, waitTimeSeconds = 1)
        while (received.isEmpty()) {
            delay(100)
            received = operations.receive(queueUrl, maxMessages = 1, waitTimeSeconds = 1)
            if (received.isEmpty()) {
                return@withTimeout emptyList()
            }
        }
        received
    }

    private suspend fun queueArn(client: SqsAsyncClient, queueUrl: String): String =
        requireNotNull(
            client.getQueueAttributes {
                it.queueUrl(queueUrl)
                it.attributeNames(QueueAttributeName.QUEUE_ARN)
            }.await().attributes()[QueueAttributeName.QUEUE_ARN]
        )

    private fun queuePolicy(queueArn: String, topicArn: String): String =
        """{"Version":"2012-10-17","Statement":[{"Effect":"Allow","Principal":"*","Action":"sqs:SendMessage","Resource":"$queueArn","Condition":{"ArnEquals":{"aws:SourceArn":"$topicArn"}}}]}"""

    private fun redrivePolicy(deadLetterQueueArn: String, maxReceiveCount: Int): String =
        """{"deadLetterTargetArn":"$deadLetterQueueArn","maxReceiveCount":"$maxReceiveCount"}"""

    /** Floci의 unsigned fanout envelope에 test-only parser 필드를 더하며 서명 성공을 주장하지 않습니다. */
    private fun io.bluetape4k.aws.spring.sqs.SqsReceivedMessage.withFlociSignatureFixture() = copy(
        message = message.toBuilder()
            .body(
                body.dropLast(1) +
                    ",\"SignatureVersion\":\"1\",\"Signature\":\"floci-not-a-signature\"," +
                    "\"SigningCertURL\":\"https://sns.us-east-1.amazonaws.com/floci.pem\"}",
            )
            .build(),
    )

    private fun uniqueName(prefix: String): String = "$prefix-${UUID.randomUUID().toString().take(8)}"

    private fun withSqsResources(block: suspend (SqsAsyncClient, FlociCleanupStack) -> Unit) = runSuspendIO {
        val sqsClient = sqsClient()
        withCleanup { cleanup ->
            cleanup.register { sqsClient.close() }
            block(sqsClient, cleanup)
        }
    }

    private fun withSnsSqsResources(
        block: suspend (SnsAsyncClient, SqsAsyncClient, FlociCleanupStack) -> Unit,
    ) = runSuspendIO {
        val snsClient = snsClient()
        val sqsClient = sqsClient()
        withCleanup { cleanup ->
            cleanup.register { snsClient.close() }
            cleanup.register { sqsClient.close() }
            block(snsClient, sqsClient, cleanup)
        }
    }

    private suspend fun withCleanup(block: suspend (FlociCleanupStack) -> Unit) {
        val cleanup = FlociCleanupStack()
        var primary: Throwable? = null
        try {
            block(cleanup)
        } catch (failure: Throwable) {
            primary = failure
        } finally {
            cleanup.close(primary)
        }
        primary?.let { throw it }
    }

    private fun sqsClient(): SqsAsyncClient = SqsAsyncClient.builder()
        .credentialsProvider(awsEmulator.getCredentialProvider())
        .region(Region.of(awsEmulator.regionName))
        .endpointOverride(awsEmulator.awsEndpoint)
        .build()

    private fun snsClient(): SnsAsyncClient = SnsAsyncClient.builder()
        .credentialsProvider(awsEmulator.getCredentialProvider())
        .region(Region.of(awsEmulator.regionName))
        .endpointOverride(awsEmulator.awsEndpoint)
        .build()

    private companion object {
        const val TARGET_ALIAS = "events"
        const val EVENT_TYPE = "floci.event"
        val awsEmulator by lazy { AwsSpringBootTestEmulator.get("sns", "sqs") }
    }
}

private data class FlociEvent(
    val id: String,
    val payload: String,
    val trace: String,
)

private object FlociEventSerializer : EventSerializer {
    override fun serialize(event: Any): Any = (event as FlociEvent).let {
        """{"id":"${it.id}","payload":"${it.payload}","trace":"${it.trace}"}"""
    }

    override fun <T : Any> deserialize(serialized: Any, type: Class<T>): T {
        val json = serialized as String
        val event = FlociEvent(
            id = json.field("id"),
            payload = json.field("payload"),
            trace = json.field("trace"),
        )
        return type.cast(event)
    }

    private fun String.field(name: String): String =
        requireNotNull(Regex("\\\"$name\\\":\\\"([^\\\"]+)\\\"").find(this)?.groupValues?.get(1))
}

private class FlociEventHandler {
    val events = CopyOnWriteArrayList<FlociEvent>()

    @EventListener
    fun on(event: FlociEvent) {
        events += event
    }
}

private class RecordingAcknowledgement(
    private val delete: suspend () -> Unit,
) : SqsAcknowledgement {
    val acknowledgeCalls = AtomicInteger()
    override val completed: Boolean get() = acknowledgeCalls.get() > 0

    override suspend fun acknowledge() {
        acknowledgeCalls.incrementAndGet()
        delete()
    }

    override suspend fun nack(timeoutSeconds: Int) = Unit

    override suspend fun changeVisibility(timeoutSeconds: Int) = Unit
}

private class FlociCleanupStack(
    private val resourceTimeout: Duration = Duration.ofSeconds(10),
) {
    private val actions = ArrayDeque<suspend () -> Unit>()
    val pendingCount: Int get() = actions.size

    fun register(action: suspend () -> Unit) {
        actions.addLast(action)
    }

    suspend fun close(primary: Throwable? = null) {
        val cleanupFailures = mutableListOf<Throwable>()
        while (actions.isNotEmpty()) {
            val action = actions.removeLast()
            try {
                withTimeout(resourceTimeout.toMillis()) { action() }
            } catch (failure: Throwable) {
                cleanupFailures += FlociCleanupException(failure)
            }
        }
        if (primary != null) {
            cleanupFailures.forEach(primary::addSuppressed)
        } else if (cleanupFailures.isNotEmpty()) {
            val first = cleanupFailures.first()
            cleanupFailures.drop(1).forEach(first::addSuppressed)
            throw first
        }
    }
}

private class FlociCleanupException(cause: Throwable) : RuntimeException("Floci resource cleanup failed.", cause)
