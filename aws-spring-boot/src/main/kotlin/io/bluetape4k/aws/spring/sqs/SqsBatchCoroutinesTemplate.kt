package io.bluetape4k.aws.spring.sqs

import io.bluetape4k.logging.KLogging
import software.amazon.awssdk.services.sqs.SqsAsyncClient
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicLong

/**
 * SQS 자동 배치 연산과 소유 자원의 종료 수명 주기를 제공합니다.
 *
 * 취소와 종료 timeout은 이미 SDK manager가 수락한 메시지의 전달을 rollback하지 않습니다.
 * 이 template은 주입받은 [SqsAsyncClient]를 닫지 않습니다.
 */
@Suppress("TooManyFunctions")
class SqsBatchCoroutinesTemplate internal constructor(
    private val coordinator: SqsBatchCoordinator,
    private val resources: SqsBatchTransportResources?,
    private val properties: SqsBatchProperties,
    private val closeRuntime: SqsBatchCloseRuntime = DefaultSqsBatchCloseRuntime,
) : SqsBatchOperations, AutoCloseable {

    override suspend fun sendMany(
        entries: Collection<SqsBatchSendEntry>,
        failureStrategy: SendBatchFailureStrategy,
    ): SqsSendManyResult = coordinator.sendMany(entries, failureStrategy)

    override suspend fun deleteMany(entries: Collection<SqsBatchDeleteEntry>): SqsDeleteManyResult =
        coordinator.deleteMany(entries)

    override fun close() {
        val outcome = when (val claim = coordinator.beginClose()) {
            is SqsBatchCloseClaim.Owner -> closeOwner(claim)
            is SqsBatchCloseClaim.Observer -> claim.completion.join()
        }
        outcome.throwIfFailed()
    }

    @Suppress("TooGenericExceptionCaught")
    private fun closeOwner(claim: SqsBatchCloseClaim.Owner): SqsBatchCloseOutcome {
        val components = linkedSetOf<SqsBatchCleanupComponent>()
        var outcome: SqsBatchCloseOutcome? = null
        try {
            val deadlineNanos = closeRuntime.deadlineAfterOrNull(properties.shutdownTimeout.toNanos())
            var timedOut = deadlineNanos == null || !drainAccepted(claim.accepted, deadlineNanos)
            if (timedOut) {
                cancelAccepted(claim.accepted)
            }
            resources?.let { owned ->
                timedOut = closeManager(owned, deadlineNanos, components) || timedOut
                closeExecutor(owned, deadlineNanos, timedOut, components)
            }
            if (timedOut) {
                components += SqsBatchCleanupComponent.TIMEOUT
            }
        } catch (failure: Throwable) {
            if (failure is InterruptedException) {
                Thread.currentThread().interrupt()
            }
            components += SqsBatchCleanupComponent.TIMEOUT
            resources?.let { closeExecutor(it, null, true, components) }
        } finally {
            outcome = components.toCloseOutcome()
            coordinator.finishClose(checkNotNull(outcome))
        }
        return checkNotNull(outcome)
    }

    @Suppress("TooGenericExceptionCaught")
    private fun cancelAccepted(accepted: List<SqsAcceptedBatchEntry>) {
        accepted.forEach { entry ->
            try {
                entry.cancelIfIncomplete()
            } catch (failure: Throwable) {
                if (failure is InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
        }
    }

    private fun drainAccepted(
        accepted: List<SqsAcceptedBatchEntry>,
        deadlineNanos: Long,
    ): Boolean {
        var drained = true
        val iterator = accepted.iterator()
        while (drained && iterator.hasNext()) {
            val entry = iterator.next()
            val remaining = closeRuntime.remainingNanosOrNull(deadlineNanos)
            drained = remaining != null && remaining > 0L && awaitAccepted(entry, remaining)
        }
        return drained
    }

    private fun awaitAccepted(entry: SqsAcceptedBatchEntry, remainingNanos: Long): Boolean =
        try {
            closeRuntime.awaitCompletion(entry.completion, remainingNanos)
            true
        } catch (_: ExecutionException) {
            true
        } catch (_: TimeoutException) {
            false
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        } catch (_: Throwable) {
            false
        }

    private fun closeManager(
        owned: SqsBatchTransportResources,
        deadlineNanos: Long?,
        components: MutableSet<SqsBatchCleanupComponent>,
    ): Boolean {
        val completion = CompletableFuture<Boolean>()
        val cleanupThread = try {
            closeRuntime.newManagerCleanupThread(
                Runnable {
                    val succeeded = try {
                        owned.closeManager()
                        true
                    } catch (_: Throwable) {
                        false
                    }
                    completion.complete(succeeded)
                    if (!succeeded) {
                        log.warn("SQS batch manager cleanup failed (component=MANAGER).")
                    }
                },
            ).also(Thread::start)
        } catch (_: Throwable) {
            components += SqsBatchCleanupComponent.MANAGER
            null
        }

        var timedOut = false
        if (cleanupThread != null) {
            val remaining = deadlineNanos?.let(closeRuntime::remainingNanosOrNull)
            timedOut = if (remaining == null || remaining <= 0L) {
                cleanupThread.interrupt()
                true
            } else {
                awaitManagerCleanup(completion, cleanupThread, remaining, components)
            }
        }
        return timedOut
    }

    private fun awaitManagerCleanup(
        completion: CompletableFuture<Boolean>,
        cleanupThread: Thread,
        remainingNanos: Long,
        components: MutableSet<SqsBatchCleanupComponent>,
    ): Boolean = try {
        closeRuntime.awaitCompletion(completion, remainingNanos)
        if (!completion.getNow(false)) {
            components += SqsBatchCleanupComponent.MANAGER
        }
        false
    } catch (_: TimeoutException) {
        cleanupThread.interrupt()
        true
    } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
        cleanupThread.interrupt()
        true
    } catch (_: Throwable) {
        components += SqsBatchCleanupComponent.MANAGER
        false
    }

    private fun closeExecutor(
        owned: SqsBatchTransportResources,
        deadlineNanos: Long?,
        force: Boolean,
        components: MutableSet<SqsBatchCleanupComponent>,
    ) {
        var forced = force
        try {
            if (force) {
                owned.shutdownExecutorNow()
            } else {
                owned.shutdownExecutor()
            }
        } catch (_: Throwable) {
            components += SqsBatchCleanupComponent.EXECUTOR
            forced = true
            runCatching(owned::shutdownExecutorNow)
        }

        val remaining = deadlineNanos?.let(closeRuntime::remainingNanosOrNull)
        if (remaining == null || remaining <= 0L) {
            if (!forced) runCatching(owned::shutdownExecutorNow)
            components += SqsBatchCleanupComponent.TIMEOUT
            return
        }
        try {
            if (!closeRuntime.awaitTermination(owned.executorService(), remaining)) {
                if (!forced) runCatching(owned::shutdownExecutorNow)
                components += SqsBatchCleanupComponent.TIMEOUT
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            if (!forced) runCatching(owned::shutdownExecutorNow)
            components += SqsBatchCleanupComponent.TIMEOUT
        } catch (_: Throwable) {
            if (!forced) runCatching(owned::shutdownExecutorNow)
            components += SqsBatchCleanupComponent.EXECUTOR
        }
    }

    companion object : KLogging() {

        internal fun create(
            client: SqsAsyncClient,
            properties: SqsBatchProperties,
            closeRuntime: SqsBatchCloseRuntime = DefaultSqsBatchCloseRuntime,
            resourcesFactory: (SqsBatchProperties, SqsAsyncClient) -> SqsBatchTransportResources =
                SqsBatchTransportFactory::create,
            templateFactory: (
                SqsBatchCoordinator,
                SqsBatchTransportResources?,
                SqsBatchProperties,
                SqsBatchCloseRuntime,
            ) -> SqsBatchCoroutinesTemplate = ::SqsBatchCoroutinesTemplate,
            exceptionFactory: (
                SqsBatchStartupComponent,
                Collection<SqsBatchCleanupComponent>,
            ) -> SqsBatchStartupException = ::SqsBatchStartupException,
        ): SqsBatchCoroutinesTemplate {
            if (!properties.enabled) {
                return try {
                    templateFactory(
                        SqsBatchCoordinator(properties, DirectSqsBatchTransport(client)),
                        null,
                        properties,
                        closeRuntime,
                    )
                } catch (_: Throwable) {
                    throw exceptionFactory(SqsBatchStartupComponent.TEMPLATE, emptyList())
                }
            }

            val owned = resourcesFactory(properties, client)
            return try {
                templateFactory(
                    SqsBatchCoordinator(properties, owned.transport),
                    owned,
                    properties,
                    closeRuntime,
                )
            } catch (_: Throwable) {
                val cleanup = rollbackResources(owned, properties, closeRuntime)
                throw exceptionFactory(SqsBatchStartupComponent.TEMPLATE, cleanup)
            }
        }

        private fun rollbackResources(
            owned: SqsBatchTransportResources,
            properties: SqsBatchProperties,
            closeRuntime: SqsBatchCloseRuntime,
        ): List<SqsBatchCleanupComponent> {
            val components = linkedSetOf<SqsBatchCleanupComponent>()
            val deadline = closeRuntime.deadlineAfterOrNull(properties.shutdownTimeout.toNanos())
            if (deadline == null) {
                components += SqsBatchCleanupComponent.TIMEOUT
            }
            try {
                rollbackManager(owned, deadline, closeRuntime, components)
            } finally {
                rollbackExecutor(owned, deadline, closeRuntime, components)
            }
            return components.toList()
        }

        private fun rollbackManager(
            owned: SqsBatchTransportResources,
            deadlineNanos: Long?,
            closeRuntime: SqsBatchCloseRuntime,
            components: MutableSet<SqsBatchCleanupComponent>,
        ) {
            val managerCompletion = CompletableFuture<Boolean>()
            var cleanupThread: Thread? = null
            try {
                cleanupThread = closeRuntime.newManagerCleanupThread(
                    Runnable {
                        managerCompletion.complete(
                            try {
                                owned.closeManager()
                                true
                            } catch (_: Throwable) {
                                false
                            },
                        )
                    },
                ).also(Thread::start)
                val remaining = deadlineNanos?.let(closeRuntime::remainingNanosOrNull)
                if (remaining == null || remaining <= 0L) {
                    components += SqsBatchCleanupComponent.TIMEOUT
                } else {
                    closeRuntime.awaitCompletion(managerCompletion, remaining)
                    if (!managerCompletion.getNow(false)) {
                        components += SqsBatchCleanupComponent.MANAGER
                    }
                }
            } catch (_: TimeoutException) {
                components += SqsBatchCleanupComponent.TIMEOUT
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                components += SqsBatchCleanupComponent.TIMEOUT
            } catch (_: Throwable) {
                components += SqsBatchCleanupComponent.MANAGER
            } finally {
                if (!managerCompletion.isDone) cleanupThread?.interrupt()
            }
        }

        private fun rollbackExecutor(
            owned: SqsBatchTransportResources,
            deadlineNanos: Long?,
            closeRuntime: SqsBatchCloseRuntime,
            components: MutableSet<SqsBatchCleanupComponent>,
        ) {
            try {
                owned.shutdownExecutorNow()
            } catch (_: Throwable) {
                components += SqsBatchCleanupComponent.EXECUTOR
            }
            try {
                val remaining = deadlineNanos?.let(closeRuntime::remainingNanosOrNull)
                if (remaining == null || remaining <= 0L ||
                    !closeRuntime.awaitTermination(owned.executorService(), remaining)
                ) {
                    components += SqsBatchCleanupComponent.TIMEOUT
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                components += SqsBatchCleanupComponent.TIMEOUT
            } catch (_: Throwable) {
                components += SqsBatchCleanupComponent.EXECUTOR
            }
        }
    }
}

/** 종료 clock, blocking wait와 manager cleanup thread 생성을 격리한 internal runtime입니다. */
internal interface SqsBatchCloseRuntime {
    fun nanoTime(): Long

    @Throws(InterruptedException::class, TimeoutException::class, ExecutionException::class)
    fun awaitCompletion(future: CompletableFuture<*>, timeoutNanos: Long)

    fun awaitTermination(executor: ExecutorService, timeoutNanos: Long): Boolean

    fun newManagerCleanupThread(task: Runnable): Thread
}

private object DefaultSqsBatchCloseRuntime : SqsBatchCloseRuntime {
    override fun nanoTime(): Long = System.nanoTime()

    override fun awaitCompletion(future: CompletableFuture<*>, timeoutNanos: Long) {
        future.get(timeoutNanos, TimeUnit.NANOSECONDS)
    }

    override fun awaitTermination(executor: ExecutorService, timeoutNanos: Long): Boolean =
        executor.awaitTermination(timeoutNanos, TimeUnit.NANOSECONDS)

    override fun newManagerCleanupThread(task: Runnable): Thread =
        Thread(task, "$SQS_BATCH_CLEANUP_THREAD_PREFIX${SQS_BATCH_CLEANUP_THREAD_SEQUENCE.incrementAndGet()}").apply {
            isDaemon = true
        }
}

private fun SqsBatchCloseRuntime.deadlineAfter(timeoutNanos: Long): Long {
    val now = nanoTime()
    return if (timeoutNanos > 0L && now > Long.MAX_VALUE - timeoutNanos) {
        Long.MAX_VALUE
    } else {
        now + timeoutNanos
    }
}

@Suppress("TooGenericExceptionCaught")
private fun SqsBatchCloseRuntime.deadlineAfterOrNull(timeoutNanos: Long): Long? =
    try {
        deadlineAfter(timeoutNanos)
    } catch (failure: Throwable) {
        if (failure is InterruptedException) {
            Thread.currentThread().interrupt()
        }
        null
    }

@Suppress("TooGenericExceptionCaught")
private fun SqsBatchCloseRuntime.remainingNanosOrNull(deadlineNanos: Long): Long? =
    try {
        (deadlineNanos - nanoTime()).coerceAtLeast(0L)
    } catch (failure: Throwable) {
        if (failure is InterruptedException) {
            Thread.currentThread().interrupt()
        }
        null
    }

private fun Collection<SqsBatchCleanupComponent>.toCloseOutcome(): SqsBatchCloseOutcome =
    if (isEmpty()) {
        SqsBatchCloseOutcome.Success
    } else {
        SqsBatchCloseOutcome.Failure(SqsBatchCloseException(this))
    }

private fun SqsBatchCloseOutcome.throwIfFailed() {
    if (this is SqsBatchCloseOutcome.Failure) throw exception
}

private const val SQS_BATCH_CLEANUP_THREAD_PREFIX = "bluetape4k-sqs-batch-cleanup-"
private val SQS_BATCH_CLEANUP_THREAD_SEQUENCE = AtomicLong()
