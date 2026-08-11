package io.bluetape4k.aws.spring.sqs

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.asCoroutineDispatcher
import software.amazon.awssdk.services.sqs.model.ChangeMessageVisibilityResponse
import software.amazon.awssdk.services.sqs.model.DeleteMessageResponse
import software.amazon.awssdk.services.sqs.model.QueueAttributeName
import software.amazon.awssdk.services.sqs.model.SendMessageResponse
import java.lang.management.ManagementFactory
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger

/**
 * 실제 SQS listener container/invoker 경로를 fake AWS boundary에 연결하는 성능 adapter입니다.
 * 절대 성능을 주장하지 않고 동일 JVM·dispatcher·fixture의 controlled regression만 측정합니다.
 */
internal class SqsBatchPerformanceRuntimeAdapter(
    private val workerCount: Int = 1,
) {

    enum class RuntimePath {
        SINGLE,
        BATCH,
    }

    data class Sample(
        val path: RuntimePath,
        val batchSize: Int,
        val micrometer: Boolean,
        val elapsedNanos: Long,
        val allocatedBytes: Long,
        val deleteCalls: Int,
        val deleteBatchCalls: Int,
        val visibilityBatchCalls: Int,
        val workerIds: List<Long>,
    )

    @Suppress("LongMethod", "TooGenericExceptionCaught")
    suspend fun run(
        path: RuntimePath,
        batchSize: Int,
        micrometer: Boolean,
    ): Sample {
        require(batchSize in 1..10)
        val workerIds = mutableListOf<Long>()
        val threadFactory = RecordingThreadFactory(workerIds)
        val executor = Executors.newFixedThreadPool(workerCount, threadFactory)
        val dispatcher = executor.asCoroutineDispatcher()
        val operations = RecordingOperations(messages(batchSize))
        val handler = PerformanceHandler(batchSize, path)
        val methodName = if (path == RuntimePath.SINGLE) "single" else "batch"
        val method = PerformanceHandler::class.java.declaredMethods.single { it.name == methodName }
        val invoker = SqsListenerMethodInvoker(handler, method, NoopSqsMessageConverter, dispatcher)
        val endpoint = SqsListenerEndpoint(
            id = "performance-$path-$batchSize",
            queue = QUEUE_URL,
            maxMessages = batchSize,
            waitTimeSeconds = 0,
            visibilityTimeoutSeconds = null,
            errorVisibilityTimeoutSeconds = null,
            autoStartup = true,
            phase = 0,
            concurrency = workerCount,
            stopTimeoutMillis = 5_000,
            retry = SqsProperties.Retry(),
            batch = path == RuntimePath.BATCH,
            acknowledgementMode = SqsAcknowledgementMode.ON_SUCCESS,
        )
        val meterRegistry: MeterRegistry? = if (micrometer) SimpleMeterRegistry() else null
        val interceptors = meterRegistry?.let { listOf<SqsListenerInterceptor>(MicrometerSqsListenerInterceptor(it)) }
            ?: emptyList()
        val container = SqsMessageListenerContainer(
            endpoint = endpoint,
            operations = operations,
            invoker = invoker,
            interceptors = interceptors,
            dispatcher = dispatcher,
        )
        withContext(dispatcher) { Unit }
        val allocationStart = allocatedBytes(workerIds)
        val startedAt = System.nanoTime()
        var allocated = 0L
        try {
            container.start()
            try {
                withTimeout(5_000) { handler.completed.await() }
            } catch (e: Throwable) {
                throw IllegalStateException(
                    "performance handler did not complete: path=$path batchSize=$batchSize " +
                        "invocations=${handler.invocationCount}",
                    e,
                )
            }
            val stopped = CompletableDeferred<Unit>()
            container.stop { stopped.complete(Unit) }
            withTimeout(5_000) { stopped.await() }
            allocated = (allocatedBytes(workerIds) - allocationStart).coerceAtLeast(0L)
        } finally {
            dispatcher.close()
            executor.shutdownNow()
        }
        val elapsed = System.nanoTime() - startedAt
        return Sample(
            path = path,
            batchSize = batchSize,
            micrometer = micrometer,
            elapsedNanos = elapsed,
            allocatedBytes = allocated,
            deleteCalls = operations.deleteCalls.get(),
            deleteBatchCalls = operations.deleteBatchCalls.get(),
            visibilityBatchCalls = operations.visibilityBatchCalls.get(),
            workerIds = workerIds.distinct(),
        )
    }

    private fun allocatedBytes(workerIds: List<Long>): Long {
        val bean = ManagementFactory.getThreadMXBean() as? com.sun.management.ThreadMXBean
            ?: error("ThreadMXBean allocated memory is unavailable")
        require(bean.isThreadAllocatedMemorySupported) { "ThreadMXBean allocated memory is unavailable" }
        if (!bean.isThreadAllocatedMemoryEnabled) {
            bean.isThreadAllocatedMemoryEnabled = true
        }
        return bean.getThreadAllocatedBytes(workerIds.distinct().toLongArray()).sum().coerceAtLeast(0L)
    }

    private class RecordingThreadFactory(
        private val workerIds: MutableList<Long>,
    ) : ThreadFactory {
        private val sequence = AtomicInteger()

        override fun newThread(runnable: Runnable): Thread = Thread(runnable).also { thread ->
            thread.name = "sqs-batch-performance-${sequence.incrementAndGet()}"
            synchronized(workerIds) { workerIds += thread.threadId() }
        }
    }

    class PerformanceHandler(
        private val expectedInvocations: Int,
        private val path: RuntimePath,
    ) {
        private val invocations = AtomicInteger()
        val completed = CompletableDeferred<Unit>()
        val invocationCount: Int
            get() = invocations.get()

        @Suppress("UNUSED_PARAMETER")
        suspend fun single(message: SqsReceivedMessage) {
            if (invocations.incrementAndGet() >= expectedInvocations) {
                completed.complete(Unit)
            }
        }

        @Suppress("UNUSED_PARAMETER")
        suspend fun batch(messages: List<SqsReceivedMessage>) {
            check(path == RuntimePath.BATCH)
            invocations.incrementAndGet()
            completed.complete(Unit)
        }
    }

    private class RecordingOperations(
        private val batch: List<SqsReceivedMessage>,
    ) : SqsOperations {
        private val receiveCalls = AtomicInteger()
        val deleteCalls = AtomicInteger()
        val deleteBatchCalls = AtomicInteger()
        val visibilityBatchCalls = AtomicInteger()

        override suspend fun getQueueUrl(queueName: String): String = QUEUE_URL

        override suspend fun createQueue(
            queueName: String,
            attributes: Map<QueueAttributeName, String>,
        ): String = QUEUE_URL

        override suspend fun createConfiguredQueue(queueName: String): String = QUEUE_URL

        override suspend fun send(queueUrl: String, body: String, delaySeconds: Int?): SendMessageResponse =
            SendMessageResponse.builder().messageId("performance").build()

        override suspend fun receive(
            queueUrl: String,
            maxMessages: Int,
            waitTimeSeconds: Int,
            visibilityTimeoutSeconds: Int?,
        ): List<SqsReceivedMessage> {
            if (receiveCalls.incrementAndGet() == 1) return batch
            kotlinx.coroutines.awaitCancellation()
        }

        override suspend fun delete(queueUrl: String, receiptHandle: String): DeleteMessageResponse {
            deleteCalls.incrementAndGet()
            return DeleteMessageResponse.builder().build()
        }

        override suspend fun deleteBatch(
            queueUrl: String,
            receiptHandles: Collection<String>,
        ): SqsBatchDeleteResult {
            deleteBatchCalls.incrementAndGet()
            return SqsBatchDeleteResult(
                successfulEntryIds = receiptHandles.indices.map { "entry-$it" },
                failed = emptyList(),
            )
        }

        override suspend fun changeVisibility(
            queueUrl: String,
            receiptHandle: String,
            timeoutSeconds: Int,
        ): ChangeMessageVisibilityResponse = ChangeMessageVisibilityResponse.builder().build()

        override suspend fun changeVisibilityBatch(
            queueUrl: String,
            requests: Collection<SqsChangeVisibilityRequest>,
        ): SqsBatchVisibilityResult {
            visibilityBatchCalls.incrementAndGet()
            return SqsBatchVisibilityResult(requests.map { it.messageId }, emptyList())
        }

        override fun receiveFlow(
            queueUrl: String,
            maxMessages: Int,
            waitTimeSeconds: Int,
            visibilityTimeoutSeconds: Int?,
        ): Flow<SqsReceivedMessage> = emptyFlow()
    }

    private fun messages(size: Int): List<SqsReceivedMessage> = (0 until size).map { index ->
        SqsReceivedMessage(
            queueUrl = QUEUE_URL,
            message = software.amazon.awssdk.services.sqs.model.Message.builder()
                .messageId("performance-$index")
                .receiptHandle("performance-receipt-$index")
                .body("payload")
                .build(),
        )
    }

    companion object {
        const val QUEUE_URL: String = "https://sqs.local/performance"
    }
}
