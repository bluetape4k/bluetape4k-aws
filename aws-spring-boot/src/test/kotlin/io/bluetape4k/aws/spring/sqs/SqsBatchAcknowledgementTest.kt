package io.bluetape4k.aws.spring.sqs

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.micrometer.observation.Observation
import io.micrometer.observation.ObservationHandler
import io.micrometer.observation.ObservationRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import software.amazon.awssdk.services.sqs.model.ChangeMessageVisibilityResponse
import software.amazon.awssdk.services.sqs.model.DeleteMessageResponse
import software.amazon.awssdk.services.sqs.model.Message
import software.amazon.awssdk.services.sqs.model.QueueAttributeName
import software.amazon.awssdk.services.sqs.model.SendMessageResponse
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

class SqsBatchAcknowledgementTest {

    @Test
    fun `cancellation while committing completed IO does not strand later waiters`() = runTest {
        val operations = RecordingBatchOperations()
        val acknowledgement = acknowledgement(messages(2), operations)
        val mutex = acknowledgement.javaClass.getDeclaredField("mutex").let { field ->
            field.isAccessible = true
            field.get(acknowledgement) as Mutex
        }
        val commitBlocked = CompletableDeferred<Unit>()
        operations.beforeDeleteReturn = {
            mutex.lock()
            commitBlocked.complete(Unit)
        }
        val cancellation = CancellationException("cancel during commit")

        val first = async(start = CoroutineStart.UNDISPATCHED) { acknowledgement.acknowledge() }
        commitBlocked.await()
        first.cancel(cancellation)
        mutex.unlock()
        assertFailsWith<CancellationException> { first.await() }

        withTimeout(1_000) {
            acknowledgement.acknowledge().status shouldBeEqualTo SqsBatchAcknowledgementStatus.SUCCESS
        }
        operations.deleteBatchCalls shouldBeEqualTo 1
    }

    @Test
    fun `batch actual IO records partial counts without individual identifiers`() = runTest {
        val messages = messages(3)
        val operations = RecordingBatchOperations().apply {
            deleteBatchResult = SqsBatchDeleteResult(
                successfulEntryIds = listOf("entry-0", "entry-2"),
                failed = listOf(SqsBatchDeleteFailure("entry-1", "AccessDenied", "denied", true)),
            )
        }
        val recorder = BatchObservationRecorder()
        val acknowledgement = acknowledgement(
            messages,
            operations,
            observationRuntime = observationRuntime(recorder),
        )

        acknowledgement.acknowledge()

        recorder.contexts.single().apply {
            metadata.stage shouldBeEqualTo SqsObservationStage.ACKNOWLEDGEMENT
            metadata.acknowledgementAction shouldBeEqualTo SqsAcknowledgementAction.ACK
            metadata.batch.shouldBeTrue()
            metadata.batchSize shouldBeEqualTo 3
            metadata.messageId shouldBeEqualTo null
            metadata.messageGroupId shouldBeEqualTo null
            metadata.messageDeduplicationId shouldBeEqualTo null
            acknowledgementSuccessCount shouldBeEqualTo 2
            acknowledgementFailureCount shouldBeEqualTo 1
            outcome shouldBeEqualTo SqsObservationOutcome.PARTIAL
        }
    }

    @Test
    fun `already terminal and prevalidation paths create no extra observation`() = runTest {
        val messages = messages(2)
        val operations = RecordingBatchOperations()
        val recorder = BatchObservationRecorder()
        val acknowledgement = acknowledgement(
            messages,
            operations,
            observationRuntime = observationRuntime(recorder),
        )

        acknowledgement.acknowledge()
        acknowledgement.acknowledge()
        assertFailsWith<IllegalArgumentException> {
            acknowledgement.acknowledge(messages + message(99))
        }

        operations.deleteBatchCalls shouldBeEqualTo 1
        recorder.contexts.size shouldBeEqualTo 1
    }

    @Test
    fun `cancellation rollback completes waiter outside mutex and permits retry for 1000 races`() = runTest {
        repeat(1_000) {
            val messages = messages(2)
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val operations = RecordingBatchOperations().apply {
                deleteStarted = started
                deleteRelease = release
            }
            val acknowledgement = acknowledgement(messages, operations)
            val first = async(start = CoroutineStart.UNDISPATCHED) { acknowledgement.acknowledge() }
            started.await()
            val waiter = async(start = CoroutineStart.UNDISPATCHED) { acknowledgement.acknowledge() }

            operations.deleteRelease = null
            first.cancel(CancellationException("cancel batch acknowledgement"))
            first.join()

            waiter.await().status shouldBeEqualTo SqsBatchAcknowledgementStatus.SUCCESS
            acknowledgement.completed.shouldBeTrue()
            operations.deleteBatchCalls shouldBeEqualTo 2
        }
    }

    @Test
    fun `waiting duplicate shares one actual IO observation`() = runTest {
        val messages = messages(2)
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val operations = RecordingBatchOperations().apply {
            deleteStarted = started
            deleteRelease = release
        }
        val recorder = BatchObservationRecorder()
        val acknowledgement = acknowledgement(messages, operations, observationRuntime = observationRuntime(recorder))

        val first = async(start = CoroutineStart.UNDISPATCHED) { acknowledgement.acknowledge() }
        started.await()
        val waiter = async(start = CoroutineStart.UNDISPATCHED) { acknowledgement.acknowledge() }
        release.complete(Unit)

        first.await().status shouldBeEqualTo SqsBatchAcknowledgementStatus.SUCCESS
        waiter.await().status shouldBeEqualTo SqsBatchAcknowledgementStatus.SUCCESS
        operations.deleteBatchCalls shouldBeEqualTo 1
        recorder.contexts.size shouldBeEqualTo 1
    }

    @Test
    fun `cancellation completes interceptor and observation cleanup before retry`() = runTest {
        val messages = messages(2)
        val cancellation = CancellationException("cancel batch acknowledgement")
        val stopFailure = IllegalStateException("observation stop failed")
        val operations = RecordingBatchOperations().apply { deleteBatchFailure = cancellation }
        val activeAfterHooks = mutableListOf<Boolean>()
        val interceptor = object : SqsListenerInterceptor {
            override suspend fun afterAcknowledgement(
                context: SqsListenerInvocationContext,
                action: SqsAcknowledgementAction,
                error: Throwable?,
                correlation: SqsListenerBatchCorrelation,
                batchSize: Int,
            ) {
                activeAfterHooks += currentCoroutineContext().isActive
            }
        }
        val registry = ObservationRegistry.create()
        registry.observationConfig().observationHandler(FailFirstBatchStopHandler(stopFailure))
        val runtime = SqsObservationRuntime(
            registry = registry,
            customizers = emptyList(),
            factory = defaultSqsObservationFactory(defaultSqsObservationConventions()),
        )
        val acknowledgement = acknowledgement(
            messages,
            operations,
            interceptors = listOf(interceptor),
            observationRuntime = runtime,
        )

        val actual = assertFailsWith<CancellationException> { acknowledgement.acknowledge() }

        actual shouldBeEqualTo cancellation
        actual.suppressed.toList() shouldBeEqualTo listOf(stopFailure)
        activeAfterHooks.single().shouldBeTrue()
        acknowledgement.pending.size shouldBeEqualTo 2

        operations.deleteBatchFailure = null
        acknowledgement.acknowledge().status shouldBeEqualTo SqsBatchAcknowledgementStatus.SUCCESS
        acknowledgement.completed.shouldBeTrue()
    }

    @Test
    fun `acknowledge deletes all pending and completes`() = runSuspendIO {
        val messages = messages(3)
        val operations = RecordingBatchOperations()
        val acknowledgement = acknowledgement(messages, operations)

        val result = acknowledgement.acknowledge()

        result.operation shouldBeEqualTo SqsBatchAcknowledgementOperation.ACKNOWLEDGE
        result.status shouldBeEqualTo SqsBatchAcknowledgementStatus.SUCCESS
        result.successfulMessageIds shouldBeEqualTo listOf("message-1", "message-2", "message-3")
        acknowledgement.pending shouldBeEqualTo emptyList()
        acknowledgement.completed.shouldBeTrue()
        operations.deleteBatchCalls shouldBeEqualTo 1
        operations.deleteRequests.single() shouldBeEqualTo listOf("receipt-1", "receipt-2", "receipt-3")
    }

    @Test
    fun `partial delete keeps failed item pending`() = runSuspendIO {
        val messages = messages(3)
        val operations = RecordingBatchOperations().apply {
            deleteBatchResult = SqsBatchDeleteResult(
                successfulEntryIds = listOf("entry-0", "entry-2"),
                failed = listOf(SqsBatchDeleteFailure("entry-1", "AccessDenied", "denied", true)),
            )
        }
        val acknowledgement = acknowledgement(messages, operations)

        val result = acknowledgement.acknowledge()

        result.status shouldBeEqualTo SqsBatchAcknowledgementStatus.PARTIAL_FAILURE
        result.successfulMessageIds shouldBeEqualTo listOf("message-1", "message-3")
        result.failed.single().messageId shouldBeEqualTo "message-2"
        acknowledgement.pending.map { it.messageId } shouldBeEqualTo listOf("message-2")
        acknowledgement.completed.shouldBeFalse()
    }

    @Test
    fun `nack success becomes deferred and does not delete`() = runSuspendIO {
        val messages = messages(2)
        val operations = RecordingBatchOperations()
        val acknowledgement = acknowledgement(messages, operations)

        val result = acknowledgement.nack(messages, timeoutSeconds = 30)

        result.operation shouldBeEqualTo SqsBatchAcknowledgementOperation.NACK
        result.status shouldBeEqualTo SqsBatchAcknowledgementStatus.SUCCESS
        acknowledgement.pending shouldBeEqualTo emptyList()
        acknowledgement.completed.shouldBeTrue()
        operations.deleteBatchCalls shouldBeEqualTo 0
        operations.visibilityRequests.single().map { it.timeoutSeconds } shouldBeEqualTo listOf(30, 30)
    }

    @Test
    fun `change visibility keeps messages pending`() = runSuspendIO {
        val messages = messages(1)
        val operations = RecordingBatchOperations()
        val acknowledgement = acknowledgement(messages, operations)

        val result = acknowledgement.changeVisibility(messages, timeoutSeconds = 15)

        result.operation shouldBeEqualTo SqsBatchAcknowledgementOperation.CHANGE_VISIBILITY
        result.status shouldBeEqualTo SqsBatchAcknowledgementStatus.SUCCESS
        acknowledgement.pending.map { it.messageId } shouldBeEqualTo listOf("message-1")
        acknowledgement.completed.shouldBeFalse()
    }

    @Test
    fun `concurrent duplicate ack is linearized`() = runTest {
        val messages = messages(2)
        val operations = RecordingBatchOperations()
        val acknowledgement = acknowledgement(messages, operations)

        val results = listOf(
            async { acknowledgement.acknowledge(messages) },
            async { acknowledgement.acknowledge(messages) },
        ).awaitAll()

        operations.deleteBatchCalls shouldBeEqualTo 1
        results.forEach { it.successfulMessageIds shouldBeEqualTo listOf("message-1", "message-2") }
        acknowledgement.completed.shouldBeTrue()
    }

    @Test
    fun `foreign and eleven item inputs fail before AWS`() = runSuspendIO {
        val messages = messages(2)
        val operations = RecordingBatchOperations()
        val acknowledgement = acknowledgement(messages, operations)

        assertFailsWith<IllegalArgumentException> {
            acknowledgement.acknowledge(messages + message(99))
        }
        assertFailsWith<IllegalArgumentException> {
            acknowledgement.acknowledge(List(11) { messages.first() })
        }
        operations.deleteBatchCalls shouldBeEqualTo 0
    }

    @Test
    fun `fifo predecessor blocks later acknowledgement`() = runSuspendIO {
        val messages = messages(2, groupId = "group-1")
        val operations = RecordingBatchOperations()
        val acknowledgement = acknowledgement(messages, operations)

        val result = acknowledgement.acknowledge(listOf(messages[1]))

        result.status shouldBeEqualTo SqsBatchAcknowledgementStatus.FAILURE
        result.failed.single().code shouldBeEqualTo "fifo_predecessor_pending"
        operations.deleteBatchCalls shouldBeEqualTo 0
        acknowledgement.pending.map { it.messageId } shouldBeEqualTo listOf("message-1", "message-2")
    }

    @Test
    fun `result string does not expose message identifiers or handles`() = runSuspendIO {
        val messages = messages(1)
        val operations = RecordingBatchOperations()
        val acknowledgement = acknowledgement(messages, operations)

        val result = acknowledgement.acknowledge()
        val rendered = "${result}${result.failed}"

        rendered shouldContain "SqsBatchAcknowledgementResult"
        check("message-1" !in rendered)
        check("receipt-1" !in rendered)
        check("payload-1" !in rendered)
    }

    @Test
    fun `operation guard cancels before an AWS batch call`() = runSuspendIO {
        val operations = RecordingBatchOperations()
        val recorder = BatchObservationRecorder()
        val acknowledgement = DefaultSqsBatchAcknowledgement(
            listenerId = "listener",
            queueUrl = QUEUE_URL,
            messages = messages(2),
            operations = operations,
            interceptors = emptyList(),
            operationGuard = { throw CancellationException("listener is stopping") },
            observationRuntime = observationRuntime(recorder),
        )

        assertFailsWith<CancellationException> { acknowledgement.acknowledge() }

        operations.deleteBatchCalls shouldBeEqualTo 0
        acknowledgement.pending.size shouldBeEqualTo 2
        acknowledgement.completed.shouldBeFalse()
        recorder.contexts shouldBeEqualTo emptyList()
    }

    private fun acknowledgement(
        messages: List<SqsReceivedMessage>,
        operations: RecordingBatchOperations,
        interceptors: List<SqsListenerInterceptor> = emptyList(),
        observationRuntime: SqsObservationRuntime? = null,
    ): DefaultSqsBatchAcknowledgement =
        DefaultSqsBatchAcknowledgement(
            listenerId = "listener",
            queueUrl = QUEUE_URL,
            messages = messages,
            operations = operations,
            interceptors = interceptors,
            observationRuntime = observationRuntime,
        )

    private fun observationRuntime(recorder: BatchObservationRecorder): SqsObservationRuntime {
        val registry = ObservationRegistry.create()
        registry.observationConfig().observationHandler(recorder)
        return SqsObservationRuntime(
            registry = registry,
            customizers = emptyList(),
            factory = defaultSqsObservationFactory(defaultSqsObservationConventions()),
        )
    }

    private class BatchObservationRecorder : ObservationHandler<SqsObservationContext> {
        val contexts = mutableListOf<SqsObservationContext>()

        override fun supportsContext(context: Observation.Context): Boolean = context is SqsObservationContext

        override fun onStop(context: SqsObservationContext) {
            contexts += context
        }
    }

    private class FailFirstBatchStopHandler(
        private val failure: Throwable,
    ) : ObservationHandler<SqsObservationContext> {
        private val remainingFailures = AtomicInteger(1)

        override fun supportsContext(context: Observation.Context): Boolean = context is SqsObservationContext

        override fun onStop(context: SqsObservationContext) {
            if (remainingFailures.getAndDecrement() > 0) {
                throw failure
            }
        }
    }

    private fun messages(count: Int, groupId: String? = null): List<SqsReceivedMessage> =
        (1..count).map { message(it, groupId) }

    private fun message(index: Int, groupId: String? = null): SqsReceivedMessage {
        val builder = Message.builder()
            .messageId("message-$index")
            .receiptHandle("receipt-$index")
            .body("payload-$index")
        groupId?.let {
            builder.attributes(
                mapOf(software.amazon.awssdk.services.sqs.model.MessageSystemAttributeName.MESSAGE_GROUP_ID to it)
            )
        }
        return SqsReceivedMessage(QUEUE_URL, builder.build())
    }

    private class RecordingBatchOperations : SqsOperations {
        var deleteBatchCalls = 0
        var deleteBatchResult = SqsBatchDeleteResult(emptyList(), emptyList())
        var deleteBatchFailure: Throwable? = null
        var deleteStarted: CompletableDeferred<Unit>? = null
        var deleteRelease: CompletableDeferred<Unit>? = null
        var beforeDeleteReturn: suspend () -> Unit = {}
        val deleteRequests = CopyOnWriteArrayList<List<String>>()
        val visibilityRequests = CopyOnWriteArrayList<List<SqsChangeVisibilityRequest>>()

        override suspend fun getQueueUrl(queueName: String): String = QUEUE_URL
        override suspend fun createQueue(
            queueName: String,
            attributes: Map<QueueAttributeName, String>,
        ): String = QUEUE_URL
        override suspend fun createConfiguredQueue(queueName: String): String = QUEUE_URL
        override suspend fun send(queueUrl: String, body: String, delaySeconds: Int?): SendMessageResponse =
            SendMessageResponse.builder().build()
        override suspend fun receive(
            queueUrl: String,
            maxMessages: Int,
            waitTimeSeconds: Int,
            visibilityTimeoutSeconds: Int?,
        ): List<SqsReceivedMessage> = emptyList()
        override suspend fun delete(queueUrl: String, receiptHandle: String): DeleteMessageResponse =
            DeleteMessageResponse.builder().build()
        override suspend fun changeVisibility(
            queueUrl: String,
            receiptHandle: String,
            timeoutSeconds: Int,
        ): ChangeMessageVisibilityResponse = ChangeMessageVisibilityResponse.builder().build()
        override fun receiveFlow(
            queueUrl: String,
            maxMessages: Int,
            waitTimeSeconds: Int,
            visibilityTimeoutSeconds: Int?,
        ): Flow<SqsReceivedMessage> = emptyFlow()

        override suspend fun deleteBatch(
            queueUrl: String,
            receiptHandles: Collection<String>,
        ): SqsBatchDeleteResult {
            deleteBatchCalls++
            deleteRequests += receiptHandles.toList()
            deleteStarted?.complete(Unit)
            deleteRelease?.let { it.await() }
            deleteBatchFailure?.let { throw it }
            beforeDeleteReturn()
            return if (deleteBatchResult.successfulEntryIds.isEmpty() && deleteBatchResult.failed.isEmpty()) {
                SqsBatchDeleteResult(receiptHandles.indices.map { "entry-$it" }, emptyList())
            } else {
                deleteBatchResult
            }
        }

        override suspend fun changeVisibilityBatch(
            queueUrl: String,
            requests: Collection<SqsChangeVisibilityRequest>,
        ): SqsBatchVisibilityResult {
            visibilityRequests += requests.toList()
            return SqsBatchVisibilityResult(requests.map { it.messageId }, emptyList())
        }
    }

    companion object {
        private const val QUEUE_URL = "https://sqs.local/orders"
    }
}
