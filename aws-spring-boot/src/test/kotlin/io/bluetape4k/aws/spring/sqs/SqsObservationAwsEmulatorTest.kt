package io.bluetape4k.aws.spring.sqs

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.core.read.ListAppender
import io.bluetape4k.aws.spring.AwsAutoConfiguration
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeGreaterOrEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.junit5.awaitility.untilSuspending
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.testcontainers.aws.FlociServer
import io.bluetape4k.testcontainers.aws.getCredentialProvider
import io.micrometer.observation.Observation
import io.micrometer.observation.ObservationHandler
import io.micrometer.observation.ObservationRegistry
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.delay
import kotlinx.coroutines.future.await
import org.awaitility.kotlin.await
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.ApplicationListener
import org.springframework.context.event.ContextClosedEvent
import org.springframework.context.event.ContextRefreshedEvent
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider
import software.amazon.awssdk.services.sqs.SqsAsyncClient
import software.amazon.awssdk.services.sqs.model.CreateQueueRequest
import software.amazon.awssdk.services.sqs.model.DeleteQueueRequest
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue
import software.amazon.awssdk.services.sqs.model.QueueAttributeName
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest
import software.amazon.awssdk.services.sqs.model.SendMessageRequest
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

@Timeout(30)
class SqsObservationAwsEmulatorTest {

    private val openContexts = AtomicInteger()
    private val listenerRegistries: MutableList<SqsMessageListenerContainerRegistry> = CopyOnWriteArrayList()

    companion object {
        private val floci: FlociServer by lazy { FlociServer.Launcher.floci }
        private const val SECRET_BODY: String = "body-secret-task8"
        private const val SECRET_ATTRIBUTE_NAME: String = "secret-attribute-task8"
        private const val SECRET_ATTRIBUTE_VALUE: String = "attribute-secret-task8"
        private const val SECRET_THROWABLE_TOKEN: String = "throwable-secret-task8"
    }

    @Test
    fun `Floci listener propagates process context and observes heartbeat plus manual acknowledgement`() {
        val recorder = ObservationRecorder()
        val registry = ObservationRegistry.create().apply {
            observationConfig().observationHandler(recorder)
        }
        val client = sqsClient()
        val queueUrl = createQueue(client, "observation")
        try {
            contextRunner(
                queueUrl,
                registry,
                recorder,
                ObservedListenerConfiguration::class.java,
                "bluetape4k.aws.sqs.listener.message-visibility-heartbeat-interval-seconds=1",
                "bluetape4k.aws.sqs.listener.message-visibility-heartbeat-seconds=3",
            ).run { context ->
                val listener = context.getBean(ObservedListener::class.java)

                sendSecretMessage(client, queueUrl)

                await.atMost(Duration.ofSeconds(10)).untilAsserted {
                    listener.currentProcessObservations.size shouldBeEqualTo 1
                    listener.childParentMatches.single().shouldBeTrue()
                    recorder.snapshots.count { it.stage == SqsObservationStage.PROCESS } shouldBeEqualTo 1
                }
                runSuspendIO {
                    await.atMost(Duration.ofSeconds(10)).untilSuspending {
                        context.getBean(SqsOperations::class.java)
                            .receive(queueUrl, maxMessages = 1, waitTimeSeconds = 1)
                            .isEmpty()
                    }
                }

                recorder.snapshots.count {
                    it.stage == SqsObservationStage.ACKNOWLEDGEMENT &&
                        it.acknowledgementAction == SqsAcknowledgementAction.CHANGE_VISIBILITY
                }.shouldBeGreaterOrEqualTo(1)
                recorder.snapshots.count {
                    it.stage == SqsObservationStage.ACKNOWLEDGEMENT &&
                        it.acknowledgementAction == SqsAcknowledgementAction.ACK
                } shouldBeEqualTo 1
                recorder.renderedTelemetry().let { rendered ->
                    listOf(SECRET_BODY, SECRET_ATTRIBUTE_NAME, SECRET_ATTRIBUTE_VALUE, queueUrl, "receipt")
                        .forEach { secret -> rendered.contains(secret).shouldBeFalse() }
                }
            }
            assertRegistryStopped(contextRunnerRegistry = registry)
        } finally {
            runSuspendIO {
                client.deleteQueue(DeleteQueueRequest.builder().queueUrl(queueUrl).build()).await()
            }
            client.close()
        }
    }

    @Test
    fun `Floci retry preserves delivery and records one retried process budget`() {
        val recorder = ObservationRecorder()
        val registry = observationRegistry(recorder)
        val client = sqsClient()
        val queueUrl = createQueue(client, "observation-retry")
        try {
            contextRunner(
                queueUrl,
                registry,
                recorder,
                RetryListenerConfiguration::class.java,
                "bluetape4k.aws.sqs.listener.retry.max-attempts=2",
                "bluetape4k.aws.sqs.listener.error-visibility-timeout-seconds=0",
            ).run { context ->
                val operations = context.getBean(SqsOperations::class.java)
                val listener = context.getBean(RetryListener::class.java)

                runSuspendIO { operations.send(queueUrl, "retry-once") }

                await.atMost(Duration.ofSeconds(10)).untilAsserted {
                    listener.attempts.get() shouldBeEqualTo 2
                    recorder.snapshots.count { it.stage == SqsObservationStage.PROCESS } shouldBeEqualTo 1
                }
                runSuspendIO {
                    await.atMost(Duration.ofSeconds(10)).untilSuspending {
                        operations.receive(queueUrl, maxMessages = 1, waitTimeSeconds = 1).isEmpty()
                    }
                }
                recorder.snapshots.single { it.stage == SqsObservationStage.PROCESS }.let { process ->
                    process.outcome shouldBeEqualTo SqsObservationOutcome.RETRIED
                    process.retryCount shouldBeEqualTo 1
                    process.attempt shouldBeEqualTo 2
                    process.delivery shouldBeEqualTo SqsObservationDelivery.FIRST
                }
                recorder.snapshots.count {
                    it.stage == SqsObservationStage.ACKNOWLEDGEMENT &&
                        it.acknowledgementAction == SqsAcknowledgementAction.ACK
                } shouldBeEqualTo 1
            }
            assertRegistryStopped(registry)
        } finally {
            deleteQueue(client, queueUrl)
            client.close()
        }
    }

    @Test
    fun `Floci error visibility preserves redelivery and records both process deliveries`() {
        val recorder = ObservationRecorder()
        val registry = observationRegistry(recorder)
        val client = sqsClient()
        val queueUrl = createQueue(client, "observation-redelivery")
        try {
            contextRunner(
                queueUrl,
                registry,
                recorder,
                RedeliveryListenerConfiguration::class.java,
                "bluetape4k.aws.sqs.listener.retry.max-attempts=1",
                "bluetape4k.aws.sqs.listener.error-visibility-timeout-seconds=0",
            ).run { context ->
                val operations = context.getBean(SqsOperations::class.java)
                val listener = context.getBean(RedeliveryListener::class.java)
                runSuspendIO { operations.send(queueUrl, "redeliver-once") }

                await.atMost(Duration.ofSeconds(10)).untilAsserted {
                    listener.attempts.get() shouldBeEqualTo 2
                    recorder.snapshots.count { it.stage == SqsObservationStage.PROCESS } shouldBeEqualTo 2
                }
                runSuspendIO {
                    await.atMost(Duration.ofSeconds(10)).untilSuspending {
                        operations.receive(queueUrl, maxMessages = 1, waitTimeSeconds = 1).isEmpty()
                    }
                }
                val processes = recorder.snapshots.filter { it.stage == SqsObservationStage.PROCESS }
                processes.map { it.outcome }.toSet() shouldBeEqualTo
                    setOf(SqsObservationOutcome.ERROR, SqsObservationOutcome.SUCCESS)
                processes.map { it.delivery }.toSet() shouldBeEqualTo
                    setOf(SqsObservationDelivery.FIRST, SqsObservationDelivery.REDELIVERED)
                recorder.snapshots.count {
                    it.stage == SqsObservationStage.ACKNOWLEDGEMENT &&
                        it.acknowledgementAction == SqsAcknowledgementAction.CHANGE_VISIBILITY
                } shouldBeEqualTo 1
                recorder.snapshots.count {
                    it.stage == SqsObservationStage.ACKNOWLEDGEMENT &&
                        it.acknowledgementAction == SqsAcknowledgementAction.ACK
                } shouldBeEqualTo 1
            }
            assertRegistryStopped(registry)
        } finally {
            deleteQueue(client, queueUrl)
            client.close()
        }
    }

    @Test
    fun `empty Floci poll records receive only and creates no process or acknowledgement observation`() {
        val recorder = ObservationRecorder()
        val registry = observationRegistry(recorder)
        val client = sqsClient()
        val queueUrl = createQueue(client, "observation-empty")
        try {
            contextRunner(queueUrl, registry, recorder, EmptyListenerConfiguration::class.java).run {
                await.atMost(Duration.ofSeconds(10)).untilAsserted {
                    recorder.snapshots.any { it.stage == SqsObservationStage.RECEIVE }.shouldBeTrue()
                }
                recorder.snapshots.none { it.stage == SqsObservationStage.PROCESS }.shouldBeTrue()
                recorder.snapshots.none { it.stage == SqsObservationStage.ACKNOWLEDGEMENT }.shouldBeTrue()
                recorder.snapshots.filter { it.stage == SqsObservationStage.RECEIVE }
                    .all { it.batchSize == 0 && it.highCardinality.isEmpty() }
                    .shouldBeTrue()
            }
            assertRegistryStopped(registry)
        } finally {
            deleteQueue(client, queueUrl)
            client.close()
        }
    }

    @Test
    fun `Floci FIFO identifiers remain single-message high-cardinality values`() {
        val recorder = ObservationRecorder()
        val registry = observationRegistry(recorder)
        val client = sqsClient()
        val queueUrl = createQueue(
            client,
            "observation-fifo",
            suffix = ".fifo",
            attributes = mapOf(QueueAttributeName.FIFO_QUEUE to "true"),
        )
        val groupId = "task8-group-${UUID.randomUUID()}"
        val deduplicationId = "task8-dedup-${UUID.randomUUID()}"
        try {
            runSuspendIO {
                client.sendMessage(
                    SendMessageRequest.builder()
                        .queueUrl(queueUrl)
                        .messageBody("fifo-message")
                        .messageGroupId(groupId)
                        .messageDeduplicationId(deduplicationId)
                        .build(),
                ).await()
            }

            contextRunner(queueUrl, registry, recorder, FifoListenerConfiguration::class.java).run { context ->
                val listener = context.getBean(FifoListener::class.java)
                await.atMost(Duration.ofSeconds(10)).untilAsserted {
                    listener.messages.size shouldBeEqualTo 1
                    recorder.snapshots.count { it.stage == SqsObservationStage.PROCESS } shouldBeEqualTo 1
                }
                val process = recorder.snapshots.single { it.stage == SqsObservationStage.PROCESS }
                val received = listener.messages.single()
                process.highCardinality["messaging.message.id"] shouldBeEqualTo received.messageId
                process.highCardinality["messaging.sqs.message.group.id"] shouldBeEqualTo groupId
                process.highCardinality["messaging.sqs.message.deduplication.id"] shouldBeEqualTo deduplicationId
                recorder.snapshots.flatMap { it.lowCardinality.values }.let { lowValues ->
                    lowValues.contains(received.messageId).shouldBeFalse()
                    lowValues.contains(groupId).shouldBeFalse()
                    lowValues.contains(deduplicationId).shouldBeFalse()
                }
                recorder.snapshots.filter { it.batch }.forEach { it.highCardinality.isEmpty().shouldBeTrue() }
            }
            assertRegistryStopped(registry)
        } finally {
            deleteQueue(client, queueUrl)
            client.close()
        }
    }

    @Test
    fun `Floci manual batch partial acknowledgement deletes only selected message and records partial counts`() {
        val recorder = ObservationRecorder()
        val registry = observationRegistry(recorder)
        val client = sqsClient()
        val queueUrl = createQueue(
            client,
            "observation-batch",
            attributes = mapOf(QueueAttributeName.VISIBILITY_TIMEOUT to "2"),
        )
        try {
            runSuspendIO {
                repeat(2) { index ->
                    client.sendMessage(
                        SendMessageRequest.builder().queueUrl(queueUrl).messageBody("batch-$index").build(),
                    ).await()
                }
            }

            contextRunner(
                queueUrl,
                registry,
                recorder,
                BatchListenerConfiguration::class.java,
                "bluetape4k.aws.sqs.listener.max-messages=10",
            ).run { context ->
                val listener = context.getBean(PartialBatchListener::class.java)
                await.atMost(Duration.ofSeconds(10)).untilAsserted {
                    listener.result.size shouldBeEqualTo 1
                    listener.batchSize shouldBeEqualTo 2
                }
                val process = recorder.snapshots.single { it.stage == SqsObservationStage.PROCESS }
                process.outcome shouldBeEqualTo SqsObservationOutcome.PARTIAL
                process.batchSize shouldBeEqualTo 2
                process.highCardinality.isEmpty().shouldBeTrue()
                val acknowledgement = recorder.snapshots.single {
                    it.stage == SqsObservationStage.ACKNOWLEDGEMENT &&
                        it.acknowledgementAction == SqsAcknowledgementAction.ACK
                }
                acknowledgement.acknowledgementSuccessCount shouldBeEqualTo 1
                acknowledgement.acknowledgementFailureCount shouldBeEqualTo 0
                acknowledgement.highCardinality.isEmpty().shouldBeTrue()
            }
            assertRegistryStopped(registry)

            val remaining = receiveSingleVisibleMessage(client, queueUrl)
            remaining.single().body().shouldBeEqualTo("batch-0".takeUnless {
                it == PartialBatchListener.acknowledgedBody
            } ?: "batch-1")
        } finally {
            deleteQueue(client, queueUrl)
            client.close()
        }
    }

    @Test
    fun `Floci acknowledgement I-O failure records one failed acknowledgement observation`() {
        val recorder = ObservationRecorder()
        val registry = observationRegistry(recorder)
        val client = sqsClient()
        val queueUrl = createQueue(client, "observation-ack-failure")
        AckFailureListener.queueUrl = queueUrl
        try {
            contextRunner(queueUrl, registry, recorder, AckFailureListenerConfiguration::class.java).run { context ->
                val operations = context.getBean(SqsOperations::class.java)
                val listener = context.getBean(AckFailureListener::class.java)
                runSuspendIO { operations.send(queueUrl, "ack-failure") }

                await.atMost(Duration.ofSeconds(10)).untilAsserted {
                    (listener.failure != null).shouldBeTrue()
                    recorder.snapshots.count {
                        it.stage == SqsObservationStage.ACKNOWLEDGEMENT &&
                            it.acknowledgementAction == SqsAcknowledgementAction.ACK
                    } shouldBeEqualTo 1
                    recorder.snapshots.count { it.stage == SqsObservationStage.PROCESS } shouldBeEqualTo 1
                }
                recorder.snapshots.single {
                    it.stage == SqsObservationStage.ACKNOWLEDGEMENT &&
                        it.acknowledgementAction == SqsAcknowledgementAction.ACK
                }.let { acknowledgement ->
                    acknowledgement.outcome shouldBeEqualTo SqsObservationOutcome.ERROR
                    acknowledgement.acknowledgementSuccessCount shouldBeEqualTo 0
                    acknowledgement.acknowledgementFailureCount shouldBeEqualTo 1
                }
            }
            assertRegistryStopped(registry)
        } finally {
            runCatching { deleteQueue(client, queueUrl) }
            client.close()
            AckFailureListener.queueUrl = null
        }
    }

    @Test
    fun `heartbeat telemetry cleanup failure logs only bounded OBS-202 and preserves Floci delivery`() {
        val recorder = ObservationRecorder()
        val registry = observationRegistry(recorder).apply {
            observationConfig().observationHandler(SecretHeartbeatCleanupFailureHandler)
        }
        val client = sqsClient()
        val queueUrl = createQueue(client, "observation-cleanup")
        val logger = LoggerFactory.getLogger(SqsMessageListenerContainer::class.java) as Logger
        val appender = ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>().apply { start() }
        val previousLevel = logger.level
        logger.addAppender(appender)
        logger.level = Level.WARN
        try {
            contextRunner(
                queueUrl,
                registry,
                recorder,
                CleanupFailureListenerConfiguration::class.java,
                "bluetape4k.aws.sqs.listener.message-visibility-heartbeat-interval-seconds=1",
                "bluetape4k.aws.sqs.listener.message-visibility-heartbeat-seconds=3",
            ).run { context ->
                val operations = context.getBean(SqsOperations::class.java)
                val listener = context.getBean(CleanupFailureListener::class.java)
                runSuspendIO { operations.send(queueUrl, "cleanup-message") }

                await.atMost(Duration.ofSeconds(10)).untilAsserted {
                    listener.completed.shouldBeTrue()
                    appender.list.any { it.formattedMessage.contains("BT4K-SQS-OBS-202") }.shouldBeTrue()
                    recorder.snapshots.count { it.stage == SqsObservationStage.PROCESS } shouldBeEqualTo 1
                }
                runSuspendIO {
                    await.atMost(Duration.ofSeconds(10)).untilSuspending {
                        operations.receive(queueUrl, maxMessages = 1, waitTimeSeconds = 1).isEmpty()
                    }
                }
            }

            val diagnosticEvents = appender.list.filter { it.formattedMessage.contains("BT4K-SQS-OBS-202") }
            diagnosticEvents.isNotEmpty().shouldBeTrue()
            diagnosticEvents.forEach { event ->
                event.formattedMessage.contains("stage=acknowledgement").shouldBeTrue()
                event.formattedMessage.contains("action=change_visibility").shouldBeTrue()
                event.formattedMessage.contains("reason=telemetry_cleanup").shouldBeTrue()
                event.formattedMessage.contains(SECRET_THROWABLE_TOKEN).shouldBeFalse()
                (event.throwableProxy == null).shouldBeTrue()
            }
            assertRegistryStopped(registry)
        } finally {
            logger.detachAppender(appender)
            logger.level = previousLevel
            deleteQueue(client, queueUrl)
            client.close()
        }
    }

    @Test
    fun `queue resolution failure logs only bounded OBS-201 without secret throwable state`() {
        val recorder = ObservationRecorder()
        val registry = observationRegistry(recorder)
        val logger = LoggerFactory.getLogger(SqsMessageListenerContainer::class.java) as Logger
        val appender = ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>().apply { start() }
        val previousLevel = logger.level
        logger.addAppender(appender)
        logger.level = Level.WARN
        try {
            contextRunner(
                "secret-queue-name-task8",
                registry,
                recorder,
                FailingResolutionListenerConfiguration::class.java,
                "bluetape4k.aws.sqs.listener.retry.initial-backoff=10s",
            ).run {
                await.atMost(Duration.ofSeconds(10)).untilAsserted {
                    appender.list.any { it.formattedMessage.contains("BT4K-SQS-OBS-201") }.shouldBeTrue()
                }
            }

            val warningText = appender.list.joinToString("\n") { it.formattedMessage }
            warningText.contains("BT4K-SQS-OBS-201").shouldBeTrue()
            warningText.contains("stage=resolution").shouldBeTrue()
            warningText.contains(SECRET_THROWABLE_TOKEN).shouldBeFalse()
            warningText.contains("secret-queue-name-task8").shouldBeFalse()
            appender.list.none { it.throwableProxy != null }.shouldBeTrue()
            assertRegistryStopped(registry)
        } finally {
            logger.detachAppender(appender)
            logger.level = previousLevel
        }
    }

    @Test
    fun `extension failures preserve throwable identity and business errors are redacted for telemetry`() {
        val customizerFailure = IllegalStateException("$SECRET_THROWABLE_TOKEN-customizer")
        val customizerRuntime = observationRuntime(
            customizers = listOf(failingCustomizer(customizerFailure)),
        )
        (captureFailure { customizerRuntime.observe<Unit>(processContext()) {} } === customizerFailure).shouldBeTrue()

        val factoryFailure = IllegalArgumentException("$SECRET_THROWABLE_TOKEN-factory")
        val factoryRuntime = observationRuntime(
            factory = failingFactory(factoryFailure),
        )
        (captureFailure { factoryRuntime.observe<Unit>(processContext()) {} } === factoryFailure).shouldBeTrue()

        val conventionFailure = UnsupportedOperationException("$SECRET_THROWABLE_TOKEN-convention")
        val conventionRuntime = observationRuntime(
            factory = defaultSqsObservationFactory(
                defaultSqsObservationConventions() +
                    (SqsObservationStage.PROCESS to failingConvention(conventionFailure)),
            ),
        )
        (captureFailure { conventionRuntime.observe<Unit>(processContext()) {} } === conventionFailure).shouldBeTrue()

        val handlerFailure = IllegalStateException("$SECRET_THROWABLE_TOKEN-handler")
        val handlerRegistry = ObservationRegistry.create().apply {
            observationConfig().observationHandler(failingHandler(handlerFailure))
        }
        val handlerRuntime = observationRuntime(registry = handlerRegistry)
        (captureFailure { handlerRuntime.observe<Unit>(processContext()) {} } === handlerFailure).shouldBeTrue()

        val telemetryRecorder = TelemetryErrorRecorder()
        val businessRegistry = ObservationRegistry.create().apply {
            observationConfig().observationHandler(telemetryRecorder)
        }
        val businessFailure = IllegalStateException("$SECRET_THROWABLE_TOKEN-business")
        val businessRuntime = observationRuntime(registry = businessRegistry)
        (captureFailure { businessRuntime.observe(processContext()) { throw businessFailure } } === businessFailure)
            .shouldBeTrue()
        val telemetryError = telemetryRecorder.error
        (telemetryError is SqsObservationTelemetryException).shouldBeTrue()
        telemetryError?.message shouldBeEqualTo null
        telemetryError?.cause shouldBeEqualTo null
        telemetryError?.stackTrace?.isEmpty().shouldBeEqualTo(true)
        telemetryError.toString().contains(SECRET_THROWABLE_TOKEN).shouldBeFalse()
        businessRegistry.currentObservation shouldBeEqualTo null
    }

    private fun contextRunner(
        queueUrl: String,
        registry: ObservationRegistry,
        recorder: ObservationRecorder,
        userConfiguration: Class<*>,
        vararg properties: String,
    ): ApplicationContextRunner =
        ApplicationContextRunner()
            .withConfiguration(
                AutoConfigurations.of(
                    AwsAutoConfiguration::class.java,
                    SqsObservationAutoConfiguration::class.java,
                    SqsAutoConfiguration::class.java,
                    SqsJacksonMessageConverterAutoConfiguration::class.java,
                ),
            )
            .withInitializer { applicationContext ->
                openContexts.incrementAndGet()
                applicationContext.addApplicationListener(
                    ApplicationListener<ContextRefreshedEvent> { event ->
                        listenerRegistries += event.applicationContext
                            .getBean(SqsMessageListenerContainerRegistry::class.java)
                    },
                )
                applicationContext.addApplicationListener(
                    ApplicationListener<ContextClosedEvent> {
                        openContexts.decrementAndGet()
                    },
                )
            }
            .withUserConfiguration(userConfiguration)
            .withBean(AwsCredentialsProvider::class.java, { floci.getCredentialProvider() })
            .withBean(ObservationRegistry::class.java, { registry })
            .withBean(ObservationHandler::class.java, { recorder })
            .withPropertyValues(
                "bluetape4k.aws.sqs.region=${floci.regionName}",
                "bluetape4k.aws.sqs.endpoint-override=${floci.awsEndpoint}",
                "bluetape4k.aws.sqs.listener.max-messages=1",
                "bluetape4k.aws.sqs.listener.wait-time-seconds=1",
                "bluetape4k.aws.sqs.listener.stop-timeout-millis=5000",
                "bluetape4k.aws.sqs.observation.enabled=true",
                "test.queue-url=$queueUrl",
                *properties,
            )

    private fun observationRegistry(recorder: ObservationRecorder): ObservationRegistry =
        ObservationRegistry.create().apply { observationConfig().observationHandler(recorder) }

    private fun observationRuntime(
        registry: ObservationRegistry = ObservationRegistry.create().apply {
            observationConfig().observationHandler(ObservationRecorder())
        },
        customizers: List<SqsObservationContextCustomizer> = emptyList(),
        factory: SqsObservationFactory = defaultSqsObservationFactory(defaultSqsObservationConventions()),
    ): SqsObservationRuntime = SqsObservationRuntime(registry, customizers, factory)

    private fun processContext(): SqsObservationContext =
        SqsObservationContext(
            SqsObservationMetadata(
                listenerId = "task8-runtime",
                queueName = "orders",
                stage = SqsObservationStage.PROCESS,
                batch = false,
                initialAttempt = 1,
                batchSize = 1,
            ),
        )

    private fun captureFailure(block: suspend () -> Unit): Throwable {
        var failure: Throwable? = null
        runSuspendIO { failure = runCatching { block() }.exceptionOrNull() }
        return checkNotNull(failure)
    }

    private fun failingCustomizer(failure: Throwable): SqsObservationContextCustomizer =
        SqsObservationContextCustomizer { throw failure }

    private fun failingFactory(failure: Throwable): SqsObservationFactory =
        SqsObservationFactory { _, _ -> throw failure }

    private fun failingConvention(failure: Throwable): SqsObservationConvention =
        object : SqsObservationConvention {
            override val stage: SqsObservationStage = SqsObservationStage.PROCESS
            override fun getName(): String = throw failure
        }

    private fun failingHandler(failure: Throwable): ObservationHandler<SqsObservationContext> =
        object : ObservationHandler<SqsObservationContext> {
            override fun supportsContext(context: Observation.Context): Boolean = context is SqsObservationContext
            override fun onStart(context: SqsObservationContext) = throw failure
        }

    private fun sendSecretMessage(client: SqsAsyncClient, queueUrl: String) {
        runSuspendIO {
            client.sendMessage(
                SendMessageRequest.builder()
                    .queueUrl(queueUrl)
                    .messageBody(SECRET_BODY)
                    .messageAttributes(
                        mapOf(
                            SECRET_ATTRIBUTE_NAME to MessageAttributeValue.builder()
                                .dataType("String")
                                .stringValue(SECRET_ATTRIBUTE_VALUE)
                                .build(),
                        ),
                    )
                    .build(),
            ).await()
        }
    }

    private fun receiveSingleVisibleMessage(
        client: SqsAsyncClient,
        queueUrl: String,
    ): List<software.amazon.awssdk.services.sqs.model.Message> {
        lateinit var remaining: List<software.amazon.awssdk.services.sqs.model.Message>
        runSuspendIO {
            await.atMost(Duration.ofSeconds(10)).untilSuspending {
                val received = client.receiveMessage(
                    ReceiveMessageRequest.builder()
                        .queueUrl(queueUrl)
                        .maxNumberOfMessages(10)
                        .waitTimeSeconds(1)
                        .visibilityTimeout(0)
                        .build(),
                ).await().messages()
                if (received.size == 1) remaining = received
                received.size == 1
            }
        }
        return remaining
    }

    private fun createQueue(
        client: SqsAsyncClient,
        prefix: String,
        suffix: String = "",
        attributes: Map<QueueAttributeName, String> = emptyMap(),
    ): String {
        lateinit var queueUrl: String
        runSuspendIO {
            queueUrl = client.createQueue(
                CreateQueueRequest.builder()
                    .queueName("$prefix-${UUID.randomUUID()}$suffix")
                    .attributes(attributes)
                    .build(),
            ).await().queueUrl()
        }
        return queueUrl
    }

    private fun deleteQueue(client: SqsAsyncClient, queueUrl: String) {
        runSuspendIO { client.deleteQueue(DeleteQueueRequest.builder().queueUrl(queueUrl).build()).await() }
    }

    private fun sqsClient(): SqsAsyncClient =
        SqsAsyncClient.builder()
            .endpointOverride(floci.awsEndpoint)
            .region(software.amazon.awssdk.regions.Region.of(floci.regionName))
            .credentialsProvider(floci.getCredentialProvider())
            .build()

    @Configuration(proxyBeanMethods = false)
    internal class ObservedListenerConfiguration {
        @Bean
        fun observedListener(registry: ObservationRegistry): ObservedListener = ObservedListener(registry)
    }

    internal class ObservedListener(
        private val registry: ObservationRegistry,
    ) {
        val currentProcessObservations: MutableList<Observation> = CopyOnWriteArrayList()
        val childParentMatches: MutableList<Boolean> = CopyOnWriteArrayList()

        @SqsListener(queue = "\${test.queue-url}", id = "observed-listener")
        suspend fun handle(
            @Suppress("UNUSED_PARAMETER") message: SqsReceivedMessage,
            acknowledgement: SqsAcknowledgement,
        ) {
            val process = checkNotNull(registry.currentObservation)
            currentProcessObservations += process
            val child = Observation.start("task8-child", registry)
            childParentMatches += child.context.parentObservation === process
            child.stop()
            delay(1_500)
            acknowledgement.acknowledge()
        }
    }

    @Configuration(proxyBeanMethods = false)
    internal class RetryListenerConfiguration {
        @Bean
        fun retryListener(): RetryListener = RetryListener()
    }

    internal class RetryListener {
        val attempts = java.util.concurrent.atomic.AtomicInteger()

        @SqsListener(queue = "\${test.queue-url}", id = "retry-listener")
        fun handle(@Suppress("UNUSED_PARAMETER") body: String) {
            if (attempts.incrementAndGet() == 1) error("retry once")
        }
    }

    @Configuration(proxyBeanMethods = false)
    internal class RedeliveryListenerConfiguration {
        @Bean
        fun redeliveryListener(): RedeliveryListener = RedeliveryListener()
    }

    internal class RedeliveryListener {
        val attempts = AtomicInteger()

        @SqsListener(queue = "\${test.queue-url}", id = "redelivery-listener")
        fun handle(@Suppress("UNUSED_PARAMETER") body: String) {
            if (attempts.incrementAndGet() == 1) error("redeliver once")
        }
    }

    @Configuration(proxyBeanMethods = false)
    internal class EmptyListenerConfiguration {
        @Bean
        fun emptyListener(): EmptyListener = EmptyListener()
    }

    internal class EmptyListener {
        @SqsListener(queue = "\${test.queue-url}", id = "empty-listener")
        fun handle(@Suppress("UNUSED_PARAMETER") body: String) = Unit
    }

    @Configuration(proxyBeanMethods = false)
    internal class FifoListenerConfiguration {
        @Bean
        fun fifoListener(): FifoListener = FifoListener()
    }

    internal class FifoListener {
        val messages: MutableList<SqsReceivedMessage> = CopyOnWriteArrayList()

        @SqsListener(queue = "\${test.queue-url}", id = "fifo-listener")
        fun handle(message: SqsReceivedMessage) {
            messages += message
        }
    }

    @Configuration(proxyBeanMethods = false)
    internal class BatchListenerConfiguration {
        @Bean
        fun partialBatchListener(): PartialBatchListener = PartialBatchListener()
    }

    internal class PartialBatchListener {
        companion object {
            @Volatile
            var acknowledgedBody: String? = null
        }

        val result: MutableList<SqsBatchAcknowledgementResult> = CopyOnWriteArrayList()
        @Volatile
        var batchSize: Int = 0

        @SqsListener(
            queue = "\${test.queue-url}",
            id = "partial-batch-listener",
            batch = true,
            maxMessages = 10,
            acknowledgementMode = SqsAcknowledgementMode.MANUAL,
        )
        suspend fun handle(messages: List<SqsReceivedMessage>, acknowledgement: SqsBatchAcknowledgement) {
            batchSize = messages.size
            acknowledgedBody = messages.first().body
            result += acknowledgement.acknowledge(listOf(messages.first()))
        }
    }

    @Configuration(proxyBeanMethods = false)
    internal class AckFailureListenerConfiguration {
        @Bean
        fun ackFailureListener(client: SqsAsyncClient): AckFailureListener = AckFailureListener(client)
    }

    internal class AckFailureListener(
        private val client: SqsAsyncClient,
    ) {
        companion object {
            @Volatile
            var queueUrl: String? = null
        }

        @Volatile
        var failure: Throwable? = null

        @SqsListener(
            queue = "\${test.queue-url}",
            id = "ack-failure-listener",
            acknowledgementMode = SqsAcknowledgementMode.MANUAL,
        )
        suspend fun handle(
            @Suppress("UNUSED_PARAMETER") body: String,
            acknowledgement: SqsAcknowledgement,
        ) {
            client.deleteQueue(
                DeleteQueueRequest.builder().queueUrl(checkNotNull(queueUrl)).build(),
            ).await()
            failure = runCatching { acknowledgement.acknowledge() }.exceptionOrNull()
        }
    }

    @Configuration(proxyBeanMethods = false)
    internal class CleanupFailureListenerConfiguration {
        @Bean
        fun cleanupFailureListener(): CleanupFailureListener = CleanupFailureListener()
    }

    internal class CleanupFailureListener {
        @Volatile
        var completed: Boolean = false

        @SqsListener(queue = "\${test.queue-url}", id = "cleanup-failure-listener")
        suspend fun handle(@Suppress("UNUSED_PARAMETER") body: String) {
            delay(1_500)
            completed = true
        }
    }

    private object SecretHeartbeatCleanupFailureHandler : ObservationHandler<SqsObservationContext> {
        override fun supportsContext(context: Observation.Context): Boolean = context is SqsObservationContext

        override fun onStop(context: SqsObservationContext) {
            if (
                context.metadata.stage == SqsObservationStage.ACKNOWLEDGEMENT &&
                context.metadata.acknowledgementAction == SqsAcknowledgementAction.CHANGE_VISIBILITY
            ) {
                throw IllegalStateException(SECRET_THROWABLE_TOKEN)
            }
        }
    }

    @Configuration(proxyBeanMethods = false)
    internal class FailingResolutionListenerConfiguration {
        @Bean
        fun failingOperations(): SqsOperations = mockk {
            coEvery { getQueueUrl(any()) } throws IllegalStateException(SECRET_THROWABLE_TOKEN)
        }

        @Bean
        fun failingResolutionListener(): FailingResolutionListener = FailingResolutionListener()
    }

    internal class FailingResolutionListener {
        @SqsListener(queue = "\${test.queue-url}", id = "resolution-failure-listener")
        fun handle(@Suppress("UNUSED_PARAMETER") body: String) = Unit
    }

    private data class ObservationSnapshot(
        val stage: SqsObservationStage,
        val outcome: SqsObservationOutcome,
        val retryCount: Int,
        val attempt: Int?,
        val batch: Boolean,
        val batchSize: Int,
        val delivery: SqsObservationDelivery,
        val acknowledgementAction: SqsAcknowledgementAction?,
        val acknowledgementSuccessCount: Int,
        val acknowledgementFailureCount: Int,
        val lowCardinality: Map<String, String>,
        val highCardinality: Map<String, String>,
        val contextText: String,
    )

    private class ObservationRecorder : ObservationHandler<SqsObservationContext> {
        val snapshots: MutableList<ObservationSnapshot> = CopyOnWriteArrayList()

        override fun supportsContext(context: Observation.Context): Boolean = context is SqsObservationContext

        override fun onStop(context: SqsObservationContext) {
            val convention = defaultSqsObservationConventions().getValue(context.metadata.stage)
            snapshots += ObservationSnapshot(
                stage = context.metadata.stage,
                outcome = context.outcome,
                retryCount = context.retryCount,
                attempt = context.attempt,
                batch = context.metadata.batch,
                batchSize = context.metadata.batchSize,
                delivery = context.metadata.delivery,
                acknowledgementAction = context.metadata.acknowledgementAction,
                acknowledgementSuccessCount = context.acknowledgementSuccessCount,
                acknowledgementFailureCount = context.acknowledgementFailureCount,
                lowCardinality = convention.getLowCardinalityKeyValues(context).associate { it.key to it.value },
                highCardinality = convention.getHighCardinalityKeyValues(context).associate { it.key to it.value },
                contextText = context.toString(),
            )
        }

        fun renderedTelemetry(): String = snapshots.joinToString("\n")
    }

    private class TelemetryErrorRecorder : ObservationHandler<SqsObservationContext> {
        var error: Throwable? = null

        override fun supportsContext(context: Observation.Context): Boolean = context is SqsObservationContext

        override fun onError(context: SqsObservationContext) {
            error = context.error
        }
    }

    private fun assertRegistryStopped(contextRunnerRegistry: ObservationRegistry) {
        contextRunnerRegistry.currentObservation shouldBeEqualTo null
        openContexts.get() shouldBeEqualTo 0
        listenerRegistries.last().let { listenerRegistry ->
            listenerRegistry.isRunning.shouldBeFalse()
            listenerRegistry.containers.none { it.isRunning }.shouldBeTrue()
        }
    }

}
