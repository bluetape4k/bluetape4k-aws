package io.bluetape4k.aws.spring.sqs

import io.bluetape4k.logging.KLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import software.amazon.awssdk.services.sqs.model.QueueDoesNotExistException
import org.springframework.context.SmartLifecycle
import java.time.Duration
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * 하나의 `@SqsListener` 엔드포인트를 실행하는 SQS 메시지 리스너 컨테이너.
 */
@Suppress(
    "TooManyFunctions",
    "TooGenericExceptionCaught",
    "ThrowsCount",
    "LargeClass",
)
class SqsMessageListenerContainer internal constructor(
    private val endpoint: SqsListenerEndpoint,
    private val operations: SqsOperations,
    private val invoker: SqsListenerMethodInvoker,
    private val interceptors: List<SqsListenerInterceptor>,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
): SmartLifecycle {

    companion object : KLogging()

    private class ListenerGeneration(
        val id: Long,
        val scope: CoroutineScope,
        maxInFlight: Int,
        val pollerJobs: CopyOnWriteArrayList<Job> = CopyOnWriteArrayList(),
        val handlerJobs: MutableSet<Job> = ConcurrentHashMap.newKeySet(),
        val inFlight: Semaphore = Semaphore(maxInFlight),
        val groupDispatchOrder: SqsGroupDispatchOrder = SqsGroupDispatchOrder(),
    )

    private data class SqsGroupDispatchTicket(
        val messageGroupId: String,
        val predecessor: CompletableDeferred<Unit>?,
        val completion: CompletableDeferred<Unit>,
    )

    private class SqsGroupDispatchOrder {
        private val tails = ConcurrentHashMap<String, CompletableDeferred<Unit>>()

        fun register(messageGroupId: String): SqsGroupDispatchTicket {
            val completion = CompletableDeferred<Unit>()
            return SqsGroupDispatchTicket(
                messageGroupId = messageGroupId,
                predecessor = tails.put(messageGroupId, completion),
                completion = completion,
            )
        }

        fun complete(ticket: SqsGroupDispatchTicket) {
            ticket.completion.complete(Unit)
            tails.remove(ticket.messageGroupId, ticket.completion)
        }

        fun clear() {
            tails.clear()
        }
    }

    private enum class LifecycleState {
        STOPPED,
        RUNNING,
        STOPPING_RECEIVE,
        DRAINING,
    }

    private val generation = AtomicReference<ListenerGeneration?>()
    private val lifecycleState = AtomicReference(LifecycleState.STOPPED)
    private val generationSequence = AtomicLong()
    private val lifecycleLock = Any()
    private val queueAttributesResolver = DefaultSqsQueueAttributesResolver(
        operations = operations,
        cacheTtl = endpoint.queueAttributeCacheTtl,
    )
    private var resolvedQueueUrl: String? = null

    private val admissionLimit: Int = when (endpoint.backPressureMode) {
        SqsBackPressureMode.FIXED -> endpoint.maxInFlight
        SqsBackPressureMode.AUTO -> maxOf(endpoint.maxInFlight, endpoint.maxMessages * endpoint.concurrency)
    }

    override fun start() {
        val current: ListenerGeneration
        synchronized(lifecycleLock) {
            when (lifecycleState.get()) {
                LifecycleState.STOPPING_RECEIVE,
                LifecycleState.DRAINING,
                -> throw IllegalStateException("listener is stopping")
                LifecycleState.RUNNING -> return
                LifecycleState.STOPPED -> {
                    current = ListenerGeneration(
                        id = generationSequence.incrementAndGet(),
                        scope = CoroutineScope(SupervisorJob() + dispatcher),
                        maxInFlight = admissionLimit,
                    )
                    generation.set(current)
                    lifecycleState.set(LifecycleState.RUNNING)
                }
            }
        }

        repeat(endpoint.concurrency) { pollerId ->
            current.pollerJobs += current.scope.launch {
                pollLoop(current, pollerId)
            }
        }
    }

    override fun stop() {
        stop(Runnable {})
    }

    override fun stop(callback: Runnable) {
        val current: ListenerGeneration
        synchronized(lifecycleLock) {
            if (!lifecycleState.compareAndSet(LifecycleState.RUNNING, LifecycleState.STOPPING_RECEIVE)) {
                callback.run()
                return
            }
            current = requireNotNull(generation.getAndSet(null))
        }

        CoroutineScope(dispatcher).launch {
            try {
                current.pollerJobs.forEach { it.cancel() }
                current.pollerJobs.joinAll()

                lifecycleState.set(LifecycleState.DRAINING)
                val drained = withTimeoutOrNull(endpoint.stopTimeoutMillis) {
                    while (current.handlerJobs.isNotEmpty()) {
                        current.handlerJobs.toList().joinAll()
                    }
                } != null
                if (!drained) {
                    current.handlerJobs.toList().forEach { it.cancel() }
                }
                current.scope.cancel()
                current.groupDispatchOrder.clear()
            } finally {
                synchronized(lifecycleLock) {
                    if (generation.get() === current) {
                        generation.set(null)
                    }
                    lifecycleState.set(LifecycleState.STOPPED)
                }
                callback.run()
            }
        }
    }

    override fun isRunning(): Boolean = lifecycleState.get() == LifecycleState.RUNNING

    override fun isAutoStartup(): Boolean = endpoint.autoStartup

    override fun getPhase(): Int = endpoint.phase

    @Suppress("LoopWithTooManyJumpStatements")
    private suspend fun pollLoop(current: ListenerGeneration, pollerId: Int) {
        var batchSequence = 0L
        var receiveAttempt = 1

        while (generation.get() === current) {
            val queueUrl = resolveQueueUrlForPoll(current, receiveAttempt)
            if (queueUrl == null) {
                receiveAttempt++
                continue
            }
            awaitReceiveCapacity(current)
            val correlation = SqsListenerBatchCorrelation(
                generation = current.id,
                pollerId = pollerId,
                batchSequence = ++batchSequence,
            )
            val messages = receiveMessagesForPoll(current, queueUrl, correlation, receiveAttempt)
            if (messages == null) {
                receiveAttempt++
                continue
            }
            receiveAttempt = 1
            if (generation.get() !== current) {
                return
            }
            dispatchMessages(current, queueUrl, messages, correlation)
        }
    }

    private suspend fun awaitReceiveCapacity(current: ListenerGeneration) {
        val requiredPermits = if (endpoint.batch) {
            endpoint.maxMessages.coerceAtMost(admissionLimit)
        } else {
            1
        }
        while (current.inFlight.availablePermits < requiredPermits) {
            current.ensureActiveOperation()
            delay(1)
        }
    }

    private suspend fun resolveQueueUrlForPoll(
        current: ListenerGeneration,
        receiveAttempt: Int,
    ): String? = try {
        resolveQueueUrl()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Error) {
        failGeneration(current)
        throw e
    } catch (e: QueueDoesNotExistException) {
        when (endpoint.queueNotFoundStrategy) {
            SqsQueueNotFoundStrategy.FAIL_FAST -> {
                failGeneration(current)
                throw e
            }
            SqsQueueNotFoundStrategy.IGNORE -> {
                log.info("SQS queue does not exist; stopping listener: listenerId=${endpoint.id}")
                failGeneration(current)
                null
            }
            SqsQueueNotFoundStrategy.CREATE -> {
                require(!endpoint.queue.startsWith("http://") && !endpoint.queue.startsWith("https://")) {
                    "CREATE queueNotFoundStrategy requires a queue name, not a queue URL."
                }
                try {
                    operations.createConfiguredQueue(endpoint.queue).also { resolvedQueueUrl = it }
                } catch (createFailure: CancellationException) {
                    throw createFailure
                } catch (createFailure: Error) {
                    failGeneration(current)
                    throw createFailure
                } catch (createFailure: Throwable) {
                    log.warn(
                        "SQS queue creation failed: listenerId=${endpoint.id}, queue=${endpoint.queue}",
                        createFailure,
                    )
                    delay(endpoint.retry.nextDelay(receiveAttempt))
                    null
                }
            }
        }
    } catch (e: Throwable) {
        log.warn("SQS queue URL resolution failed: listenerId=${endpoint.id}, queue=${endpoint.queue}", e)
        delay(endpoint.retry.nextDelay(receiveAttempt))
        null
    }

    private suspend fun receiveMessagesForPoll(
        current: ListenerGeneration,
        queueUrl: String,
        correlation: SqsListenerBatchCorrelation,
        receiveAttempt: Int,
    ): List<SqsReceivedMessage>? {
        var observationStarted = false
        return try {
            observationStarted = true
            interceptors.forEach { it.beforeReceive(endpoint.id, queueUrl, correlation) }
            val received = operations.receive(
                queueUrl = queueUrl,
                maxMessages = endpoint.maxMessages,
                waitTimeSeconds = endpoint.waitTimeSeconds,
                visibilityTimeoutSeconds = endpoint.visibilityTimeoutSeconds,
            )
            interceptors.forEach { it.afterReceive(endpoint.id, queueUrl, received, null, correlation) }
            received
        } catch (e: CancellationException) {
            if (observationStarted) {
                runCancellationCleanup(e) {
                    interceptors.forEach { it.afterReceive(endpoint.id, queueUrl, emptyList(), e, correlation) }
                }
            }
            throw e
        } catch (e: Error) {
            interceptors.forEach { it.afterReceive(endpoint.id, queueUrl, emptyList(), e, correlation) }
            failGeneration(current)
            throw e
        } catch (e: Throwable) {
            interceptors.forEach { it.afterReceive(endpoint.id, queueUrl, emptyList(), e, correlation) }
            log.warn("SQS receive failed: listenerId=${endpoint.id}, queueUrl=$queueUrl", e)
            delay(endpoint.retry.nextDelay(receiveAttempt))
            null
        }
    }

    private suspend fun dispatchMessages(
        current: ListenerGeneration,
        queueUrl: String,
        messages: List<SqsReceivedMessage>,
        correlation: SqsListenerBatchCorrelation,
    ) {
        try {
            if (endpoint.batch) {
                launchHandler(current, permitCount = messages.size.coerceAtMost(admissionLimit)) {
                    handleBatch(queueUrl, messages, correlation, current)
                }
            } else {
                messages.forEach { message ->
                    if (generation.get() !== current) {
                        return
                    }
                    launchHandler(current, message.messageGroupId) {
                        handle(queueUrl, message, current)
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Error) {
            failGeneration(current)
            throw e
        }
    }

    private suspend fun launchHandler(
        current: ListenerGeneration,
        messageGroupId: String? = null,
        permitCount: Int = 1,
        block: suspend () -> Unit,
    ) {
        current.ensureActiveOperation()
        val permits = permitCount.coerceIn(1, admissionLimit)
        var acquired = 0
        try {
            repeat(permits) {
                current.inFlight.acquire()
                acquired++
            }
        } catch (e: Throwable) {
            repeat(acquired) { current.inFlight.release() }
            throw e
        }
        val groupDispatchTicket = if (
            endpoint.fifoBatchGroupingStrategy == SqsFifoBatchGroupingStrategy.GROUP_BY_MESSAGE_GROUP_ID
        ) {
            messageGroupId?.takeIf(String::isNotBlank)?.let(current.groupDispatchOrder::register)
        } else {
            null
        }
        val handlerJob = try {
            current.scope.launch(start = CoroutineStart.LAZY) {
                try {
                    if (groupDispatchTicket == null) {
                        block()
                    } else {
                        withContext(NonCancellable) {
                            groupDispatchTicket.predecessor?.await()
                        }
                        current.ensureActiveOperation()
                        block()
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Error) {
                    log.error("SQS listener handler terminated with an error: listenerId=${endpoint.id}", e)
                } finally {
                    groupDispatchTicket?.let(current.groupDispatchOrder::complete)
                    repeat(permits) { current.inFlight.release() }
                }
            }
        } catch (e: Throwable) {
            groupDispatchTicket?.let(current.groupDispatchOrder::complete)
            repeat(permits) { current.inFlight.release() }
            throw e
        }
        current.handlerJobs += handlerJob
        handlerJob.invokeOnCompletion { current.handlerJobs -= handlerJob }
        handlerJob.start()
    }

    @Suppress("CyclomaticComplexMethod")
    private suspend fun handle(
        queueUrl: String,
        message: SqsReceivedMessage,
        generation: ListenerGeneration,
    ) {
        var heartbeatAcknowledgement: HeartbeatAwareSqsAcknowledgement? = null
        withVisibilityHeartbeat(
            generation = generation,
            target = "single",
            shouldContinue = { heartbeatAcknowledgement?.completed != true },
            operation = { timeoutSeconds ->
                heartbeatAcknowledgement?.heartbeat(timeoutSeconds) ?: Unit
            },
        ) {
            var attempt = 1
            while (attempt <= endpoint.retry.maxAttempts) {
                val context = SqsListenerInvocationContext(endpoint.id, queueUrl, message, attempt)
                val acknowledgement = DefaultSqsAcknowledgement(
                    context = context,
                    operations = operations,
                    interceptors = interceptors,
                    operationGuard = { generation.ensureActiveOperation() },
                )
                val currentAcknowledgement = HeartbeatAwareSqsAcknowledgement(acknowledgement)
                heartbeatAcknowledgement = currentAcknowledgement
                var observationStarted = false
                var observationCompleted = false
                try {
                    observationStarted = true
                    interceptors.forEach { it.beforeHandle(context) }
                    invoker.invoke(message, currentAcknowledgement)
                    interceptors.forEach { it.afterHandle(context, null) }
                    observationCompleted = true
                    if (!invoker.manualAcknowledgement) {
                        currentAcknowledgement.acknowledge()
                    }
                    return@withVisibilityHeartbeat
                } catch (e: CancellationException) {
                    if (observationStarted && !observationCompleted) {
                        runCancellationCleanup(e) {
                            interceptors.forEach { it.afterHandle(context, e) }
                        }
                    }
                    throw e
                } catch (e: Error) {
                    failGeneration(generation)
                    throw e
                } catch (e: Throwable) {
                    interceptors.forEach { it.afterHandle(context, e) }
                    if (acknowledgement.completed) {
                        return@withVisibilityHeartbeat
                    }
                    if (attempt >= endpoint.retry.maxAttempts) {
                        handleFailure(queueUrl, message, acknowledgement, e)
                        return@withVisibilityHeartbeat
                    }
                    delay(endpoint.retry.nextDelay(attempt))
                    attempt++
                }
            }
        }
    }

    private suspend fun runCancellationCleanup(
        cancellation: CancellationException,
        cleanup: suspend () -> Unit,
    ) {
        try {
            withContext(NonCancellable) {
                cleanup()
            }
        } catch (cleanupFailure: Throwable) {
            if (cleanupFailure !== cancellation) {
                cancellation.addSuppressed(cleanupFailure)
            }
        }
    }

    private suspend fun handleBatch(
        queueUrl: String,
        messages: List<SqsReceivedMessage>,
        correlation: SqsListenerBatchCorrelation,
        generation: ListenerGeneration,
    ) {
        if (messages.isEmpty()) {
            return
        }
        val acknowledgement = DefaultSqsBatchAcknowledgement(
            listenerId = endpoint.id,
            queueUrl = queueUrl,
            messages = messages,
            operations = operations,
            interceptors = interceptors,
            attempt = 1,
            correlation = correlation,
            operationGuard = { generation.ensureActiveOperation() },
        )
        val manual = endpoint.acknowledgementMode == SqsAcknowledgementMode.MANUAL
        val context = SqsListenerInvocationContext(endpoint.id, queueUrl, messages.first(), 1)
        withVisibilityHeartbeat(
            generation = generation,
            target = "batchSize=${messages.size}",
            shouldContinue = { !acknowledgement.completed },
            operation = { timeoutSeconds ->
                changeBatchVisibilityForHeartbeat(queueUrl, acknowledgement, timeoutSeconds)
            },
        ) {
            handleBatchAttempts(queueUrl, acknowledgement, manual, context, correlation, generation)
        }
    }

    private suspend fun changeBatchVisibilityForHeartbeat(
        queueUrl: String,
        acknowledgement: DefaultSqsBatchAcknowledgement,
        timeoutSeconds: Int,
    ) {
        val pending = acknowledgement.pending
        if (pending.isEmpty()) return
        val result = withContext(Dispatchers.IO) {
            acknowledgement.changeVisibility(pending, timeoutSeconds)
        }
        if (result.failed.isNotEmpty()) {
            log.warn(
                "SQS batch visibility heartbeat partially failed: " +
                    "listenerId=${endpoint.id}, queueUrl=$queueUrl, " +
                    "batchSize=${pending.size}, failed=${result.failed.size}",
            )
        }
    }

    @Suppress("CyclomaticComplexMethod", "ReturnCount")
    private suspend fun handleBatchAttempts(
        queueUrl: String,
        acknowledgement: DefaultSqsBatchAcknowledgement,
        manual: Boolean,
        context: SqsListenerInvocationContext,
        correlation: SqsListenerBatchCorrelation,
        generation: ListenerGeneration,
    ) {
        var attempt = 1
        while (attempt <= endpoint.retry.maxAttempts) {
            acknowledgement.updateAttempt(attempt)
            val pending = acknowledgement.pending
            if (pending.isEmpty()) {
                return
            }
            val attemptContext = context.copy(attempt = attempt)
            try {
                invokeBatchHandler(pending, acknowledgement, manual, attemptContext, correlation)
                if (manual) {
                    return
                }

                val result = acknowledgement.acknowledge(pending)
                if (result.status == SqsBatchAcknowledgementStatus.SUCCESS || acknowledgement.completed) {
                    return
                }
                if (attempt >= endpoint.retry.maxAttempts) {
                    handleBatchFailure(queueUrl, acknowledgement)
                    return
                }
                interceptors.forEach {
                    it.onBatchRetry(attemptContext, correlation, pending.size, attempt + 1, null)
                }
            } catch (e: CancellationException) {
                interceptors.forEach { it.onBatchCancellation(attemptContext, correlation, pending.size) }
                throw e
            } catch (e: Error) {
                failGeneration(generation)
                throw e
            } catch (e: Throwable) {
                if (acknowledgement.completed) {
                    return
                }
                if (attempt >= endpoint.retry.maxAttempts) {
                    handleBatchFailure(queueUrl, acknowledgement)
                    return
                }
                interceptors.forEach {
                    it.onBatchRetry(attemptContext, correlation, pending.size, attempt + 1, e)
                }
            }
            delay(endpoint.retry.nextDelay(attempt))
            attempt++
        }
    }

    private suspend fun <T> withVisibilityHeartbeat(
        generation: ListenerGeneration,
        target: String,
        shouldContinue: () -> Boolean = { true },
        operation: suspend (timeoutSeconds: Int) -> Unit,
        block: suspend () -> T,
    ): T {
        val intervalSeconds = endpoint.messageVisibilityHeartbeatIntervalSeconds
        val heartbeatSeconds = endpoint.messageVisibilityHeartbeatSeconds
        if (intervalSeconds == null || heartbeatSeconds == null) {
            return block()
        }

        return coroutineScope {
            val heartbeatJob = launch(CoroutineName("sqs-visibility-heartbeat")) {
                while (isActive) {
                    try {
                        delay(Duration.ofSeconds(intervalSeconds.toLong()).toMillis())
                        if (!isActive) {
                            return@launch
                        }
                        if (!shouldContinue()) {
                            return@launch
                        }
                        generation.ensureActiveOperation()
                        operation(heartbeatSeconds)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        log.warn(
                            "SQS visibility heartbeat failed: listenerId=${endpoint.id}, target=$target",
                            e,
                        )
                    }
                }
            }
            try {
                block()
            } finally {
                withContext(NonCancellable) {
                    heartbeatJob.cancelAndJoin()
                }
            }
        }
    }

    private suspend fun invokeBatchHandler(
        pending: List<SqsReceivedMessage>,
        acknowledgement: DefaultSqsBatchAcknowledgement,
        manual: Boolean,
        context: SqsListenerInvocationContext,
        correlation: SqsListenerBatchCorrelation,
    ) {
        interceptors.forEach { it.beforeBatchHandle(context, correlation, pending.size) }
        var handlerFailure: Throwable? = null
        try {
            invoker.invokeBatch(pending, acknowledgement.takeIf { manual })
        } catch (e: Throwable) {
            handlerFailure = e
            throw e
        } finally {
            interceptors.forEach { it.afterBatchHandle(context, handlerFailure, correlation, pending.size) }
        }
    }

    private suspend fun handleBatchFailure(
        queueUrl: String,
        acknowledgement: DefaultSqsBatchAcknowledgement,
    ) {
        val pending = acknowledgement.pending
        if (pending.isEmpty()) {
            return
        }
        endpoint.errorVisibilityTimeoutSeconds?.let { timeoutSeconds ->
            try {
                acknowledgement.changeVisibility(pending, timeoutSeconds)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Error) {
                throw e
            } catch (e: Throwable) {
                log.warn(
                    "SQS batch changeVisibility failed: listenerId=${endpoint.id}, queueUrl=$queueUrl, " +
                        "batchSize=${pending.size}",
                    e,
                )
            }
        }
    }

    private suspend fun handleFailure(
        queueUrl: String,
        message: SqsReceivedMessage,
        acknowledgement: SqsAcknowledgement,
        error: Throwable,
    ) {
        log.warn(
            "SQS message handling failed: listenerId=${endpoint.id}, queueUrl=$queueUrl, messageId=${message.messageId}",
            error,
        )
        endpoint.errorVisibilityTimeoutSeconds?.let {
            try {
                if (!acknowledgement.completed) {
                    acknowledgement.changeVisibility(it)
                }
            } catch (ve: CancellationException) {
                throw ve
            } catch (ve: Error) {
                throw ve
            } catch (ve: Throwable) {
                log.warn(
                    "SQS changeVisibility failed: listenerId=${endpoint.id}, queueUrl=$queueUrl, messageId=${message.messageId}",
                    ve,
                )
            }
        }
    }

    private suspend fun resolveQueueUrl(): String {
        resolvedQueueUrl?.let { return it }

        val queueUrl = when {
            endpoint.queue.startsWith("http://") || endpoint.queue.startsWith("https://") -> endpoint.queue
            else -> operations.getQueueUrl(endpoint.queue)
        }
        if (endpoint.queueAttributeNames.isNotEmpty()) {
            queueAttributesResolver.resolve(queueUrl, endpoint.queueAttributeNames)
        }
        resolvedQueueUrl = queueUrl
        return queueUrl
    }

    private fun ListenerGeneration.ensureActiveOperation() {
        if (generation.get() !== this || lifecycleState.get() != LifecycleState.RUNNING) {
            throw CancellationException("SQS listener generation is stopping")
        }
    }

    private fun failGeneration(current: ListenerGeneration) {
        synchronized(lifecycleLock) {
            if (generation.get() !== current) {
                return
            }
            generation.set(null)
            lifecycleState.set(LifecycleState.STOPPED)
            current.scope.cancel()
        }
    }
}

private class HeartbeatAwareSqsAcknowledgement(
    private val delegate: SqsAcknowledgement,
) : SqsAcknowledgement {

    private val operationMutex = Mutex()

    override val completed: Boolean
        get() = delegate.completed

    override suspend fun acknowledge() {
        operationMutex.withLock {
            delegate.acknowledge()
        }
    }

    override suspend fun nack(timeoutSeconds: Int) {
        operationMutex.withLock {
            delegate.nack(timeoutSeconds)
        }
    }

    override suspend fun changeVisibility(timeoutSeconds: Int) {
        operationMutex.withLock {
            delegate.changeVisibility(timeoutSeconds)
        }
    }

    suspend fun heartbeat(timeoutSeconds: Int) {
        operationMutex.withLock {
            if (!delegate.completed) {
                withContext(Dispatchers.IO) {
                    delegate.changeVisibility(timeoutSeconds)
                }
            }
        }
    }
}

private fun SqsProperties.Retry.nextDelay(failedAttempt: Int): Long {
    if (initialBackoff.isZero) {
        return 0L
    }
    val exponential = initialBackoff.toMillis() * Math.pow(multiplier, (failedAttempt - 1).toDouble())
    val capped = maxBackoff?.let { minOf(exponential, it.toMillis().toDouble()) } ?: exponential
    val jittered = if (jitterRatio == 0.0) {
        capped
    } else {
        val delta = capped * jitterRatio
        ThreadLocalRandom.current().nextDouble(capped - delta, capped + delta)
    }
    return Duration.ofMillis(jittered.toLong().coerceAtLeast(0L)).toMillis()
}
