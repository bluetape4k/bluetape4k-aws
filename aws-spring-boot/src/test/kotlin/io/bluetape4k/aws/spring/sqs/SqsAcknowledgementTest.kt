package io.bluetape4k.aws.spring.sqs

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.micrometer.observation.Observation
import io.micrometer.observation.ObservationHandler
import io.micrometer.observation.ObservationRegistry
import io.micrometer.observation.ObservationView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import software.amazon.awssdk.services.sqs.model.ChangeMessageVisibilityResponse
import software.amazon.awssdk.services.sqs.model.DeleteMessageResponse
import software.amazon.awssdk.services.sqs.model.Message
import software.amazon.awssdk.services.sqs.model.QueueAttributeName
import software.amazon.awssdk.services.sqs.model.SendMessageResponse
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class SqsAcknowledgementTest {

    @Test
    fun `terminal race keeps heartbeat observation count equal to actual visibility IO`() = runTest {
        val operations = RecordingSqsOperations()
        val recorder = AcknowledgementObservationRecorder()
        val heartbeatStarted = CountDownLatch(1)
        val releaseHeartbeat = CountDownLatch(1)
        val registry = ObservationRegistry.create()
        registry.observationConfig().observationHandler(recorder)
        registry.observationConfig().observationHandler(object : ObservationHandler<SqsObservationContext> {
            override fun supportsContext(context: Observation.Context): Boolean =
                context is SqsObservationContext

            override fun onStart(context: SqsObservationContext) {
                if (context.metadata.acknowledgementAction == SqsAcknowledgementAction.CHANGE_VISIBILITY) {
                    heartbeatStarted.countDown()
                    check(releaseHeartbeat.await(5, TimeUnit.SECONDS))
                }
            }
        })
        val acknowledgement = acknowledgement(
            operations,
            observationRuntime = SqsObservationRuntime(
                registry = registry,
                customizers = emptyList(),
                factory = defaultSqsObservationFactory(defaultSqsObservationConventions()),
            ),
        )

        val heartbeat = async(Dispatchers.Default) { acknowledgement.heartbeat(30) {} }
        withContext(Dispatchers.IO) {
            check(heartbeatStarted.await(5, TimeUnit.SECONDS))
        }
        val terminal = async(Dispatchers.Default) { acknowledgement.acknowledge() }
        releaseHeartbeat.countDown()
        heartbeat.await()
        terminal.await()

        recorder.contexts.count {
            it.metadata.acknowledgementAction == SqsAcknowledgementAction.CHANGE_VISIBILITY
        } shouldBeEqualTo operations.changeVisibilityCalls
        recorder.contexts.count {
            it.metadata.acknowledgementAction == SqsAcknowledgementAction.ACK
        } shouldBeEqualTo operations.deleteCalls
    }

    @Test
    fun `actual acknowledgement IO creates one observation and duplicate creates none`() = runTest {
        val operations = RecordingSqsOperations()
        val recorder = AcknowledgementObservationRecorder()
        val acknowledgement = acknowledgement(operations, observationRuntime = observationRuntime(recorder))

        acknowledgement.acknowledge()
        acknowledgement.acknowledge()

        operations.deleteCalls shouldBeEqualTo 1
        recorder.contexts.size shouldBeEqualTo 1
        recorder.contexts.single().apply {
            metadata.stage shouldBeEqualTo SqsObservationStage.ACKNOWLEDGEMENT
            metadata.acknowledgementAction shouldBeEqualTo SqsAcknowledgementAction.ACK
            metadata.batch.shouldBeFalse()
            acknowledgementSuccessCount shouldBeEqualTo 1
            acknowledgementFailureCount shouldBeEqualTo 0
            outcome shouldBeEqualTo SqsObservationOutcome.SUCCESS
        }
    }

    @Test
    fun `acknowledgement cancellation keeps identity and records cancelled outcome`() = runTest {
        val cancellation = CancellationException("cancel acknowledgement")
        val operations = RecordingSqsOperations().apply { deleteFailure = cancellation }
        val recorder = AcknowledgementObservationRecorder()
        val acknowledgement = acknowledgement(operations, observationRuntime = observationRuntime(recorder))

        val actual = assertFailsWith<CancellationException> { acknowledgement.acknowledge() }

        actual shouldBeEqualTo cancellation
        acknowledgement.completed.shouldBeFalse()
        recorder.contexts.single().apply {
            outcome shouldBeEqualTo SqsObservationOutcome.CANCELLED
            failureStage shouldBeEqualTo "acknowledgement"
            acknowledgementFailureCount shouldBeEqualTo 1
        }
    }

    @Test
    fun `detached acknowledgement uses the current call-time observation parent`() = runTest {
        val registry = ObservationRegistry.create()
        val recorder = AcknowledgementObservationRecorder()
        registry.observationConfig().observationHandler(recorder)
        val runtime = SqsObservationRuntime(
            registry = registry,
            customizers = emptyList(),
            factory = defaultSqsObservationFactory(defaultSqsObservationConventions()),
        )
        val acknowledgement = acknowledgement(RecordingSqsOperations(), observationRuntime = runtime)
        val completedProcess = Observation.start("completed-process", registry)
        completedProcess.stop()
        val currentParent = Observation.start("current-parent", registry)

        currentParent.openScope().use {
            acknowledgement.acknowledge()
        }
        currentParent.stop()

        recorder.parents.single() shouldBeEqualTo currentParent
    }

    @Test
    fun `nack error and change visibility success record their actual IO outcomes`() = runTest {
        val operations = RecordingSqsOperations().apply {
            changeVisibilityFailure = IllegalStateException("visibility failed")
        }
        val recorder = AcknowledgementObservationRecorder()
        val acknowledgement = acknowledgement(operations, observationRuntime = observationRuntime(recorder))

        assertFailsWith<IllegalStateException> { acknowledgement.nack(0) }
        operations.changeVisibilityFailure = null
        acknowledgement.changeVisibility(15)

        recorder.contexts.map { it.metadata.acknowledgementAction } shouldBeEqualTo
            listOf(SqsAcknowledgementAction.NACK, SqsAcknowledgementAction.CHANGE_VISIBILITY)
        recorder.contexts.map { it.outcome } shouldBeEqualTo
            listOf(SqsObservationOutcome.ERROR, SqsObservationOutcome.SUCCESS)
        recorder.contexts.map { it.acknowledgementFailureCount } shouldBeEqualTo listOf(1, 0)
        acknowledgement.completed.shouldBeFalse()
    }

    @Test
    fun `observation setup fails closed before acknowledgement IO`() = runTest {
        val setupFailure = IllegalStateException("observation setup failed")
        val operations = RecordingSqsOperations()
        val runtime = SqsObservationRuntime(
            registry = ObservationRegistry.create(),
            customizers = listOf(SqsObservationContextCustomizer { throw setupFailure }),
            factory = defaultSqsObservationFactory(defaultSqsObservationConventions()),
        )
        val acknowledgement = acknowledgement(operations, observationRuntime = runtime)

        val actual = assertFailsWith<IllegalStateException> { acknowledgement.acknowledge() }

        actual shouldBeEqualTo setupFailure
        operations.deleteCalls shouldBeEqualTo 0
        acknowledgement.completed.shouldBeFalse()
    }

    @Test
    fun `business failure stays primary when observation stop also fails`() = runTest {
        val businessFailure = IllegalStateException("delete failed")
        val stopFailure = IllegalArgumentException("observation stop failed")
        val operations = RecordingSqsOperations().apply { deleteFailure = businessFailure }
        val registry = ObservationRegistry.create()
        registry.observationConfig().observationHandler(FailingAcknowledgementStopHandler(stopFailure))
        val runtime = SqsObservationRuntime(
            registry = registry,
            customizers = emptyList(),
            factory = defaultSqsObservationFactory(defaultSqsObservationConventions()),
        )
        val acknowledgement = acknowledgement(operations, observationRuntime = runtime)

        val actual = assertFailsWith<IllegalStateException> { acknowledgement.acknowledge() }

        actual shouldBeEqualTo businessFailure
        actual.suppressed.toList() shouldBeEqualTo listOf(stopFailure)
        acknowledgement.completed.shouldBeFalse()
    }

    @Test
    fun `acknowledge marks completed only after delete succeeds`() = runSuspendIO {
        val operations = RecordingSqsOperations()
        val interceptor = RecordingInterceptor()
        val acknowledgement = acknowledgement(operations, interceptor)

        acknowledgement.completed.shouldBeFalse()

        acknowledgement.acknowledge()

        acknowledgement.completed.shouldBeTrue()
        operations.deleteCalls shouldBeEqualTo 1
        interceptor.afterFailures shouldBeEqualTo listOf(null)
    }

    @Test
    fun `failed acknowledge leaves acknowledgement incomplete and retryable`() = runSuspendIO {
        val operations = RecordingSqsOperations().apply {
            deleteFailure = IllegalStateException("delete failed")
        }
        val interceptor = RecordingInterceptor()
        val acknowledgement = acknowledgement(operations, interceptor)

        assertFailsWith<IllegalStateException> {
            acknowledgement.acknowledge()
        }

        acknowledgement.completed.shouldBeFalse()
        operations.deleteCalls shouldBeEqualTo 1
        interceptor.afterFailures.single()?.message shouldBeEqualTo "delete failed"

        operations.deleteFailure = null
        acknowledgement.acknowledge()

        acknowledgement.completed.shouldBeTrue()
        operations.deleteCalls shouldBeEqualTo 2
    }

    @Test
    fun `failed nack leaves acknowledgement incomplete and retryable`() = runSuspendIO {
        val operations = RecordingSqsOperations().apply {
            changeVisibilityFailure = IllegalStateException("visibility failed")
        }
        val acknowledgement = acknowledgement(operations)

        assertFailsWith<IllegalStateException> {
            acknowledgement.nack(timeoutSeconds = 0)
        }

        acknowledgement.completed.shouldBeFalse()
        operations.changeVisibilityCalls shouldBeEqualTo 1

        operations.changeVisibilityFailure = null
        acknowledgement.nack(timeoutSeconds = 0)

        acknowledgement.completed.shouldBeTrue()
        operations.changeVisibilityCalls shouldBeEqualTo 2
    }

    @Test
    fun `changeVisibility does not complete acknowledgement`() = runSuspendIO {
        val operations = RecordingSqsOperations()
        val acknowledgement = acknowledgement(operations)

        acknowledgement.changeVisibility(timeoutSeconds = 5)

        acknowledgement.completed.shouldBeFalse()
        operations.changeVisibilityCalls shouldBeEqualTo 1
    }

    private fun acknowledgement(
        operations: RecordingSqsOperations,
        interceptor: SqsListenerInterceptor = RecordingInterceptor(),
        observationRuntime: SqsObservationRuntime? = null,
    ): DefaultSqsAcknowledgement =
        DefaultSqsAcknowledgement(
            context = SqsListenerInvocationContext(
                listenerId = "listener",
                queueUrl = "https://sqs.us-east-1.amazonaws.com/123/orders",
                message = SqsReceivedMessage(
                    queueUrl = "https://sqs.us-east-1.amazonaws.com/123/orders",
                    message = Message.builder()
                        .messageId("message-1")
                        .body("payload")
                        .receiptHandle("receipt-1")
                        .build(),
                ),
                attempt = 1,
            ),
            operations = operations,
            interceptors = listOf(interceptor),
            observationRuntime = observationRuntime,
        )

    private fun observationRuntime(recorder: AcknowledgementObservationRecorder): SqsObservationRuntime {
        val registry = ObservationRegistry.create()
        registry.observationConfig().observationHandler(recorder)
        return SqsObservationRuntime(
            registry = registry,
            customizers = emptyList(),
            factory = defaultSqsObservationFactory(defaultSqsObservationConventions()),
        )
    }

    private class AcknowledgementObservationRecorder : ObservationHandler<SqsObservationContext> {
        val contexts = mutableListOf<SqsObservationContext>()
        val parents = mutableListOf<ObservationView?>()

        override fun supportsContext(context: Observation.Context): Boolean = context is SqsObservationContext

        override fun onStart(context: SqsObservationContext) {
            parents.add(context.parentObservation)
        }

        override fun onStop(context: SqsObservationContext) {
            contexts += context
        }
    }

    private class FailingAcknowledgementStopHandler(
        private val failure: Throwable,
    ) : ObservationHandler<SqsObservationContext> {
        override fun supportsContext(context: Observation.Context): Boolean = context is SqsObservationContext

        override fun onStop(context: SqsObservationContext) {
            throw failure
        }
    }

    private class RecordingInterceptor : SqsListenerInterceptor {
        val afterFailures = mutableListOf<Throwable?>()

        override suspend fun afterAcknowledgement(
            context: SqsListenerInvocationContext,
            action: SqsAcknowledgementAction,
            error: Throwable?,
        ) {
            afterFailures += error
        }
    }

    private class RecordingSqsOperations : SqsOperations {
        var deleteCalls = 0
        var changeVisibilityCalls = 0
        var deleteFailure: Throwable? = null
        var changeVisibilityFailure: Throwable? = null

        override suspend fun getQueueUrl(queueName: String): String = "queue-url"

        override suspend fun createQueue(
            queueName: String,
            attributes: Map<QueueAttributeName, String>,
        ): String = "queue-url"

        override suspend fun createConfiguredQueue(queueName: String): String = "queue-url"

        override suspend fun send(
            queueUrl: String,
            body: String,
            delaySeconds: Int?,
        ): SendMessageResponse =
            SendMessageResponse.builder().messageId("message-id").build()

        override suspend fun receive(
            queueUrl: String,
            maxMessages: Int,
            waitTimeSeconds: Int,
            visibilityTimeoutSeconds: Int?,
        ): List<SqsReceivedMessage> = emptyList()

        override suspend fun delete(
            queueUrl: String,
            receiptHandle: String,
        ): DeleteMessageResponse {
            deleteCalls++
            deleteFailure?.let { throw it }
            return DeleteMessageResponse.builder().build()
        }

        override suspend fun changeVisibility(
            queueUrl: String,
            receiptHandle: String,
            timeoutSeconds: Int,
        ): ChangeMessageVisibilityResponse {
            changeVisibilityCalls++
            changeVisibilityFailure?.let { throw it }
            return ChangeMessageVisibilityResponse.builder().build()
        }

        override fun receiveFlow(
            queueUrl: String,
            maxMessages: Int,
            waitTimeSeconds: Int,
            visibilityTimeoutSeconds: Int?,
        ): Flow<SqsReceivedMessage> =
            emptyFlow()
    }
}
