package io.bluetape4k.aws.spring.sqs

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.core.read.ListAppender
import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeGreaterOrEqualTo
import io.bluetape4k.assertions.shouldBeInstanceOf
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.assertions.shouldNotContain
import io.bluetape4k.concurrent.awaitTermination
import io.bluetape4k.coroutines.support.awaitUntil
import io.bluetape4k.javatimes.millis
import io.bluetape4k.javatimes.seconds
import io.bluetape4k.junit5.awaitility.untilSuspending
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.debug
import io.micrometer.observation.Observation
import io.micrometer.observation.ObservationHandler
import io.micrometer.observation.ObservationRegistry
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.awaitility.kotlin.atMost
import org.awaitility.kotlin.await
import org.awaitility.kotlin.until
import org.awaitility.kotlin.withPollInterval
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.RepeatedTest
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import software.amazon.awssdk.services.sqs.model.ChangeMessageVisibilityResponse
import software.amazon.awssdk.services.sqs.model.DeleteMessageResponse
import software.amazon.awssdk.services.sqs.model.Message
import software.amazon.awssdk.services.sqs.model.MessageSystemAttributeName
import software.amazon.awssdk.services.sqs.model.QueueDoesNotExistException
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@Suppress("LargeClass")
class SqsMessageListenerContainerTest {

    companion object: KLoggingChannel() {
        private const val QUEUE_URL = "https://sqs.local/orders"
    }

    private val operations = mockk<SqsOperations>()
    private val invoker = mockk<SqsListenerMethodInvoker>()

    @BeforeEach
    fun beforeEach() {
        clearMocks(operations, invoker)
    }

    @Test
    fun `enabled runtime records one receive and one process per single message`() = runSuspendIO {
        val messages = listOf(message(), message("message-2"))
        val receiveCalls = AtomicInteger()
        val handlersStarted = AtomicInteger()
        val allHandlersStarted = CompletableDeferred<Unit>()
        val releaseHandlers = CompletableDeferred<Unit>()

        coEvery {
            operations.receive(QUEUE_URL, 2, 0, null)
        } coAnswers {
            if (receiveCalls.incrementAndGet() == 1) messages else awaitCancellation()
        }
        every { invoker.manualAcknowledgement } returns true
        coEvery {
            invoker.invoke(any(), any(), any())
        } coAnswers {
            if (handlersStarted.incrementAndGet() == messages.size) {
                allHandlersStarted.complete(Unit)
            }
            releaseHandlers.await()
        }
        val recorder = ContainerObservationRecorder()
        val container = container(operations, invoker, maxMessages = 2, maxInFlight = 2)
        container.setObservationRuntime(observationRuntime(recorder))

        container.start()
        allHandlersStarted.awaitUntil(2.seconds)
        val stopped = CompletableDeferred<Unit>()
        container.stop { stopped.complete(Unit) }
        releaseHandlers.complete(Unit)
        stopped.awaitUntil(2.seconds)

        recorder.snapshots.count { it.stage == SqsObservationStage.RECEIVE } shouldBeEqualTo 1
        recorder.snapshots.count { it.stage == SqsObservationStage.PROCESS } shouldBeEqualTo 2
        recorder.snapshots.filter { it.stage == SqsObservationStage.PROCESS }
            .map { it.outcome }
            .toSet() shouldBeEqualTo setOf(SqsObservationOutcome.SUCCESS)
    }

    @Test
    fun `stop closes receive admission before a suspended interceptor reaches SQS`() = runSuspendIO {
        val beforeReceiveStarted = CompletableDeferred<Unit>()
        val releaseBeforeReceive = CompletableDeferred<Unit>()
        val interceptor = object: SqsListenerInterceptor {
            override suspend fun beforeReceive(listenerId: String, queueUrl: String) {
                beforeReceiveStarted.complete(Unit)
                withContext(NonCancellable) {
                    releaseBeforeReceive.await()
                }
            }
        }
        coEvery {
            operations.receive(QUEUE_URL, 1, 0, null)
        } returns emptyList()

        val container = container(operations, invoker, interceptors = listOf(interceptor))

        container.start()
        beforeReceiveStarted.awaitUntil(2.seconds)
        val stopped = CompletableDeferred<Unit>()
        container.stop { stopped.complete(Unit) }
        releaseBeforeReceive.complete(Unit)
        stopped.awaitUntil(2.seconds)

        coVerify(exactly = 0) {
            operations.receive(QUEUE_URL, 1, 0, null)
        }
    }

    @Test
    fun `retry stays inside one process observation and records one event`() = runSuspendIO {
        val operations = mockk<SqsOperations>()
        val invoker = mockk<SqsListenerMethodInvoker>()
        val handlerCalls = AtomicInteger()
        val retriedHandlerStarted = CompletableDeferred<Unit>()
        val releaseHandler = CompletableDeferred<Unit>()
        val failure = IllegalStateException("retry")
        coEvery {
            operations.receive(QUEUE_URL, 1, 0, null)
        } returns listOf(message())
        every { invoker.manualAcknowledgement } returns true
        coEvery {
            invoker.invoke(any(), any(), any())
        } coAnswers {
            if (handlerCalls.incrementAndGet() == 1) {
                throw failure
            }
            retriedHandlerStarted.complete(Unit)
            releaseHandler.await()
        }
        val recorder = ContainerObservationRecorder()
        val container = container(
            operations,
            invoker,
            retry = SqsProperties.Retry(maxAttempts = 2, initialBackoff = Duration.ZERO),
        )
        container.setObservationRuntime(observationRuntime(recorder))

        container.start()
        retriedHandlerStarted.awaitUntil(2.seconds)
        val stopped = CompletableDeferred<Unit>()
        container.stop { stopped.complete(Unit) }
        releaseHandler.complete(Unit)
        stopped.awaitUntil(2.seconds)

        val process = recorder.snapshots.single { it.stage == SqsObservationStage.PROCESS }
        log.debug { "process=$process" }
        process.outcome shouldBeEqualTo SqsObservationOutcome.RETRIED
        process.retryCount shouldBeEqualTo 1
        process.attempt shouldBeEqualTo 2
        recorder.retryEvents.get() shouldBeEqualTo 1
    }

    @Test
    fun `batch size one process observation never exposes message identifiers`() = runSuspendIO {
        val handlerStarted = CompletableDeferred<Unit>()
        val releaseHandler = CompletableDeferred<Unit>()

        coEvery {
            operations.receive(QUEUE_URL, 1, 0, null)
        } returns listOf(message(receiveCount = "2"))
        coEvery {
            invoker.invokeBatch(any(), anyNullable<SqsBatchAcknowledgement>(), any())
        } coAnswers {
            handlerStarted.complete(Unit)
            releaseHandler.await()
        }
        val recorder = ContainerObservationRecorder()
        val container = container(
            operations,
            invoker,
            batch = true,
            acknowledgementMode = SqsAcknowledgementMode.MANUAL,
        )
        container.setObservationRuntime(observationRuntime(recorder))

        container.start()
        handlerStarted.awaitUntil(2.seconds)
        val stopped = CompletableDeferred<Unit>()
        container.stop { stopped.complete(Unit) }
        releaseHandler.complete(Unit)
        stopped.awaitUntil(2.seconds)

        val process = recorder.snapshots.single { it.stage == SqsObservationStage.PROCESS }
        log.debug { "process=$process" }
        process.batch.shouldBeTrue()
        process.batchSize shouldBeEqualTo 1
        process.messageId.shouldBeNull()
        process.messageGroupId.shouldBeNull()
        process.messageDeduplicationId.shouldBeNull()
        process.delivery shouldBeEqualTo SqsObservationDelivery.UNKNOWN
    }

    @Test
    fun `queue URL resolution failure happens before receive observation`() = runSuspendIO {
        val lookupAttempted = CompletableDeferred<Unit>()

        coEvery {
            operations.getQueueUrl("orders")
        } coAnswers {
            lookupAttempted.complete(Unit)
            error("lookup failed")
        }
        val recorder = ContainerObservationRecorder()
        val container = container(
            operations,
            invoker,
            queue = "orders",
            retry = SqsProperties.Retry(initialBackoff = 10.seconds()),
        )
        container.setObservationRuntime(observationRuntime(recorder))

        container.start()
        lookupAttempted.awaitUntil(2.seconds)
        val stopped = CompletableDeferred<Unit>()
        container.stop { stopped.complete(Unit) }
        stopped.awaitUntil(2.seconds)

        recorder.snapshots shouldBeEqualTo emptyList()
    }

    @Test
    fun `queue not found strategy emits bounded observation diagnostic`() = runSuspendIO {
        val containerLogger = LoggerFactory.getLogger(SqsMessageListenerContainer::class.java) as Logger
        val appender = ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>().apply { start() }
        val previousLevel = containerLogger.level
        containerLogger.addAppender(appender)
        containerLogger.level = Level.WARN
        try {
            coEvery {
                operations.getQueueUrl("orders")
            } throws QueueDoesNotExistException.builder().message("queue-secret").build()
            every { invoker.manualAcknowledgement } returns false

            val container = container(
                operations,
                invoker,
                queue = "orders",
                queueNotFoundStrategy = SqsQueueNotFoundStrategy.IGNORE,
            )

            container.start()
            await atMost 2.seconds until {
                !container.isRunning
            }

            appender.list.map { it.formattedMessage }
                .any { it.contains("BT4K-SQS-OBS-201") && it.contains("stage=resolution") }
                .shouldBeTrue()
        } finally {
            containerLogger.detachAppender(appender)
            containerLogger.level = previousLevel
        }
    }

    @Test
    fun `CREATE queue failure logs bounded OBS-201 without queue or throwable`() = runSuspendIO {
        val containerLogger = LoggerFactory.getLogger(SqsMessageListenerContainer::class.java) as Logger
        val appender = ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>().apply {
            list = CopyOnWriteArrayList()
            start()
        }
        val previousLevel = containerLogger.level
        val queue = "secret-queue-task8"
        val failure = IllegalStateException("throwable-secret-task8")
        var container: SqsMessageListenerContainer? = null
        containerLogger.addAppender(appender)
        containerLogger.level = Level.WARN
        try {
            coEvery {
                operations.getQueueUrl(queue)
            } throws QueueDoesNotExistException.builder().message("queue-secret-task8").build()
            coEvery { operations.createConfiguredQueue(queue) } throws failure
            val listenerContainer = container(
                operations = operations,
                invoker = invoker,
                queue = queue,
                queueNotFoundStrategy = SqsQueueNotFoundStrategy.CREATE,
                retry = SqsProperties.Retry(initialBackoff = 10.seconds()),
            )
            container = listenerContainer

            listenerContainer.start()

            await atMost 2.seconds until {
                appender.list.any { it.formattedMessage.contains("reason=queue_creation") }
            }

            val diagnostic = appender.list.single { it.formattedMessage.contains("reason=queue_creation") }
            diagnostic.formattedMessage.let {
                it shouldContain "BT4K-SQS-OBS-201"
                it shouldContain "stage=resolution"
                it shouldContain "reason=queue_creation"
                it shouldNotContain queue
                it shouldNotContain failure.message.orEmpty()
            }
            diagnostic.throwableProxy.shouldBeNull()
        } finally {
            container?.let { listenerContainer ->
                val stopped = CompletableDeferred<Unit>()
                listenerContainer.stop { stopped.complete(Unit) }
                stopped.awaitUntil(2.seconds)
            }
            containerLogger.detachAppender(appender)
            containerLogger.level = previousLevel
        }
    }

    @Test
    fun `process observation setup failure consumes one retry before handler invocation`() = runSuspendIO {
        val receiveCalls = AtomicInteger()
        val processFactoryCalls = AtomicInteger()
        val handlerCalls = AtomicInteger()
        val handlerInvoked = CompletableDeferred<Unit>()

        coEvery {
            operations.receive(QUEUE_URL, 1, 0, null)
        } coAnswers {
            if (receiveCalls.incrementAndGet() == 1) listOf(message()) else awaitCancellation()
        }
        every { invoker.manualAcknowledgement } returns true
        coEvery {
            invoker.invoke(any(), any(), any())
        } coAnswers {
            handlerCalls.incrementAndGet()
            handlerInvoked.complete(Unit)
        }
        val recorder = ContainerObservationRecorder()
        val registry = ObservationRegistry.create().apply {
            observationConfig().observationHandler(recorder)
        }
        val delegateFactory = defaultSqsObservationFactory(defaultSqsObservationConventions())
        val runtime = SqsObservationRuntime(
            registry = registry,
            customizers = emptyList(),
            factory = { context, suppliedRegistry ->
                if (
                    context.metadata.stage == SqsObservationStage.PROCESS &&
                    processFactoryCalls.incrementAndGet() == 1
                ) {
                    error("process observation setup failed")
                }
                delegateFactory.createNotStarted(context, suppliedRegistry)
            },
        )
        val container = container(
            operations,
            invoker,
            retry = SqsProperties.Retry(maxAttempts = 2, initialBackoff = Duration.ZERO),
        )
        container.setObservationRuntime(runtime)

        try {
            container.start()
            handlerInvoked.awaitUntil(2.seconds)
        } finally {
            val stopped = CompletableDeferred<Unit>()
            container.stop { stopped.complete(Unit) }
            stopped.awaitUntil(2.seconds)
        }

        processFactoryCalls.get() shouldBeEqualTo 2
        handlerCalls.get() shouldBeEqualTo 1

        val process = recorder.snapshots.single { it.stage == SqsObservationStage.PROCESS }
        log.debug { "process=$process" }
        process.outcome shouldBeEqualTo SqsObservationOutcome.RETRIED
        process.retryCount shouldBeEqualTo 1
        process.attempt shouldBeEqualTo 2
    }

    @Test
    fun `batch process observation setup retry enters handler once`() = runSuspendIO {
        val receiveCalls = AtomicInteger()
        val processFactoryCalls = AtomicInteger()
        val handlerCalls = AtomicInteger()
        val handlerInvoked = CompletableDeferred<Unit>()
        coEvery {
            operations.receive(QUEUE_URL, 1, 0, null)
        } coAnswers {
            if (receiveCalls.incrementAndGet() == 1) listOf(message()) else awaitCancellation()
        }
        coEvery {
            invoker.invokeBatch(any(), anyNullable<SqsBatchAcknowledgement>(), any())
        } coAnswers {
            handlerCalls.incrementAndGet()
            handlerInvoked.complete(Unit)
        }
        val recorder = ContainerObservationRecorder()
        val registry = ObservationRegistry.create().apply {
            observationConfig().observationHandler(recorder)
        }
        val delegateFactory = defaultSqsObservationFactory(defaultSqsObservationConventions())
        val runtime = SqsObservationRuntime(
            registry = registry,
            customizers = emptyList(),
            factory = { context, suppliedRegistry ->
                if (
                    context.metadata.stage == SqsObservationStage.PROCESS &&
                    processFactoryCalls.incrementAndGet() == 1
                ) {
                    error("batch process observation setup failed")
                }
                delegateFactory.createNotStarted(context, suppliedRegistry)
            },
        )
        val container = container(
            operations,
            invoker,
            batch = true,
            acknowledgementMode = SqsAcknowledgementMode.MANUAL,
            retry = SqsProperties.Retry(maxAttempts = 2, initialBackoff = Duration.ZERO),
        )
        container.setObservationRuntime(runtime)

        try {
            container.start()
            handlerInvoked.awaitUntil(2.seconds)
        } finally {
            val stopped = CompletableDeferred<Unit>()
            container.stop { stopped.complete(Unit) }
            stopped.awaitUntil(2.seconds)
        }

        processFactoryCalls.get() shouldBeEqualTo 2
        handlerCalls.get() shouldBeEqualTo 1

        val process = recorder.snapshots.single { it.stage == SqsObservationStage.PROCESS }
        log.debug { "process=$process" }
        process.outcome shouldBeEqualTo SqsObservationOutcome.RETRIED
        process.retryCount shouldBeEqualTo 1
        process.attempt shouldBeEqualTo 2
        process.batch.shouldBeTrue()
    }

    @Test
    fun `receive observation setup failure logs only a bounded diagnostic`() = runSuspendIO {
        assertObservationSetupFailureIsRedacted(SqsObservationStage.RECEIVE, batch = false)
    }

    @Test
    fun `single process observation setup failure logs only a bounded diagnostic`() = runSuspendIO {
        assertObservationSetupFailureIsRedacted(SqsObservationStage.PROCESS, batch = false)
    }

    @Test
    fun `batch process observation setup failure logs only a bounded diagnostic`() = runSuspendIO {
        assertObservationSetupFailureIsRedacted(SqsObservationStage.PROCESS, batch = true)
    }

    @Test
    fun `conversion and handler failures keep their process stage boundaries`() = runSuspendIO {
        val conversionFailure = IllegalArgumentException("conversion")
        val conversionInvoker = SqsListenerMethodInvoker(
            ContainerConvertedListener(),
            ContainerConvertedListener::class.java.declaredMethods.single { it.name == "handle" },
            object: SqsMessageConverter {
                override fun convert(message: SqsReceivedMessage, targetType: Class<*>): Any = throw conversionFailure
            },
        )
        val handlerFailure = IllegalStateException("handler")
        val handlerInvoker = SqsListenerMethodInvoker(
            ContainerThrowingListener(handlerFailure),
            ContainerThrowingListener::class.java.declaredMethods.single { it.name == "handle" },
            NoopSqsMessageConverter,
        )

        listOf("conversion" to conversionInvoker, "handler" to handlerInvoker).forEach { (stage, invoker) ->
            val operations = mockk<SqsOperations>()
            val receiveCalls = AtomicInteger()
            coEvery { operations.receive(QUEUE_URL, 1, 0, null) } coAnswers {
                if (receiveCalls.incrementAndGet() == 1) listOf(message()) else awaitCancellation()
            }
            val recorder = ContainerObservationRecorder()
            val container = container(
                operations,
                invoker,
                retry = SqsProperties.Retry(maxAttempts = 1),
            )
            container.setObservationRuntime(observationRuntime(recorder))

            container.start()
            withTimeout(2.seconds) {
                while (recorder.snapshots.none { it.stage == SqsObservationStage.PROCESS }) {
                    delay(5.milliseconds)
                }
            }
            val stopped = CompletableDeferred<Unit>()
            container.stop { stopped.complete(Unit) }
            stopped.awaitUntil(2.seconds)

            val process = recorder.snapshots.single { it.stage == SqsObservationStage.PROCESS }
            process.outcome shouldBeEqualTo SqsObservationOutcome.ERROR
            process.failureStage shouldBeEqualTo stage
        }
    }

    @Test
    fun `batch conversion failure keeps the conversion process stage`() = runSuspendIO {
        val conversionFailure = IllegalArgumentException("batch conversion")
        val invoker = SqsListenerMethodInvoker(
            ContainerBatchConvertedListener(),
            ContainerBatchConvertedListener::class.java.declaredMethods.single { it.name == "handle" },
            object: SqsMessageConverter {
                override fun convert(message: SqsReceivedMessage, targetType: Class<*>): Any = throw conversionFailure
            },
        )
        val operations = mockk<SqsOperations>()
        val receiveCalls = AtomicInteger()
        coEvery {
            operations.receive(QUEUE_URL, 1, 0, null)
        } coAnswers {
            if (receiveCalls.incrementAndGet() == 1) listOf(message()) else awaitCancellation()
        }
        val recorder = ContainerObservationRecorder()
        val container = container(
            operations,
            invoker,
            batch = true,
            retry = SqsProperties.Retry(maxAttempts = 1),
        )
        container.setObservationRuntime(observationRuntime(recorder))

        container.start()
        withTimeout(2.seconds) {
            while (recorder.snapshots.none { it.stage == SqsObservationStage.PROCESS }) {
                delay(5.milliseconds)
            }
        }
        val stopped = CompletableDeferred<Unit>()
        container.stop { stopped.complete(Unit) }
        stopped.awaitUntil(2.seconds)

        val process = recorder.snapshots.single { it.stage == SqsObservationStage.PROCESS }
        log.debug { "process=$process" }
        process.outcome shouldBeEqualTo SqsObservationOutcome.ERROR
        process.failureStage shouldBeEqualTo "conversion"
    }

    @Test
    fun `batch acknowledgement failure keeps the acknowledgement process stage`() = runSuspendIO {
        val receiveCalls = AtomicInteger()
        coEvery {
            operations.receive(QUEUE_URL, 1, 0, null)
        } coAnswers {
            if (receiveCalls.incrementAndGet() == 1) listOf(message()) else awaitCancellation()
        }
        coEvery {
            operations.deleteBatch(QUEUE_URL, any())
        } throws IllegalStateException("delete failed")
        coEvery {
            invoker.invokeBatch(any(), anyNullable<SqsBatchAcknowledgement>(), any())
        } returns Unit

        val recorder = ContainerObservationRecorder()
        val container = container(
            operations,
            invoker,
            batch = true,
            acknowledgementMode = SqsAcknowledgementMode.ON_SUCCESS,
            retry = SqsProperties.Retry(maxAttempts = 1),
        )
        container.setObservationRuntime(observationRuntime(recorder))

        container.start()
        withTimeout(2.seconds) {
            while (recorder.snapshots.none { it.stage == SqsObservationStage.PROCESS }) {
                delay(5.milliseconds)
            }
        }
        val stopped = CompletableDeferred<Unit>()
        container.stop { stopped.complete(Unit) }
        stopped.awaitUntil(2.seconds)

        val process = recorder.snapshots.single { it.stage == SqsObservationStage.PROCESS }
        log.debug { "process=$process" }
        process.outcome shouldBeEqualTo SqsObservationOutcome.ERROR
        process.failureStage shouldBeEqualTo "acknowledgement"
    }

    @Test
    fun `batch cancellation hooks run in non cancellable cleanup`() = runSuspendIO {
        val interceptor = mockk<SqsListenerInterceptor>(relaxed = true)
        val receiveCalls = AtomicInteger()
        val handlerStarted = CompletableDeferred<Unit>()
        val cleanupActive = CopyOnWriteArrayList<Boolean>()
        val cancellation = slot<Throwable>()

        coEvery {
            operations.receive(QUEUE_URL, 1, 0, null)
        } coAnswers {
            if (receiveCalls.incrementAndGet() == 1) listOf(message()) else awaitCancellation()
        }
        coEvery { invoker.invokeBatch(any(), anyNullable<SqsBatchAcknowledgement>(), any()) } coAnswers {
            handlerStarted.complete(Unit)
            awaitCancellation()
        }
        coEvery { interceptor.afterBatchHandle(any(), capture(cancellation), any(), any()) } coAnswers {
            cleanupActive += currentCoroutineContext().isActive
        }
        coEvery { interceptor.onBatchCancellation(any(), any(), any()) } coAnswers {
            cleanupActive += currentCoroutineContext().isActive
        }
        val container = container(
            operations,
            invoker,
            batch = true,
            acknowledgementMode = SqsAcknowledgementMode.MANUAL,
            interceptors = listOf(interceptor),
            stopTimeoutMillis = 50,
        )

        container.start()
        handlerStarted.awaitUntil(2.seconds)
        val stopped = CompletableDeferred<Unit>()
        container.stop { stopped.complete(Unit) }
        stopped.awaitUntil(2.seconds)

        cancellation.captured.shouldBeInstanceOf(CancellationException::class)
        cleanupActive shouldBeEqualTo listOf(true, true)
    }

    @Test
    fun `batch final hook failure does not replace cancellation`() = runSuspendIO {
        val operations = mockk<SqsOperations>()
        val invoker = mockk<SqsListenerMethodInvoker>()
        val interceptor = mockk<SqsListenerInterceptor>(relaxed = true)
        val receiveCalls = AtomicInteger()
        val hookStarted = CompletableDeferred<Unit>()
        val releaseHook = CompletableDeferred<Unit>()
        val hookCompleted = CompletableDeferred<Unit>()
        val cleanupFailure = IllegalStateException("batch final hook failed")
        coEvery { operations.receive(QUEUE_URL, 1, 0, null) } coAnswers {
            if (receiveCalls.incrementAndGet() == 1) listOf(message()) else awaitCancellation()
        }
        coEvery { invoker.invokeBatch(any(), anyNullable<SqsBatchAcknowledgement>(), any()) } coAnswers {
            thirdArg<() -> Unit>().invoke()
        }
        coEvery { interceptor.afterBatchHandle(any(), anyNullable(), any(), any()) } coAnswers {
            hookStarted.complete(Unit)
            releaseHook.await()
            hookCompleted.complete(Unit)
            throw cleanupFailure
        }
        val recorder = ContainerObservationRecorder()
        val container = container(
            operations,
            invoker,
            batch = true,
            acknowledgementMode = SqsAcknowledgementMode.MANUAL,
            interceptors = listOf(interceptor),
            retry = SqsProperties.Retry(maxAttempts = 1),
            stopTimeoutMillis = 25,
        )
        container.setObservationRuntime(observationRuntime(recorder))

        try {
            container.start()
            hookStarted.awaitUntil(2.seconds)
            val stopped = CompletableDeferred<Unit>()
            container.stop { stopped.complete(Unit) }
            stopped.awaitUntil(500.milliseconds)
        } finally {
            releaseHook.complete(Unit)
        }

        hookCompleted.awaitUntil(2.seconds)
        await atMost 2.seconds until {
            recorder.snapshots.any { it.stage == SqsObservationStage.PROCESS }
        }

        val process = recorder.snapshots.single { it.stage == SqsObservationStage.PROCESS }
        log.debug { "process=$process" }
        process.outcome shouldBeEqualTo SqsObservationOutcome.CANCELLED
        process.failureStage shouldBeEqualTo "handler"
    }

    @Test
    fun `non cancellable finalization keeps original cancellation and suppresses cleanup failure`() = runTest {
        val finalizationStarted = CompletableDeferred<Unit>()
        val releaseFinalization = CompletableDeferred<Unit>()
        val cancellation = CancellationException("listener is stopping")
        val cleanupFailure = IllegalStateException("cleanup failed")
        val preservedCancellation = CompletableDeferred<CancellationException>()
        val finalization = async {
            try {
                runSqsNonCancellableFinalization {
                    finalizationStarted.complete(Unit)
                    releaseFinalization.await()
                    throw cleanupFailure
                }
            } catch (actual: CancellationException) {
                preservedCancellation.complete(actual)
                throw actual
            }
        }

        finalizationStarted.await()
        finalization.cancel(cancellation)
        releaseFinalization.complete(Unit)

        assertFailsWith<CancellationException> {
            finalization.await()
        }
        val actual = preservedCancellation.await()
        actual shouldBeEqualTo cancellation
        actual.suppressed.single().shouldBeInstanceOf<IllegalStateException>()
        actual.suppressed.single().message shouldBeEqualTo cleanupFailure.message
    }

    @Test
    fun `batch retry backoff cancellation invokes cancellation hook`() = runSuspendIO {
        val interceptor = mockk<SqsListenerInterceptor>(relaxed = true)
        val receiveCalls = AtomicInteger()
        val retryScheduled = CompletableDeferred<Unit>()

        coEvery {
            operations.receive(QUEUE_URL, 1, 0, null)
        } coAnswers {
            if (receiveCalls.incrementAndGet() == 1) listOf(message()) else awaitCancellation()
        }
        coEvery {
            invoker.invokeBatch(any(), anyNullable<SqsBatchAcknowledgement>(), any())
        } throws IllegalStateException("retry")
        coEvery {
            interceptor.onBatchRetry(any(), any(), any(), 2, any())
        } coAnswers {
            retryScheduled.complete(Unit)
        }

        val container = container(
            operations,
            invoker,
            batch = true,
            acknowledgementMode = SqsAcknowledgementMode.MANUAL,
            interceptors = listOf(interceptor),
            stopTimeoutMillis = 50,
            retry = SqsProperties.Retry(maxAttempts = 2, initialBackoff = 10.seconds()),
        )

        container.start()
        retryScheduled.awaitUntil(2.seconds)

        val stopped = CompletableDeferred<Unit>()
        container.stop { stopped.complete(Unit) }
        stopped.awaitUntil(2.seconds)

        coVerify(exactly = 1) { interceptor.onBatchCancellation(any(), any(), any()) }
    }

    @Test
    fun `cancelling retry backoff stops the same process without another attempt or acknowledgement`() = runSuspendIO {
        val handlerCalls = AtomicInteger()
        val firstAttemptFailed = CompletableDeferred<Unit>()

        coEvery {
            operations.receive(QUEUE_URL, 1, 0, null)
        } returns listOf(message())
        every { invoker.manualAcknowledgement } returns false
        coEvery {
            invoker.invoke(any(), any(), any())
        } coAnswers {
            handlerCalls.incrementAndGet()
            firstAttemptFailed.complete(Unit)
            throw IllegalStateException("retry")
        }
        val recorder = ContainerObservationRecorder()
        val container = container(
            operations,
            invoker,
            stopTimeoutMillis = 50,
            retry = SqsProperties.Retry(maxAttempts = 3, initialBackoff = 10.seconds()),
        )
        container.setObservationRuntime(observationRuntime(recorder))

        container.start()
        firstAttemptFailed.awaitUntil(2.seconds)
        val stopped = CompletableDeferred<Unit>()
        container.stop { stopped.complete(Unit) }
        stopped.awaitUntil(2.seconds)

        handlerCalls.get() shouldBeEqualTo 1
        coVerify(exactly = 0) { operations.delete(any(), any()) }

        val process = recorder.snapshots.single { it.stage == SqsObservationStage.PROCESS }
        log.debug { "process=$process" }
        process.outcome shouldBeEqualTo SqsObservationOutcome.CANCELLED
        process.retryCount shouldBeEqualTo 1
        process.attempt shouldBeEqualTo 2
        recorder.retryEvents.get() shouldBeEqualTo 1
    }

    @Test
    fun `missing and noop runtime preserve the direct listener sequence without extension calls`() = runSuspendIO {
        listOf(false, true).forEach { installNoopRuntime ->
            val calls = CopyOnWriteArrayList<String>()
            var customizers = 0
            var factories = 0
            val received = CompletableDeferred<Unit>()
            val receiveCalls = AtomicInteger()

            coEvery {
                operations.receive(QUEUE_URL, 1, 0, null)
            } coAnswers {
                if (receiveCalls.incrementAndGet() == 1) {
                    calls += "receive"
                    listOf(message())
                } else {
                    awaitCancellation()
                }
            }
            coEvery {
                operations.delete(QUEUE_URL, any())
            } coAnswers {
                calls += "ack"
                received.complete(Unit)
                DeleteMessageResponse.builder().build()
            }
            every { invoker.manualAcknowledgement } returns false
            coEvery {
                invoker.invoke(any(), any(), any())
            } coAnswers {
                calls += "handler"
            }
            val container = container(operations, invoker)
            if (installNoopRuntime) {
                container.setObservationRuntime(
                    SqsObservationRuntime(
                        registry = ObservationRegistry.NOOP,
                        customizers = listOf(SqsObservationContextCustomizer { customizers++ }),
                        factory = { _, _ ->
                            factories++
                            Observation.NOOP
                        },
                    ),
                )
            }

            try {
                container.start()
                received.awaitUntil(2.seconds)
                calls shouldBeEqualTo listOf("receive", "handler", "ack")
                customizers shouldBeEqualTo 0
                factories shouldBeEqualTo 0
            } finally {
                val stopped = CompletableDeferred<Unit>()
                container.stop { stopped.complete(Unit) }
                stopped.awaitUntil(2.seconds)
            }
        }
    }

    @Test
    fun `single message handlers run concurrently up to the in flight limit`() = runSuspendIO {
        val messages = listOf(message(), message("message-2"))
        val receiveCalls = AtomicInteger()
        val startedIds = CopyOnWriteArrayList<String>()
        val bothStarted = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()

        coEvery {
            operations.receive(QUEUE_URL, 2, 0, null)
        } coAnswers {
            if (receiveCalls.incrementAndGet() == 1) messages else awaitCancellation()
        }
        coEvery {
            operations.delete(QUEUE_URL, any())
        } returns DeleteMessageResponse.builder().build()
        every { invoker.manualAcknowledgement } returns false
        coEvery {
            invoker.invoke(any(), any(), any())
        } coAnswers {
            startedIds += firstArg<SqsReceivedMessage>().messageId
            if (startedIds.size == 2) {
                bothStarted.complete(Unit)
            }
            release.await()
        }
        val container = container(
            operations,
            invoker,
            maxMessages = 2,
            maxInFlight = 2,
        )

        container.start()
        try {
            bothStarted.awaitUntil(2.seconds)
            startedIds.toSet() shouldBeEqualTo setOf("message-1", "message-2")
        } finally {
            release.complete(Unit)
            val stopped = CompletableDeferred<Unit>()
            container.stop { stopped.complete(Unit) }
            stopped.awaitUntil(2.seconds)
        }
    }

    @RepeatedTest(100)
    fun `fifo message group is serialized while another group runs in parallel`() = runSuspendIO {
        val messages = listOf(message("a-1", "group-a"), message("a-2", "group-a"), message("b-1", "group-b"))
        val receiveCalls = AtomicInteger()
        val firstGroupStarted = CompletableDeferred<Unit>()
        val otherGroupStarted = CompletableDeferred<Unit>()
        val secondGroupMessageStarted = CompletableDeferred<Unit>()
        val releaseFirstGroup = CompletableDeferred<Unit>()
        coEvery {
            operations.receive(QUEUE_URL, 3, 0, null)
        } coAnswers {
            if (receiveCalls.incrementAndGet() == 1) messages else awaitCancellation()
        }
        coEvery {
            operations.delete(QUEUE_URL, any())
        } returns DeleteMessageResponse.builder().build()
        every { invoker.manualAcknowledgement } returns false
        coEvery {
            invoker.invoke(any(), any(), any())
        } coAnswers {
            when (val id = firstArg<SqsReceivedMessage>().messageId) {
                "a-1" -> {
                    firstGroupStarted.complete(Unit)
                    releaseFirstGroup.await()
                }
                "a-2" -> secondGroupMessageStarted.complete(Unit)
                "b-1" -> otherGroupStarted.complete(Unit)
            }
        }
        val container = container(
            operations,
            invoker,
            maxMessages = 3,
            maxInFlight = 3,
        )

        container.start()
        try {
            firstGroupStarted.awaitUntil(2.seconds)
            otherGroupStarted.awaitUntil(2.seconds)
            secondGroupMessageStarted.isCompleted.shouldBeFalse()
            releaseFirstGroup.complete(Unit)
            secondGroupMessageStarted.awaitUntil(2.seconds)
        } finally {
            releaseFirstGroup.complete(Unit)
            val stopped = CompletableDeferred<Unit>()
            container.stop { stopped.complete(Unit) }
            stopped.awaitUntil(2.seconds)
        }
    }

    @Test
    fun `batch receive invokes handler once with all messages`() = runSuspendIO {
        val invocation = CompletableDeferred<List<SqsReceivedMessage>>()
        val deleteBatchCalled = CompletableDeferred<Unit>()
        val receiveCalls = AtomicInteger(0)
        val messages = listOf(message(), message("message-2"))
        coEvery {
            operations.receive(QUEUE_URL, 2, 0, null)
        } coAnswers {
            if (receiveCalls.incrementAndGet() == 1) messages else awaitCancellation()
        }
        coEvery {
            operations.deleteBatch(QUEUE_URL, any())
        } coAnswers {
            deleteBatchCalled.complete(Unit)
            SqsBatchDeleteResult(listOf("entry-0", "entry-1"), emptyList())
        }
        coEvery {
            invoker.invokeBatch(any(), anyNullable<SqsBatchAcknowledgement>(), any())
        } coAnswers {
            invocation.complete(firstArg<List<SqsReceivedMessage>>())
        }
        val container = container(
            operations,
            invoker,
            batch = true,
            maxMessages = 2,
            acknowledgementMode = SqsAcknowledgementMode.ON_SUCCESS,
        )

        try {
            container.start()
            invocation.awaitUntil(2.seconds)
                .map { it.messageId } shouldBeEqualTo listOf("message-1", "message-2")
            deleteBatchCalled.awaitUntil(2.seconds)

            coVerify(exactly = 1) { invoker.invokeBatch(any(), null, any()) }
            coVerify(exactly = 1) { operations.deleteBatch(QUEUE_URL, any()) }
        } finally {
            val stopped = CompletableDeferred<Unit>()
            container.stop { stopped.complete(Unit) }
            stopped.awaitUntil(2.seconds)
        }
    }

    @Test
    fun `manual batch acknowledgement deletes only the selected messages`() = runSuspendIO {
        val recorder = ContainerObservationRecorder()
        val invocation = CompletableDeferred<Unit>()
        val messages = listOf(message(), message("message-2"))

        coEvery {
            operations.receive(QUEUE_URL, 2, 0, null)
        } coAnswers {
            if (!invocation.isCompleted) messages else awaitCancellation()
        }
        coEvery {
            operations.deleteBatch(QUEUE_URL, any())
        } returns SqsBatchDeleteResult(listOf("entry-0"), emptyList())
        coEvery {
            invoker.invokeBatch(any(), anyNullable<SqsBatchAcknowledgement>(), any())
        } coAnswers {
            secondArg<SqsBatchAcknowledgement>().acknowledge(listOf(firstArg<List<SqsReceivedMessage>>().first()))
            invocation.complete(Unit)
        }

        val container = container(
            operations,
            invoker,
            batch = true,
            maxMessages = 2,
            acknowledgementMode = SqsAcknowledgementMode.MANUAL,
        )
        container.setObservationRuntime(observationRuntime(recorder))

        try {
            container.start()
            invocation.awaitUntil(2.seconds)
            coVerify(exactly = 1) { operations.deleteBatch(QUEUE_URL, any()) }

            await atMost 2.seconds withPollInterval 1.milliseconds until {
                recorder.snapshots.any { it.stage == SqsObservationStage.PROCESS }
            }
            recorder.snapshots.single { it.stage == SqsObservationStage.PROCESS }
                .outcome shouldBeEqualTo SqsObservationOutcome.PARTIAL
        } finally {
            val stopped = CompletableDeferred<Unit>()
            container.stop { stopped.complete(Unit) }
            stopped.awaitUntil(2.seconds)
        }
    }

    @Test
    fun `batch retry invokes only pending messages after partial delete`() = runSuspendIO {
        val invocations = mutableListOf<List<SqsReceivedMessage>>()
        val completed = CompletableDeferred<Unit>()
        val messages = listOf(message(), message("message-2"))
        val receiveCalls = AtomicInteger(0)

        coEvery {
            operations.receive(QUEUE_URL, 2, 0, null)
        } coAnswers {
            if (receiveCalls.incrementAndGet() == 1) messages else awaitCancellation()
        }
        coEvery {
            operations.deleteBatch(QUEUE_URL, any())
        } coAnswers {
            if (invocations.size == 1) SqsBatchDeleteResult(
                successfulEntryIds = listOf("entry-0"),
                failed = listOf(SqsBatchDeleteFailure("entry-1", "failed", "rejected", false)),
            )
            else SqsBatchDeleteResult(listOf("entry-0"), emptyList()).also { completed.complete(Unit) }
        }
        coEvery {
            invoker.invokeBatch(any(), anyNullable<SqsBatchAcknowledgement>(), any())
        } coAnswers {
            invocations += firstArg<List<SqsReceivedMessage>>()
        }

        val container = container(
            operations,
            invoker,
            batch = true,
            maxMessages = 2,
            acknowledgementMode = SqsAcknowledgementMode.ON_SUCCESS,
            retry = SqsProperties.Retry(maxAttempts = 2),
        )
        try {
            container.start()
            completed.awaitUntil(2.seconds)
            invocations.map { it.map(SqsReceivedMessage::messageId) } shouldBeEqualTo
                    listOf(listOf("message-1", "message-2"), listOf("message-2"))
        } finally {
            val stopped = CompletableDeferred<Unit>()
            container.stop { stopped.complete(Unit) }
            stopped.awaitUntil(2.seconds)
        }
    }

    @Test
    fun `container runs poller on injected dispatcher`() = runSuspendIO {
        val pollerStarted = CompletableDeferred<String>()
        val executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "sqs-container-test")
        }
        val dispatcher = executor.asCoroutineDispatcher()
        coEvery {
            operations.receive(QUEUE_URL, 1, 0, null)
        } coAnswers {
            pollerStarted.complete(Thread.currentThread().name)
            awaitCancellation()
        }
        every { invoker.manualAcknowledgement } returns false

        val container = container(operations, invoker, dispatcher = dispatcher)

        try {
            container.start()
            pollerStarted.awaitUntil(2.seconds) shouldContain "sqs-container-test"
        } finally {
            val stopped = CompletableDeferred<Unit>()
            container.stop { stopped.complete(Unit) }
            stopped.awaitUntil(2.seconds)
            dispatcher.close()
            executor.shutdownNow()
            executor.awaitTermination(5.seconds)
        }
    }

    @Test
    fun `explicit start starts listener when automatic startup is disabled`() = runSuspendIO {
        val pollerStarted = CompletableDeferred<Unit>()

        coEvery {
            operations.receive(QUEUE_URL, 1, 0, null)
        } coAnswers {
            pollerStarted.complete(Unit)
            awaitCancellation()
        }
        every { invoker.manualAcknowledgement } returns false

        val container = container(operations, invoker, autoStartup = false)

        try {
            container.start()
            pollerStarted.awaitUntil(2.seconds)
            container.isRunning.shouldBeTrue()
        } finally {
            val stopped = CompletableDeferred<Unit>()
            container.stop { stopped.complete(Unit) }
            stopped.awaitUntil(2.seconds)
        }
    }

    @Test
    fun `receive cancellation invokes terminal interceptor hook`() = runSuspendIO {
        val interceptor = mockk<SqsListenerInterceptor>(relaxed = true)
        val receiveStarted = CompletableDeferred<Unit>()
        val cancellation = slot<Throwable>()

        coEvery {
            operations.receive(QUEUE_URL, 1, 0, null)
        } coAnswers {
            receiveStarted.complete(Unit)
            awaitCancellation()
        }
        every { invoker.manualAcknowledgement } returns false

        val container = container(operations, invoker, interceptors = listOf(interceptor))

        try {
            container.start()
            receiveStarted.awaitUntil(2.seconds)
        } finally {
            val stopped = CompletableDeferred<Unit>()
            container.stop { stopped.complete(Unit) }
            stopped.awaitUntil(2.seconds)
        }

        coVerify(exactly = 1) {
            interceptor.afterReceive(
                "listener",
                QUEUE_URL,
                emptyList(),
                capture(cancellation),
                any(),
            )
        }
        cancellation.captured.shouldBeInstanceOf<CancellationException>()
    }

    @Test
    fun `handler cancellation invokes terminal interceptor hook`() = runSuspendIO {
        val interceptor = mockk<SqsListenerInterceptor>(relaxed = true)
        val handlerStarted = CompletableDeferred<Unit>()
        val cancellation = slot<Throwable>()
        coEvery {
            operations.receive(QUEUE_URL, 1, 0, null)
        } returns listOf(message())
        every { invoker.manualAcknowledgement } returns false
        coEvery {
            invoker.invoke(any(), any(), any())
        } coAnswers {
            handlerStarted.complete(Unit)
            awaitCancellation()
        }

        val container = container(
            operations,
            invoker,
            stopTimeoutMillis = 50,
            interceptors = listOf(interceptor),
        )
        val recorder = ContainerObservationRecorder()
        container.setObservationRuntime(observationRuntime(recorder))

        container.start()
        handlerStarted.awaitUntil(2.seconds)

        val stopped = CompletableDeferred<Unit>()
        container.stop { stopped.complete(Unit) }
        stopped.awaitUntil(2.seconds)

        coVerify(exactly = 1) { interceptor.afterHandle(any(), capture(cancellation)) }

        cancellation.captured.shouldBeInstanceOf(CancellationException::class)
        val process = recorder.snapshots.single { it.stage == SqsObservationStage.PROCESS }
        process.outcome shouldBeEqualTo SqsObservationOutcome.CANCELLED
    }

    @Test
    fun `stop drains active handler without waiting for a restarted generation`() = runSuspendIO {
        val handlerStarted = CompletableDeferred<Unit>()
        val handlerRelease = CompletableDeferred<Unit>()
        val handlerCompleted = CompletableDeferred<Unit>()
        val receiveCalls = AtomicInteger(0)
        coEvery {
            operations.receive(QUEUE_URL, 1, 0, null)
        } coAnswers {
            if (receiveCalls.incrementAndGet() == 1) listOf(message()) else awaitCancellation()
        }
        coEvery {
            operations.delete(any(), any())
        } returns DeleteMessageResponse.builder().build()
        every { invoker.manualAcknowledgement } returns false
        coEvery {
            invoker.invoke(any(), any(), any())
        } coAnswers {
            handlerStarted.complete(Unit)
            handlerRelease.await()
            handlerCompleted.complete(Unit)
        }
        val container = container(operations, invoker)

        container.start()
        handlerStarted.awaitUntil(2.seconds)

        val firstStopCallbacks = AtomicInteger(0)
        val firstStopped = CompletableDeferred<Unit>()
        container.stop {
            firstStopCallbacks.incrementAndGet()
            firstStopped.complete(Unit)
        }
        assertFailsWith<IllegalStateException> {
            container.start()
        }
        handlerRelease.complete(Unit)

        firstStopped.awaitUntil(2.seconds)

        container.start()

        handlerCompleted.isCompleted.shouldBeTrue()
        firstStopCallbacks.get() shouldBeEqualTo 1
        container.isRunning.shouldBeTrue()

        val secondStopped = CompletableDeferred<Unit>()
        container.stop { secondStopped.complete(Unit) }
        secondStopped.awaitUntil(2.seconds)
    }

    @Test
    fun `stop timeout cancels handler and invokes repeated stop callbacks once`() = runSuspendIO {
        val handlerStarted = CompletableDeferred<Unit>()
        val handlerCancelled = CompletableDeferred<Unit>()
        coEvery {
            operations.receive(QUEUE_URL, 1, 0, null)
        } returns listOf(message())
        every { invoker.manualAcknowledgement } returns false
        coEvery {
            invoker.invoke(any(), any(), any())
        } coAnswers {
            handlerStarted.complete(Unit)
            try {
                awaitCancellation()
            } finally {
                handlerCancelled.complete(Unit)
            }
        }
        val container = container(operations, invoker, stopTimeoutMillis = 50)
        container.start()
        handlerStarted.awaitUntil(2.seconds)

        val firstCallbacks = AtomicInteger(0)
        val secondCallbacks = AtomicInteger(0)
        val firstStopped = CompletableDeferred<Unit>()
        val secondStopped = CompletableDeferred<Unit>()
        container.stop {
            firstCallbacks.incrementAndGet()
            firstStopped.complete(Unit)
        }
        container.stop {
            secondCallbacks.incrementAndGet()
            secondStopped.complete(Unit)
        }

        withTimeout(2.seconds) {
            firstStopped.await()
            secondStopped.await()
            handlerCancelled.await()
        }
        firstCallbacks.get() shouldBeEqualTo 1
        secondCallbacks.get() shouldBeEqualTo 1
    }

    @Test
    fun `stop timeout callback remains bounded for a non cooperative handler`() = runSuspendIO {
        val receiveCalls = AtomicInteger()
        val handlerStarted = CompletableDeferred<Unit>()
        val releaseHandler = CompletableDeferred<Unit>()
        val handlerCompleted = CompletableDeferred<Unit>()
        coEvery {
            operations.receive(QUEUE_URL, 1, 0, null)
        } coAnswers {
            if (receiveCalls.incrementAndGet() == 1) listOf(message()) else awaitCancellation()
        }
        every { invoker.manualAcknowledgement } returns true
        coEvery {
            invoker.invoke(any(), any(), any())
        } coAnswers {
            handlerStarted.complete(Unit)
            try {
                withContext(NonCancellable) { releaseHandler.await() }
            } finally {
                handlerCompleted.complete(Unit)
            }
        }

        val container = container(operations, invoker, stopTimeoutMillis = 25)

        try {
            container.start()
            handlerStarted.awaitUntil(2.seconds)
            val stopped = CompletableDeferred<Unit>()
            container.stop { stopped.complete(Unit) }
            stopped.awaitUntil(500.milliseconds)
        } finally {
            releaseHandler.complete(Unit)
            handlerCompleted.awaitUntil(2.seconds)
        }
    }

    @Test
    fun `queue URL lookup retries ordinary failures before polling`() = runSuspendIO {
        val operations = mockk<SqsOperations>()
        val invoker = mockk<SqsListenerMethodInvoker>()
        val lookupCalls = AtomicInteger()
        val resolved = CompletableDeferred<Unit>()
        coEvery {
            operations.getQueueUrl("orders")
        } coAnswers {
            if (lookupCalls.incrementAndGet() == 1) error("temporary lookup failure")
            QUEUE_URL
        }
        coEvery {
            operations.receive(QUEUE_URL, 1, 0, null)
        } coAnswers {
            resolved.complete(Unit)
            awaitCancellation()
        }
        every { invoker.manualAcknowledgement } returns false

        val container = container(
            operations,
            invoker,
            queue = "orders",
            retry = SqsProperties.Retry(initialBackoff = 1.millis()),
        )

        container.start()

        try {
            resolved.awaitUntil(2.seconds)
            lookupCalls.get() shouldBeEqualTo 2
        } finally {
            val stopped = CompletableDeferred<Unit>()
            container.stop { stopped.complete(Unit) }
            stopped.awaitUntil(2.seconds)
        }
    }

    @Test
    fun `fatal handler error stops generation without acknowledgement`() = runSuspendIO {
        val containerLogger = LoggerFactory.getLogger(SqsMessageListenerContainer::class.java) as Logger
        val appender = ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>().apply { start() }
        val previousLevel = containerLogger.level
        val started = CompletableDeferred<Unit>()
        coEvery {
            operations.receive(QUEUE_URL, 1, 0, null)
        } returns listOf(message())
        every { invoker.manualAcknowledgement } returns false
        coEvery {
            invoker.invoke(any(), any(), any())
        } coAnswers {
            started.complete(Unit)
            throw AssertionError("fatal handler error")
        }

        val container = container(operations, invoker)

        containerLogger.addAppender(appender)
        containerLogger.level = Level.ERROR
        try {
            container.start()
            started.awaitUntil(2.seconds)

            await atMost 2.seconds withPollInterval 5.milliseconds until {
                !container.isRunning
            }
            await atMost 2.seconds withPollInterval 5.milliseconds until {
                appender.list.any { it.throwableProxy?.message == "fatal handler error" }
            }

            appender.list.any { it.throwableProxy?.message == "fatal handler error" }.shouldBeTrue()
            coVerify(exactly = 0) { operations.delete(any(), any()) }
        } finally {
            containerLogger.detachAppender(appender)
            containerLogger.level = previousLevel
        }
    }

    @Test
    fun `fatal single heartbeat telemetry error stops generation without generic diagnostic`() = runTest {
        assertFatalHeartbeatTelemetryErrorIsNotLogged(batch = false)
    }

    @Test
    fun `fatal batch heartbeat telemetry error stops generation without generic diagnostic`() = runTest {
        assertFatalHeartbeatTelemetryErrorIsNotLogged(batch = true)
    }

    @Test
    fun `single message heartbeat repeats while handler is active and stops after success`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val handlerStarted = CompletableDeferred<Unit>()
        val handlerRelease = CompletableDeferred<Unit>()
        val secondHeartbeat = CompletableDeferred<Unit>()
        val heartbeatCalls = AtomicInteger()
        val receiveCalls = AtomicInteger()
        val recorder = ContainerObservationRecorder()
        coEvery {
            operations.receive(QUEUE_URL, 1, 0, null)
        } coAnswers {
            if (receiveCalls.incrementAndGet() == 1) listOf(message()) else awaitCancellation()
        }
        coEvery {
            operations.changeVisibility(QUEUE_URL, "receipt-message-1", 30)
        } coAnswers {
            if (heartbeatCalls.incrementAndGet() >= 2) {
                secondHeartbeat.complete(Unit)
            }
            ChangeMessageVisibilityResponse.builder().build()
        }
        coEvery {
            operations.delete(QUEUE_URL, "receipt-message-1")
        } returns DeleteMessageResponse.builder().build()
        every { invoker.manualAcknowledgement } returns false
        coEvery {
            invoker.invoke(any(), any(), any())
        } coAnswers {
            handlerStarted.complete(Unit)
            handlerRelease.await()
        }

        val container = container(
            operations = operations,
            invoker = invoker,
            dispatcher = dispatcher,
            messageVisibilityHeartbeatIntervalSeconds = 1,
            messageVisibilityHeartbeatSeconds = 30,
        )
        container.setObservationRuntime(observationRuntime(recorder))

        container.start()
        runCurrent()
        handlerStarted.await()
        advanceTimeBy(2.seconds)
        runCurrent()

        withContext(Dispatchers.Default.limitedParallelism(1)) {
            secondHeartbeat.awaitUntil(2.seconds)
        }
        heartbeatCalls.get() shouldBeGreaterOrEqualTo 2

        handlerRelease.complete(Unit)
        runCurrent()

        coVerify(exactly = 1) { operations.delete(QUEUE_URL, "receipt-message-1") }

        val callsAfterSuccess = heartbeatCalls.get()
        val stopped = CompletableDeferred<Unit>()
        container.stop { stopped.complete(Unit) }
        runCurrent()
        stopped.await()

        advanceTimeBy(2.seconds)
        runCurrent()

        heartbeatCalls.get() shouldBeEqualTo callsAfterSuccess
        assertHeartbeatObservationCounts(recorder, heartbeatCalls.get())
    }

    @Test
    fun `single message heartbeat stops after manual acknowledgement`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val handlerStarted = CompletableDeferred<Unit>()
        val acknowledgementCompleted = CompletableDeferred<Unit>()
        val handlerRelease = CompletableDeferred<Unit>()
        val receiveCalls = AtomicInteger()

        coEvery {
            operations.receive(QUEUE_URL, 1, 0, null)
        } coAnswers {
            if (receiveCalls.incrementAndGet() == 1) listOf(message()) else awaitCancellation()
        }
        coEvery {
            operations.changeVisibility(QUEUE_URL, "receipt-message-1", 30)
        } returns ChangeMessageVisibilityResponse.builder().build()
        coEvery {
            operations.delete(QUEUE_URL, "receipt-message-1")
        } returns DeleteMessageResponse.builder().build()
        every { invoker.manualAcknowledgement } returns true
        coEvery {
            invoker.invoke(any(), any(), any())
        } coAnswers {
            handlerStarted.complete(Unit)
            secondArg<SqsAcknowledgement>().acknowledge()
            acknowledgementCompleted.complete(Unit)
            handlerRelease.await()
        }

        val container = container(
            operations = operations,
            invoker = invoker,
            dispatcher = dispatcher,
            messageVisibilityHeartbeatIntervalSeconds = 1,
            messageVisibilityHeartbeatSeconds = 30,
        )

        container.start()
        runCurrent()
        handlerStarted.await()
        acknowledgementCompleted.await()
        advanceTimeBy(1.seconds)
        runCurrent()

        coVerify(exactly = 1) { operations.delete(QUEUE_URL, "receipt-message-1") }
        coVerify(exactly = 0) { operations.changeVisibility(QUEUE_URL, "receipt-message-1", 30) }

        handlerRelease.complete(Unit)
        runCurrent()

        val stopped = CompletableDeferred<Unit>()
        container.stop { stopped.complete(Unit) }
        runCurrent()
        stopped.await()
    }

    @RepeatedTest(100)
    @Suppress("LongMethod")
    fun `heartbeat observation stop failure emits bounded diagnostic without changing handler outcome`() = runTest {
        val containerLogger = LoggerFactory.getLogger(SqsMessageListenerContainer::class.java) as Logger
        val diagnosticObserved = CompletableDeferred<ch.qos.logback.classic.spi.ILoggingEvent>()
        val appender = object: ch.qos.logback.core.AppenderBase<ch.qos.logback.classic.spi.ILoggingEvent>() {
            override fun append(eventObject: ch.qos.logback.classic.spi.ILoggingEvent) {
                if (
                    eventObject.formattedMessage.contains("reason=telemetry_cleanup") &&
                    eventObject.formattedMessage.contains("target=single")
                ) {
                    diagnosticObserved.complete(eventObject)
                }
            }
        }.apply { start() }
        val observationStopEntered = CompletableDeferred<Unit>()
        val observationStopRelease = CountDownLatch(1)
        val handlerRelease = CompletableDeferred<Unit>()
        var listenerContainer: SqsMessageListenerContainer? = null
        val previousLevel = containerLogger.level
        containerLogger.addAppender(appender)
        containerLogger.level = Level.WARN

        try {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val handlerStarted = CompletableDeferred<Unit>()
            val handlerReturned = CompletableDeferred<Unit>()
            val receiveCalls = AtomicInteger()
            coEvery {
                operations.receive(QUEUE_URL, 1, 0, null)
            } coAnswers {
                if (receiveCalls.incrementAndGet() == 1) listOf(message()) else awaitCancellation()
            }
            coEvery {
                operations.changeVisibility(QUEUE_URL, "receipt-message-1", 30)
            } coAnswers {
                ChangeMessageVisibilityResponse.builder().build()
            }
            every { invoker.manualAcknowledgement } returns true
            coEvery {
                invoker.invoke(any(), any(), any())
            } coAnswers {
                handlerStarted.complete(Unit)
                handlerRelease.await()
                handlerReturned.complete(Unit)
            }
            val registry = ObservationRegistry.create()
            registry.observationConfig().observationHandler(object: ObservationHandler<SqsObservationContext> {
                override fun supportsContext(context: Observation.Context): Boolean =
                    context is SqsObservationContext

                override fun onStop(context: SqsObservationContext) {
                    if (context.metadata.stage == SqsObservationStage.ACKNOWLEDGEMENT) {
                        // 동기 콜백의 종료를 지연해 handler 반환과 로그 기록 순서를 분리한다.
                        observationStopEntered.complete(Unit)
                        check(observationStopRelease.await(10, java.util.concurrent.TimeUnit.SECONDS))
                        error("heartbeat observation stop failed")
                    }
                }
            })
            val container = container(
                operations = operations,
                invoker = invoker,
                dispatcher = dispatcher,
                acknowledgementMode = SqsAcknowledgementMode.MANUAL,
                messageVisibilityHeartbeatIntervalSeconds = 1,
                messageVisibilityHeartbeatSeconds = 30,
            )
            listenerContainer = container
            container.setObservationRuntime(
                SqsObservationRuntime(
                    registry = registry,
                    customizers = emptyList(),
                    factory = defaultSqsObservationFactory(defaultSqsObservationConventions()),
                ),
            )

            container.start()
            runCurrent()
            handlerStarted.await()
            advanceTimeBy(1.seconds)
            runCurrent()
            withContext(Dispatchers.Default.limitedParallelism(1)) {
                observationStopEntered.awaitUntil(2.seconds)
            }
            handlerRelease.complete(Unit)
            runCurrent()
            handlerReturned.await()

            diagnosticObserved.isCompleted.shouldBeFalse()
            observationStopRelease.countDown()
            val diagnostic = withContext(Dispatchers.Default) {
                diagnosticObserved.awaitUntil(5.seconds)
            }
            diagnostic.formattedMessage.contains("BT4K-SQS-OBS-202").shouldBeTrue()
            diagnostic.formattedMessage.contains(QUEUE_URL).shouldBeFalse()
            diagnostic.formattedMessage.contains("heartbeat observation stop failed").shouldBeFalse()
            diagnostic.throwableProxy.shouldBeNull()

            coVerify(exactly = 1) { invoker.invoke(any(), any(), any()) }
            coVerify(exactly = 1) { operations.changeVisibility(QUEUE_URL, "receipt-message-1", 30) }
        } finally {
            observationStopRelease.countDown()
            handlerRelease.complete(Unit)
            try {
                listenerContainer?.let { container ->
                    val stopped = CompletableDeferred<Unit>()
                    container.stop { stopped.complete(Unit) }
                    runCurrent()
                    withContext(NonCancellable + Dispatchers.Default) {
                        stopped.awaitUntil(5.seconds)
                    }
                }
            } finally {
                containerLogger.detachAppender(appender)
                containerLogger.level = previousLevel
                appender.stop()
            }
        }
    }

    @Test
    @Suppress("LongMethod")
    fun `heartbeat observation start failure emits bounded diagnostic without raw telemetry details`() = runTest {
        val containerLogger = LoggerFactory.getLogger(SqsMessageListenerContainer::class.java) as Logger
        val diagnosticObserved = CompletableDeferred<ch.qos.logback.classic.spi.ILoggingEvent>()
        val appender = object: ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>() {
            override fun append(eventObject: ch.qos.logback.classic.spi.ILoggingEvent) {
                super.append(eventObject)
                if (
                    eventObject.formattedMessage.contains("reason=heartbeat_telemetry_setup") &&
                    eventObject.formattedMessage.contains("target=single")
                ) {
                    diagnosticObserved.complete(eventObject)
                }
            }
        }.apply { start() }
        val previousLevel = containerLogger.level
        val failure = IllegalStateException("heartbeat-start-secret")
        val acknowledgementStartAttempted = CompletableDeferred<Unit>()
        var listenerContainer: SqsMessageListenerContainer? = null
        containerLogger.addAppender(appender)
        containerLogger.level = Level.WARN
        try {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val handlerStarted = CompletableDeferred<Unit>()
            val handlerRelease = CompletableDeferred<Unit>()
            val handlerReturned = CompletableDeferred<Unit>()
            val receiveCalls = AtomicInteger()
            coEvery {
                operations.receive(QUEUE_URL, 1, 0, null)
            } coAnswers {
                if (receiveCalls.incrementAndGet() == 1) listOf(message()) else awaitCancellation()
            }
            every { invoker.manualAcknowledgement } returns true
            coEvery {
                invoker.invoke(any(), any(), any())
            } coAnswers {
                handlerStarted.complete(Unit)
                handlerRelease.await()
                handlerReturned.complete(Unit)
            }

            val registry = ObservationRegistry.create().apply {
                observationConfig().observationHandler(
                    object: ObservationHandler<SqsObservationContext> {
                        override fun supportsContext(context: Observation.Context): Boolean =
                            context is SqsObservationContext

                        override fun onStart(context: SqsObservationContext) {
                            if (context.metadata.stage == SqsObservationStage.ACKNOWLEDGEMENT) {
                                acknowledgementStartAttempted.complete(Unit)
                                throw failure
                            }
                        }
                    },
                )
            }
            val currentContainer = container(
                operations = operations,
                invoker = invoker,
                dispatcher = dispatcher,
                acknowledgementMode = SqsAcknowledgementMode.MANUAL,
                messageVisibilityHeartbeatIntervalSeconds = 1,
                messageVisibilityHeartbeatSeconds = 30,
            )
            listenerContainer = currentContainer
            currentContainer.setObservationRuntime(
                SqsObservationRuntime(
                    registry = registry,
                    customizers = emptyList(),
                    factory = defaultSqsObservationFactory(defaultSqsObservationConventions()),
                ),
            )

            currentContainer.start()
            runCurrent()
            handlerStarted.await()
            advanceTimeBy(1.seconds)
            runCurrent()
            withContext(Dispatchers.Default.limitedParallelism(1)) {
                acknowledgementStartAttempted.awaitUntil(2.seconds)
            }
            val diagnostic = withContext(Dispatchers.Default.limitedParallelism(1)) {
                diagnosticObserved.awaitUntil(2.seconds)
            }

            log.debug { "diagnostic=$diagnostic" }
            diagnostic.formattedMessage shouldContain "BT4K-SQS-OBS-202"
            diagnostic.formattedMessage shouldNotContain QUEUE_URL
            diagnostic.formattedMessage shouldNotContain failure.message.orEmpty()
            diagnostic.throwableProxy.shouldBeNull()

            coVerify(exactly = 0) { operations.changeVisibility(any(), any(), any()) }

            handlerRelease.complete(Unit)
            runCurrent()
            withContext(Dispatchers.Default.limitedParallelism(1)) {
                handlerReturned.awaitUntil(2.seconds)
            }
        } finally {
            listenerContainer?.let { container ->
                val stopped = CompletableDeferred<Unit>()
                container.stop { stopped.complete(Unit) }
                runCurrent()
                stopped.awaitUntil(2.seconds)
            }
            containerLogger.detachAppender(appender)
            containerLogger.level = previousLevel
        }
    }

    @Test
    @Suppress("LongMethod")
    fun `batch heartbeat observation start failure emits bounded diagnostic without raw telemetry details`() = runTest {
        val containerLogger = LoggerFactory.getLogger(SqsMessageListenerContainer::class.java) as Logger
        val diagnosticObserved = CompletableDeferred<ch.qos.logback.classic.spi.ILoggingEvent>()
        val appender = object: ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>() {
            override fun append(eventObject: ch.qos.logback.classic.spi.ILoggingEvent) {
                super.append(eventObject)
                if (
                    eventObject.formattedMessage.contains("reason=heartbeat_telemetry_setup") &&
                    eventObject.formattedMessage.contains("target=batchSize=2")
                ) {
                    diagnosticObserved.complete(eventObject)
                }
            }
        }.apply { start() }

        val previousLevel = containerLogger.level
        val failure = IllegalStateException("batch-heartbeat-start-secret")
        val acknowledgementStartAttempted = CompletableDeferred<Unit>()
        var listenerContainer: SqsMessageListenerContainer? = null
        containerLogger.addAppender(appender)
        containerLogger.level = Level.WARN

        try {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val handlerStarted = CompletableDeferred<Unit>()
            val handlerRelease = CompletableDeferred<Unit>()
            val handlerReturned = CompletableDeferred<Unit>()
            val receiveCalls = AtomicInteger()
            val messages = listOf(message(), message("message-2"))

            coEvery {
                operations.receive(QUEUE_URL, 2, 0, null)
            } coAnswers {
                if (receiveCalls.incrementAndGet() == 1) messages else awaitCancellation()
            }
            every { invoker.manualAcknowledgement } returns true
            coEvery {
                invoker.invokeBatch(any(), anyNullable<SqsBatchAcknowledgement>(), any())
            } coAnswers {
                handlerStarted.complete(Unit)
                handlerRelease.await()
                handlerReturned.complete(Unit)
            }
            val registry = ObservationRegistry.create().apply {
                observationConfig().observationHandler(
                    object: ObservationHandler<SqsObservationContext> {
                        override fun supportsContext(context: Observation.Context): Boolean =
                            context is SqsObservationContext

                        override fun onStart(context: SqsObservationContext) {
                            if (context.metadata.stage == SqsObservationStage.ACKNOWLEDGEMENT) {
                                acknowledgementStartAttempted.complete(Unit)
                                throw failure
                            }
                        }
                    },
                )
            }
            val currentContainer = container(
                operations = operations,
                invoker = invoker,
                dispatcher = dispatcher,
                batch = true,
                maxMessages = 2,
                acknowledgementMode = SqsAcknowledgementMode.MANUAL,
                messageVisibilityHeartbeatIntervalSeconds = 1,
                messageVisibilityHeartbeatSeconds = 30,
            )
            listenerContainer = currentContainer
            currentContainer.setObservationRuntime(
                SqsObservationRuntime(
                    registry = registry,
                    customizers = emptyList(),
                    factory = defaultSqsObservationFactory(defaultSqsObservationConventions()),
                ),
            )

            currentContainer.start()
            runCurrent()
            handlerStarted.await()
            advanceTimeBy(1.seconds)
            runCurrent()
            withContext(Dispatchers.Default.limitedParallelism(1)) {
                acknowledgementStartAttempted.awaitUntil(2.seconds)
            }
            val diagnostic = withContext(Dispatchers.Default.limitedParallelism(1)) {
                diagnosticObserved.awaitUntil(2.seconds)
            }

            log.debug { "diagnostic=$diagnostic" }
            diagnostic.formattedMessage shouldContain "BT4K-SQS-OBS-202"
            diagnostic.formattedMessage shouldNotContain QUEUE_URL
            diagnostic.formattedMessage shouldNotContain failure.message.orEmpty()
            diagnostic.throwableProxy.shouldBeNull()

            coVerify(exactly = 0) { operations.changeVisibilityBatch(any(), any()) }

            handlerRelease.complete(Unit)
            runCurrent()
            withContext(Dispatchers.Default.limitedParallelism(1)) {
                handlerReturned.awaitUntil(2.seconds)
            }
        } finally {
            listenerContainer?.let { container ->
                val stopped = CompletableDeferred<Unit>()
                container.stop { stopped.complete(Unit) }
                runCurrent()
                stopped.awaitUntil(2.seconds)
            }
            containerLogger.detachAppender(appender)
            containerLogger.level = previousLevel
        }
    }

    @Test
    @Suppress("LongMethod")
    fun `heartbeat failure does not change successful handler outcome`() = runTest {
        val containerLogger = LoggerFactory.getLogger(SqsMessageListenerContainer::class.java) as Logger
        val appender = ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>().apply { start() }
        val previousLevel = containerLogger.level
        containerLogger.addAppender(appender)
        containerLogger.level = Level.WARN
        try {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val handlerStarted = CompletableDeferred<Unit>()
            val handlerRelease = CompletableDeferred<Unit>()
            val heartbeatFailed = CompletableDeferred<Unit>()
            val deleteCalled = CompletableDeferred<Unit>()
            val receiveCalls = AtomicInteger()
            coEvery {
                operations.receive(QUEUE_URL, 1, 0, null)
            } coAnswers {
                if (receiveCalls.incrementAndGet() == 1) listOf(message()) else awaitCancellation()
            }
            coEvery {
                operations.changeVisibility(QUEUE_URL, "receipt-message-1", 30)
            } coAnswers {
                heartbeatFailed.complete(Unit)
                throw IllegalStateException("heartbeat unavailable")
            }
            coEvery {
                operations.delete(QUEUE_URL, "receipt-message-1")
            } coAnswers {
                deleteCalled.complete(Unit)
                DeleteMessageResponse.builder().build()
            }
            every { invoker.manualAcknowledgement } returns false
            coEvery {
                invoker.invoke(any(), any(), any())
            } coAnswers {
                handlerStarted.complete(Unit)
                heartbeatFailed.await()
                handlerRelease.await()
            }

            val container = container(
                operations = operations,
                invoker = invoker,
                dispatcher = dispatcher,
                messageVisibilityHeartbeatIntervalSeconds = 1,
                messageVisibilityHeartbeatSeconds = 30,
            )

            container.start()
            runCurrent()
            handlerStarted.await()
            advanceTimeBy(1.seconds)
            runCurrent()
            withContext(Dispatchers.Default.limitedParallelism(1)) {
                heartbeatFailed.awaitUntil(2.seconds)
            }
            runCurrent()
            handlerRelease.complete(Unit)
            runCurrent()
            withContext(Dispatchers.Default.limitedParallelism(1)) {
                withTimeout(2.seconds) { deleteCalled.await() }
            }
            coVerify(exactly = 1) { operations.delete(QUEUE_URL, "receipt-message-1") }

            val stopped = CompletableDeferred<Unit>()
            container.stop { stopped.complete(Unit) }
            runCurrent()
            stopped.await()
            appender.list.any { it.formattedMessage.contains("SQS visibility heartbeat failed") }.shouldBeTrue()
            appender.list.none { it.formattedMessage.contains("BT4K-SQS-OBS-202") }.shouldBeTrue()
        } finally {
            containerLogger.detachAppender(appender)
            containerLogger.level = previousLevel
        }
    }

    @Test
    fun `batch heartbeat protects pending messages and excludes manually acknowledged items`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val handlerStarted = CompletableDeferred<Unit>()
        val handlerRelease = CompletableDeferred<Unit>()
        val heartbeatObserved = CompletableDeferred<List<SqsChangeVisibilityRequest>>()
        val receiveCalls = AtomicInteger()
        val messages = listOf(message(), message("message-2"))
        coEvery {
            operations.receive(QUEUE_URL, 2, 0, null)
        } coAnswers {
            if (receiveCalls.incrementAndGet() == 1) messages else awaitCancellation()
        }
        coEvery {
            operations.changeVisibilityBatch(QUEUE_URL, any())
        } coAnswers {
            val requests = secondArg<Collection<SqsChangeVisibilityRequest>>().toList()
            heartbeatObserved.complete(requests)
            SqsBatchVisibilityResult(
                successfulMessageIds = requests.map { it.messageId },
                failed = emptyList(),
            )
        }
        coEvery {
            operations.deleteBatch(QUEUE_URL, any())
        } returns
                SqsBatchDeleteResult(listOf("entry-0"), emptyList())
        coEvery {
            invoker.invokeBatch(any(), anyNullable<SqsBatchAcknowledgement>(), any())
        } coAnswers {
            secondArg<SqsBatchAcknowledgement>().acknowledge(listOf(firstArg<List<SqsReceivedMessage>>().first()))
            handlerStarted.complete(Unit)
            handlerRelease.await()
        }
        every { invoker.manualAcknowledgement } returns true

        val container = container(
            operations = operations,
            invoker = invoker,
            dispatcher = dispatcher,
            batch = true,
            maxMessages = 2,
            acknowledgementMode = SqsAcknowledgementMode.MANUAL,
            messageVisibilityHeartbeatIntervalSeconds = 1,
            messageVisibilityHeartbeatSeconds = 30,
        )

        container.start()
        runCurrent()
        handlerStarted.await()
        advanceTimeBy(1.seconds)
        runCurrent()
        val requests = withContext(Dispatchers.Default.limitedParallelism(1)) {
            heartbeatObserved.awaitUntil(2.seconds)
        }
        requests.map { it.messageId } shouldBeEqualTo listOf("message-2")
        requests.single().timeoutSeconds shouldBeEqualTo 30

        handlerRelease.complete(Unit)
        runCurrent()

        val stopped = CompletableDeferred<Unit>()
        container.stop { stopped.complete(Unit) }
        runCurrent()
        stopped.await()

        coVerify(atLeast = 1) { operations.changeVisibilityBatch(QUEUE_URL, any()) }
    }

    private fun container(
        operations: SqsOperations,
        invoker: SqsListenerMethodInvoker,
        stopTimeoutMillis: Long = 1_000,
        dispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.IO,
        queue: String = QUEUE_URL,
        queueNotFoundStrategy: SqsQueueNotFoundStrategy = SqsQueueNotFoundStrategy.FAIL_FAST,
        batch: Boolean = false,
        maxMessages: Int = 1,
        acknowledgementMode: SqsAcknowledgementMode = SqsAcknowledgementMode.INHERIT,
        retry: SqsProperties.Retry = SqsProperties.Retry(),
        autoStartup: Boolean = true,
        interceptors: List<SqsListenerInterceptor> = emptyList(),
        messageVisibilityHeartbeatIntervalSeconds: Int? = null,
        messageVisibilityHeartbeatSeconds: Int? = null,
        concurrency: Int = 1,
        maxInFlight: Int = maxMessages * concurrency,
        fifoBatchGroupingStrategy: SqsFifoBatchGroupingStrategy =
            SqsFifoBatchGroupingStrategy.GROUP_BY_MESSAGE_GROUP_ID,
    ): SqsMessageListenerContainer {
        return SqsMessageListenerContainer(
            endpoint = SqsListenerEndpoint(
                id = "listener",
                queue = queue,
                queueNotFoundStrategy = queueNotFoundStrategy,
                maxMessages = maxMessages,
                waitTimeSeconds = 0,
                visibilityTimeoutSeconds = null,
                errorVisibilityTimeoutSeconds = null,
                messageVisibilityHeartbeatIntervalSeconds = messageVisibilityHeartbeatIntervalSeconds,
                messageVisibilityHeartbeatSeconds = messageVisibilityHeartbeatSeconds,
                autoStartup = autoStartup,
                phase = 0,
                concurrency = concurrency,
                stopTimeoutMillis = stopTimeoutMillis,
                retry = retry,
                batch = batch,
                acknowledgementMode = acknowledgementMode,
                maxInFlight = maxInFlight,
                fifoBatchGroupingStrategy = fifoBatchGroupingStrategy,
            ),
            operations = operations,
            invoker = invoker,
            interceptors = interceptors,
            dispatcher = dispatcher,
        )
    }

    private suspend fun assertObservationSetupFailureIsRedacted(
        failingStage: SqsObservationStage,
        batch: Boolean,
    ) {
        val containerLogger = LoggerFactory.getLogger(SqsMessageListenerContainer::class.java) as Logger
        val appender = ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>().apply { start() }
        val previousLevel = containerLogger.level
        val failure = IllegalStateException("telemetry-secret-${failingStage.name.lowercase()}")
        val receiveCalls = AtomicInteger()
        coEvery { operations.receive(QUEUE_URL, 1, 0, null) } coAnswers {
            if (receiveCalls.incrementAndGet() == 1) listOf(message()) else awaitCancellation()
        }
        every { invoker.manualAcknowledgement } returns true

        val runtime = SqsObservationRuntime(
            registry = ObservationRegistry.create(),
            customizers = emptyList(),
            factory = { context, _ ->
                if (context.metadata.stage == failingStage) throw failure
                Observation.NOOP
            },
        )

        val listenerContainer = container(
            operations = operations,
            invoker = invoker,
            batch = batch,
            acknowledgementMode = SqsAcknowledgementMode.MANUAL,
            retry = SqsProperties.Retry(maxAttempts = 1, initialBackoff = 10.seconds()),
        )
        listenerContainer.setObservationRuntime(runtime)
        containerLogger.addAppender(appender)
        containerLogger.level = Level.WARN

        try {
            listenerContainer.start()
            val expectedStage = "stage=${failingStage.name.lowercase()}"

            await atMost 2.seconds withPollInterval 5.milliseconds until {
                appender.list.any {
                    it.formattedMessage.contains("reason=telemetry_setup") &&
                            it.formattedMessage.contains(expectedStage)
                }
            }

            val diagnostic = appender.list.first {
                it.formattedMessage.contains("reason=telemetry_setup") &&
                        it.formattedMessage.contains(expectedStage)
            }
            log.debug { "diagnostic=$diagnostic" }
            diagnostic.formattedMessage shouldContain "BT4K-SQS-OBS-202"
            diagnostic.formattedMessage shouldContain expectedStage
            diagnostic.formattedMessage shouldNotContain QUEUE_URL
            diagnostic.formattedMessage shouldNotContain failure.message.orEmpty()
            diagnostic.throwableProxy.shouldBeNull()
        } finally {
            val stopped = CompletableDeferred<Unit>()
            listenerContainer.stop { stopped.complete(Unit) }
            stopped.awaitUntil(2.seconds)
            containerLogger.detachAppender(appender)
            containerLogger.level = previousLevel
        }
    }

    @Suppress("LongMethod")
    private suspend fun TestScope.assertFatalHeartbeatTelemetryErrorIsNotLogged(batch: Boolean) {
        val containerLogger = LoggerFactory.getLogger(SqsMessageListenerContainer::class.java) as Logger
        val appender = ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>().apply { start() }
        val previousLevel = containerLogger.level
        val fatal = AssertionError("fatal-heartbeat-telemetry-secret")
        val dispatcher = StandardTestDispatcher(testScheduler)
        val handlerStarted = CompletableDeferred<Unit>()
        val receiveCalls = AtomicInteger()
        val messages = if (batch) listOf(message(), message("message-2")) else listOf(message())
        coEvery {
            operations.receive(QUEUE_URL, messages.size, 0, null)
        } coAnswers {
            if (receiveCalls.incrementAndGet() == 1) messages else awaitCancellation()
        }
        every { invoker.manualAcknowledgement } returns true
        coEvery {
            invoker.invoke(any(), any(), any())
        } coAnswers {
            handlerStarted.complete(Unit)
            awaitCancellation()
        }
        coEvery {
            invoker.invokeBatch(any(), anyNullable<SqsBatchAcknowledgement>(), any())
        } coAnswers {
            handlerStarted.complete(Unit)
            awaitCancellation()
        }
        val runtime = SqsObservationRuntime(
            registry = ObservationRegistry.create(),
            customizers = emptyList(),
            factory = { context, _ ->
                if (context.metadata.stage == SqsObservationStage.ACKNOWLEDGEMENT) throw fatal
                Observation.NOOP
            },
        )
        val listenerContainer = container(
            operations = operations,
            invoker = invoker,
            dispatcher = dispatcher,
            batch = batch,
            maxMessages = messages.size,
            acknowledgementMode = SqsAcknowledgementMode.MANUAL,
            messageVisibilityHeartbeatIntervalSeconds = 1,
            messageVisibilityHeartbeatSeconds = 30,
        )
        listenerContainer.setObservationRuntime(runtime)
        containerLogger.addAppender(appender)
        containerLogger.level = Level.WARN
        try {
            listenerContainer.start()
            runCurrent()
            handlerStarted.await()
            advanceTimeBy(1.seconds)
            runCurrent()

            withContext(Dispatchers.Default.limitedParallelism(1)) {
                await atMost 2.seconds withPollInterval 5.milliseconds untilSuspending {
                    !listenerContainer.isRunning
                }
            }

            appender.list.none {
                it.formattedMessage.contains("SQS visibility heartbeat failed") ||
                        it.formattedMessage.contains(fatal.message.orEmpty()) ||
                        it.throwableProxy?.message?.contains(fatal.message.orEmpty()) == true
            }.shouldBeTrue()
            if (batch) {
                coVerify(exactly = 0) { operations.changeVisibilityBatch(any(), any()) }
            } else {
                coVerify(exactly = 0) { operations.changeVisibility(any(), any(), any()) }
            }
        } finally {
            val stopped = CompletableDeferred<Unit>()
            listenerContainer.stop { stopped.complete(Unit) }
            runCurrent()
            stopped.awaitUntil(2.seconds)
            containerLogger.detachAppender(appender)
            containerLogger.level = previousLevel
        }
    }

    private fun message(
        messageId: String = "message-1",
        messageGroupId: String? = null,
        receiveCount: String? = null,
    ): SqsReceivedMessage =
        SqsReceivedMessage(
            queueUrl = QUEUE_URL,
            message = Message.builder()
                .messageId(messageId)
                .receiptHandle("receipt-$messageId")
                .body("payload")
                .apply {
                    val attributes = buildMap {
                        messageGroupId?.let { put(MessageSystemAttributeName.MESSAGE_GROUP_ID, it) }
                        receiveCount?.let { put(MessageSystemAttributeName.APPROXIMATE_RECEIVE_COUNT, it) }
                    }
                    if (attributes.isNotEmpty()) attributes(attributes)
                }
                .build(),
        )

    private fun observationRuntime(recorder: ContainerObservationRecorder): SqsObservationRuntime {
        val registry = ObservationRegistry.create()
        registry.observationConfig().observationHandler(recorder)
        return SqsObservationRuntime(
            registry = registry,
            customizers = emptyList(),
            factory = defaultSqsObservationFactory(defaultSqsObservationConventions()),
        )
    }

    private fun assertHeartbeatObservationCounts(
        recorder: ContainerObservationRecorder,
        heartbeatCalls: Int,
    ) {
        recorder.snapshots.count {
            it.stage == SqsObservationStage.ACKNOWLEDGEMENT &&
                    it.acknowledgementAction == SqsAcknowledgementAction.CHANGE_VISIBILITY
        } shouldBeEqualTo heartbeatCalls
        recorder.snapshots.count {
            it.stage == SqsObservationStage.ACKNOWLEDGEMENT &&
                    it.acknowledgementAction == SqsAcknowledgementAction.ACK
        } shouldBeEqualTo 1
    }

    private data class ContainerObservationSnapshot(
        val stage: SqsObservationStage,
        val outcome: SqsObservationOutcome,
        val retryCount: Int,
        val attempt: Int?,
        val batch: Boolean,
        val batchSize: Int,
        val messageId: String?,
        val messageGroupId: String?,
        val messageDeduplicationId: String?,
        val failureStage: String?,
        val acknowledgementAction: SqsAcknowledgementAction?,
        val acknowledgementSuccessCount: Int,
        val acknowledgementFailureCount: Int,
        val delivery: SqsObservationDelivery,
    )

    private class ContainerObservationRecorder: ObservationHandler<SqsObservationContext> {
        val snapshots = CopyOnWriteArrayList<ContainerObservationSnapshot>()
        val retryEvents = AtomicInteger()

        override fun supportsContext(context: Observation.Context): Boolean = context is SqsObservationContext

        override fun onEvent(event: Observation.Event, context: SqsObservationContext) {
            if (event.name == "retry") {
                retryEvents.incrementAndGet()
            }
        }

        override fun onStop(context: SqsObservationContext) {
            snapshots += ContainerObservationSnapshot(
                stage = context.metadata.stage,
                outcome = context.outcome,
                retryCount = context.retryCount,
                attempt = context.attempt,
                batch = context.metadata.batch,
                batchSize = context.metadata.batchSize,
                messageId = context.metadata.messageId,
                messageGroupId = context.metadata.messageGroupId,
                messageDeduplicationId = context.metadata.messageDeduplicationId,
                failureStage = context.failureStage,
                acknowledgementAction = context.metadata.acknowledgementAction,
                acknowledgementSuccessCount = context.acknowledgementSuccessCount,
                acknowledgementFailureCount = context.acknowledgementFailureCount,
                delivery = context.metadata.delivery,
            )
        }
    }

    private class ContainerConvertedPayload

    private class ContainerConvertedListener {
        @Suppress("UNUSED_PARAMETER")
        fun handle(payload: ContainerConvertedPayload) = Unit
    }

    private class ContainerBatchConvertedListener {
        @Suppress("UNUSED_PARAMETER")
        fun handle(payloads: List<ContainerConvertedPayload>) = Unit
    }

    private class ContainerThrowingListener(
        private val failure: RuntimeException,
    ) {
        @Suppress("UNUSED_PARAMETER")
        fun handle(payload: String): Nothing = throw failure
    }
}
