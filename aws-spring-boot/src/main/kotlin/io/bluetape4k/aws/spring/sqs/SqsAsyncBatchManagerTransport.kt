package io.bluetape4k.aws.spring.sqs

import software.amazon.awssdk.services.sqs.SqsAsyncClient
import software.amazon.awssdk.services.sqs.batchmanager.BatchOverrideConfiguration
import software.amazon.awssdk.services.sqs.batchmanager.SqsAsyncBatchManager
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest
import software.amazon.awssdk.services.sqs.model.SendMessageRequest
import java.util.concurrent.Future
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** AWS SDK [SqsAsyncBatchManager]를 공통 batch transport 경계에 연결합니다. */
internal class SqsAsyncBatchManagerTransport(
    private val manager: SqsAsyncBatchManager,
) : SqsBatchTransport {

    override fun send(entry: SqsBatchSendEntry) = submitBatchOutcome(
        entryId = entry.entryId,
        submit = { manager.sendMessage(entry.request.toSdkRequest()) },
        success = { response ->
            SqsBatchOutcome.SendSuccess(entry.entryId, response.messageId(), response.sequenceNumber())
        },
    )

    override fun delete(entry: SqsBatchDeleteEntry) = submitBatchOutcome(
        entryId = entry.entryId,
        submit = {
            manager.deleteMessage(
                DeleteMessageRequest.builder()
                    .queueUrl(entry.queueUrl)
                    .receiptHandle(entry.receiptHandle)
                    .build(),
            )
        },
        success = { SqsBatchOutcome.DeleteSuccess(entry.entryId) },
    )
}

/** batch transport와 manager/executor lifecycle handle을 함께 보관합니다. */
internal class SqsBatchTransportResources(
    val transport: SqsBatchTransport,
    private val manager: AutoCloseable,
    private val executor: ScheduledThreadPoolExecutor,
) {
    fun closeManager() {
        manager.close()
    }

    fun shutdownExecutor() {
        executor.shutdown()
    }

    fun shutdownExecutorNow() {
        executor.shutdownNow()
    }

    fun awaitExecutorTermination(timeout: Long, unit: TimeUnit): Boolean =
        executor.awaitTermination(timeout, unit)
}

/** batch mode 전용 SDK manager와 daemon scheduler를 원자적으로 조립합니다. */
internal object SqsBatchTransportFactory {

    fun create(
        properties: SqsBatchProperties,
        client: SqsAsyncClient,
    ): SqsBatchTransportResources = create(
        properties = properties,
        client = client,
        schedulerFactory = ::newSqsBatchScheduler,
        managerFactory = ::newSqsAsyncBatchManager,
        transportFactory = ::SqsAsyncBatchManagerTransport,
    )

    @Suppress("LongParameterList", "ThrowsCount", "TooGenericExceptionCaught")
    internal fun create(
        properties: SqsBatchProperties,
        client: SqsAsyncClient,
        schedulerFactory: (Int) -> ScheduledThreadPoolExecutor,
        managerFactory: (
            SqsBatchProperties,
            SqsAsyncClient,
            ScheduledThreadPoolExecutor,
        ) -> SqsAsyncBatchManager,
        transportFactory: (SqsAsyncBatchManager) -> SqsBatchTransport,
        exceptionFactory: (
            SqsBatchStartupComponent,
            Collection<SqsBatchCleanupComponent>,
        ) -> SqsBatchStartupException = { component, cleanup ->
            SqsBatchStartupException(component, cleanup)
        },
    ): SqsBatchTransportResources {
        val executor = try {
            schedulerFactory(properties.schedulerThreads)
        } catch (_: Throwable) {
            throw exceptionFactory(SqsBatchStartupComponent.MANAGER, emptyList())
        }
        val manager = try {
            managerFactory(properties, client, executor)
        } catch (_: Throwable) {
            val cleanup = cleanupExecutor(executor, properties)
            throw exceptionFactory(SqsBatchStartupComponent.MANAGER, cleanup)
        }

        val transport = try {
            transportFactory(manager)
        } catch (_: Throwable) {
            val cleanup = buildList {
                try {
                    manager.close()
                } catch (_: Throwable) {
                    add(SqsBatchCleanupComponent.MANAGER)
                }
                addAll(cleanupExecutor(executor, properties))
            }
            throw exceptionFactory(SqsBatchStartupComponent.TRANSPORT, cleanup)
        }
        return SqsBatchTransportResources(transport, manager, executor)
    }
}

internal fun newSqsBatchScheduler(threadCount: Int): ScheduledThreadPoolExecutor =
    ScheduledThreadPoolExecutor(threadCount) { task ->
        Thread(task, "$SQS_BATCH_THREAD_PREFIX${SQS_BATCH_THREAD_SEQUENCE.incrementAndGet()}").apply {
            isDaemon = true
        }
    }.apply {
        removeOnCancelPolicy = true
        setContinueExistingPeriodicTasksAfterShutdownPolicy(false)
        setExecuteExistingDelayedTasksAfterShutdownPolicy(false)
        rejectedExecutionHandler = java.util.concurrent.RejectedExecutionHandler { task, _ ->
            (task as? Future<*>)?.cancel(false)
        }
    }

internal fun newSqsAsyncBatchManager(
    properties: SqsBatchProperties,
    client: SqsAsyncClient,
    executor: ScheduledThreadPoolExecutor,
): SqsAsyncBatchManager = SqsAsyncBatchManager.builder()
    .client(client)
    .scheduledExecutor(executor)
    .overrideConfiguration(
        BatchOverrideConfiguration.builder()
            .maxBatchSize(properties.maxBatchSize)
            .sendRequestFrequency(properties.flushInterval)
            .build(),
    )
    .build()

private fun SqsSendRequest.toSdkRequest(): SendMessageRequest = SendMessageRequest.builder()
    .queueUrl(queueUrl)
    .messageBody(body)
    .apply {
        delaySeconds?.let(::delaySeconds)
        messageGroupId?.let(::messageGroupId)
        messageDeduplicationId?.let(::messageDeduplicationId)
        if (messageAttributes.isNotEmpty()) {
            messageAttributes(this@toSdkRequest.messageAttributes)
        }
    }
    .build()

@Suppress("TooGenericExceptionCaught")
private fun cleanupExecutor(
    executor: ScheduledThreadPoolExecutor,
    properties: SqsBatchProperties,
): List<SqsBatchCleanupComponent> = try {
    executor.shutdownNow()
    if (executor.awaitTermination(properties.shutdownTimeout.toMillis(), TimeUnit.MILLISECONDS)) {
        emptyList()
    } else {
        listOf(SqsBatchCleanupComponent.EXECUTOR)
    }
} catch (_: InterruptedException) {
    Thread.currentThread().interrupt()
    listOf(SqsBatchCleanupComponent.EXECUTOR)
} catch (_: Throwable) {
    listOf(SqsBatchCleanupComponent.EXECUTOR)
}

private const val SQS_BATCH_THREAD_PREFIX = "bluetape4k-sqs-batch-"
private val SQS_BATCH_THREAD_SEQUENCE = AtomicLong()
