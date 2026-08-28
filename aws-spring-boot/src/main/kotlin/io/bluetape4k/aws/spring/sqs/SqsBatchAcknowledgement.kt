package io.bluetape4k.aws.spring.sqs

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 하나의 ReceiveMessage 응답에 대한 전체·부분 acknowledgement API입니다.
 */
interface SqsBatchAcknowledgement {

    /** 현재 terminal 상태가 아닌 메시지의 방어적 snapshot입니다. */
    val pending: List<SqsReceivedMessage>

    /** 모든 메시지가 ACKED 또는 DEFERRED 상태인지 나타냅니다. */
    val completed: Boolean

    /** 현재 pending 메시지를 삭제합니다. */
    suspend fun acknowledge(): SqsBatchAcknowledgementResult

    /** 지정한 메시지만 삭제합니다. */
    suspend fun acknowledge(messages: Collection<SqsReceivedMessage>): SqsBatchAcknowledgementResult

    /** 지정한 메시지를 다시 보이게 합니다. */
    suspend fun nack(
        messages: Collection<SqsReceivedMessage> = pending,
        timeoutSeconds: Int = 0,
    ): SqsBatchAcknowledgementResult

    /** 지정한 메시지의 가시성만 변경하고 terminal 상태로 만들지 않습니다. */
    suspend fun changeVisibility(
        messages: Collection<SqsReceivedMessage> = pending,
        timeoutSeconds: Int,
    ): SqsBatchAcknowledgementResult
}

/**
 * 하나의 batch에 대한 acknowledgement 상태를 소유하는 내부 구현입니다.
 */
@Suppress("TooManyFunctions")
internal class DefaultSqsBatchAcknowledgement(
    private val listenerId: String,
    private val queueUrl: String,
    messages: List<SqsReceivedMessage>,
    private val operations: SqsOperations,
    private val interceptors: List<SqsListenerInterceptor>,
    private var attempt: Int = 1,
    private val correlation: SqsListenerBatchCorrelation = SqsListenerBatchCorrelation(0L, 0, 0L),
    private val operationGuard: () -> Unit = {},
    private val observationRuntime: SqsObservationRuntime? = null,
    private val observationQueueName: String? = null,
) : SqsBatchAcknowledgement {

    private enum class ItemState {
        PENDING,
        IN_FLIGHT,
        ACKED,
        DEFERRED,
    }

    private data class Outcome(
        val success: Boolean,
        val failure: SqsBatchAcknowledgementFailure? = null,
    )

    private class Item(
        val message: SqsReceivedMessage,
        val index: Int,
    ) {
        var state: ItemState = ItemState.PENDING
        var inFlight: CompletableDeferred<Unit>? = null
        val outcomes: MutableMap<SqsBatchAcknowledgementOperation, Outcome> = mutableMapOf()
    }

    private sealed interface Decision {
        data class Run(
            val requested: List<Item>,
            val items: List<Item>,
            val preSuccessIds: List<String>,
            val preFailures: List<SqsBatchAcknowledgementFailure>,
            val deferred: CompletableDeferred<Unit>,
        ) : Decision

        data class Wait(val deferreds: List<CompletableDeferred<Unit>>) : Decision
        data class Done(val result: SqsBatchAcknowledgementResult) : Decision
    }

    private val mutex = Mutex()
    private val snapshotLock = Any()
    private val items: List<Item>
    private val byReceiptHandle: Map<String, Item>

    @Volatile
    private var observationSetupFailure: Throwable? = null

    init {
        requireBatchSize(messages.size)
        require(messages.all { it.queueUrl == queueUrl }) {
            "batch acknowledgement queue does not match the current batch"
        }
        require(messages.map { it.receiptHandle }.distinct().size == messages.size) {
            "duplicate batch acknowledgement receipt handle"
        }
        require(messages.map { it.messageId }.distinct().size == messages.size) {
            "duplicate batch acknowledgement message id"
        }
        items = messages.mapIndexed { index, message -> Item(message, index) }
        byReceiptHandle = items.associateBy { it.message.receiptHandle }
    }

    override val pending: List<SqsReceivedMessage>
        get() = synchronized(snapshotLock) {
            items.filter { it.state != ItemState.ACKED && it.state != ItemState.DEFERRED }
                .map { it.message }
                .toList()
        }

    override val completed: Boolean
        get() = synchronized(snapshotLock) {
            items.all { it.state == ItemState.ACKED || it.state == ItemState.DEFERRED }
        }

    internal fun updateAttempt(attempt: Int) {
        require(attempt >= 1) { "attempt must be positive" }
        this.attempt = attempt
    }

    override suspend fun acknowledge(): SqsBatchAcknowledgementResult =
        acknowledge(pending)

    override suspend fun acknowledge(messages: Collection<SqsReceivedMessage>): SqsBatchAcknowledgementResult =
        execute(SqsBatchAcknowledgementOperation.ACKNOWLEDGE, messages.toList(), timeoutSeconds = null)

    override suspend fun nack(
        messages: Collection<SqsReceivedMessage>,
        timeoutSeconds: Int,
    ): SqsBatchAcknowledgementResult {
        requireVisibilityTimeout(timeoutSeconds)
        return execute(SqsBatchAcknowledgementOperation.NACK, messages.toList(), timeoutSeconds)
    }

    override suspend fun changeVisibility(
        messages: Collection<SqsReceivedMessage>,
        timeoutSeconds: Int,
    ): SqsBatchAcknowledgementResult {
        requireVisibilityTimeout(timeoutSeconds)
        return execute(SqsBatchAcknowledgementOperation.CHANGE_VISIBILITY, messages.toList(), timeoutSeconds)
    }

    internal suspend fun heartbeat(
        messages: Collection<SqsReceivedMessage>,
        timeoutSeconds: Int,
        onObservationFailure: (Throwable) -> Unit,
    ): SqsBatchAcknowledgementResult {
        requireVisibilityTimeout(timeoutSeconds)
        return execute(
            operation = SqsBatchAcknowledgementOperation.CHANGE_VISIBILITY,
            requested = messages.toList(),
            timeoutSeconds = timeoutSeconds,
            onObservationCleanupFailure = onObservationFailure,
        )
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun execute(
        operation: SqsBatchAcknowledgementOperation,
        requested: List<SqsReceivedMessage>,
        timeoutSeconds: Int?,
        onObservationCleanupFailure: ((Throwable) -> Unit)? = null,
    ): SqsBatchAcknowledgementResult {
        val requestedItems = validateRequested(requested)
        val action = operation.toAcknowledgementAction()
        val context = requireNotNull(items.firstOrNull()?.let {
            SqsListenerInvocationContext(listenerId, queueUrl, it.message, attempt)
        })
        var failure: Throwable? = null
        var afterAcknowledgementCompleted = false
        var result: SqsBatchAcknowledgementResult? = null
        try {
            beforeAcknowledgement(context, action, requestedItems.size)
            result = executeReserved(
                operation = operation,
                action = action,
                requestedItems = requestedItems,
                timeoutSeconds = timeoutSeconds,
                context = context,
                onObservationCleanupFailure = onObservationCleanupFailure,
                onCancellationFinalized = { afterAcknowledgementCompleted = true },
            )
        } catch (e: Throwable) {
            failure = e
        }
        val cleanupFailure = if (afterAcknowledgementCompleted) {
            null
        } else {
            runCatchingBatchFinalization {
                afterAcknowledgement(context, action, failure, requestedItems.size)
            }
        }
        throwAcknowledgementFailures(failure, cleanupFailure)
        return requireNotNull(result)
    }

    @Suppress("LongParameterList")
    private suspend fun executeReserved(
        operation: SqsBatchAcknowledgementOperation,
        action: SqsAcknowledgementAction,
        requestedItems: List<Item>,
        timeoutSeconds: Int?,
        context: SqsListenerInvocationContext,
        onObservationCleanupFailure: ((Throwable) -> Unit)?,
        onCancellationFinalized: () -> Unit,
    ): SqsBatchAcknowledgementResult {
        while (true) {
            when (val decision = reserve(operation, requestedItems)) {
                is Decision.Done -> {
                    notifyResult(context, action, decision.result, requestedItems.size)
                    return decision.result
                }
                is Decision.Wait -> decision.deferreds.awaitAll()
                is Decision.Run -> {
                    val result = executeRunDecision(
                        operation,
                        action,
                        timeoutSeconds,
                        context,
                        requestedItems.size,
                        decision,
                        onObservationCleanupFailure,
                        onCancellationFinalized,
                    )
                    withContext(NonCancellable) {
                        commit(decision.items, decision.deferred, operation, result)
                    }
                    notifyResult(context, action, result, requestedItems.size)
                    return result
                }
            }
        }
    }

    @Suppress("LongParameterList", "TooGenericExceptionCaught", "ThrowsCount")
    private suspend fun executeRunDecision(
        operation: SqsBatchAcknowledgementOperation,
        action: SqsAcknowledgementAction,
        timeoutSeconds: Int?,
        context: SqsListenerInvocationContext,
        requestedSize: Int,
        decision: Decision.Run,
        onObservationCleanupFailure: ((Throwable) -> Unit)?,
        onCancellationFinalized: () -> Unit,
    ): SqsBatchAcknowledgementResult {
        var rollbackCompleted = false
        return try {
            operationGuard()
            suspend fun performDecision(): SqsBatchAcknowledgementResult =
                try {
                    perform(
                        operation,
                        decision.requested,
                        decision.items,
                        decision.preSuccessIds,
                        decision.preFailures,
                        timeoutSeconds,
                    )
                } catch (e: CancellationException) {
                    rollbackAfterCancellation(decision.items, decision.deferred, e)
                    rollbackCompleted = true
                    completeAfterAcknowledgementCancellation(context, action, e, requestedSize)
                    onCancellationFinalized()
                    throw e
                }
            val activeRuntime = observationRuntime.activeOrNull()
            if (activeRuntime == null) {
                performDecision()
            } else {
                observeBatchAcknowledgement(
                    runtime = activeRuntime,
                    context = context,
                    action = action,
                    batchSize = decision.items.size,
                    onObservationCleanupFailure = onObservationCleanupFailure,
                ) {
                    performDecision()
                }
            }
        } catch (e: CancellationException) {
            if (!rollbackCompleted) {
                rollbackAfterCancellation(decision.items, decision.deferred, e)
            }
            throw e
        } catch (e: Throwable) {
            rollback(decision.items, decision.deferred)
            throw e
        }
    }

    @Suppress("ThrowsCount", "TooGenericExceptionCaught")
    private suspend fun <T> observeBatchAcknowledgement(
        runtime: SqsObservationRuntime,
        context: SqsListenerInvocationContext,
        action: SqsAcknowledgementAction,
        batchSize: Int,
        onObservationCleanupFailure: ((Throwable) -> Unit)?,
        block: suspend SqsObservationExecution.() -> T,
    ): T {
        var observedContext: SqsObservationContext? = null
        val observation = try {
            runtime.prepare(batchObservationContext(context, action, batchSize).also { observedContext = it })
        } catch (e: Throwable) {
            observationSetupFailure = e
            throw e
        }
        return observation.observe(
            onSetupFailure = { observationSetupFailure = it },
            onCleanupFailure = onObservationCleanupFailure,
        ) {
            try {
                val result = block()
                if (result is SqsBatchAcknowledgementResult) {
                    observedContext?.apply {
                        acknowledgementSuccessCount = result.successfulMessageIds.size
                        acknowledgementFailureCount = result.failed.size
                        outcome = when (result.status) {
                            SqsBatchAcknowledgementStatus.SUCCESS -> SqsObservationOutcome.SUCCESS
                            SqsBatchAcknowledgementStatus.PARTIAL_FAILURE -> SqsObservationOutcome.PARTIAL
                            SqsBatchAcknowledgementStatus.FAILURE -> SqsObservationOutcome.ERROR
                        }
                        if (result.status == SqsBatchAcknowledgementStatus.FAILURE) {
                            failureStage = "acknowledgement"
                        }
                    }
                }
                result
            } catch (e: CancellationException) {
                observedContext?.acknowledgementFailureCount = batchSize
                cancel("acknowledgement")
                throw e
            } catch (e: Throwable) {
                observedContext?.acknowledgementFailureCount = batchSize
                fail("acknowledgement")
                throw e
            }
        }
    }

    internal fun isObservationSetupFailure(error: Throwable): Boolean = observationSetupFailure === error

    private fun batchObservationContext(
        context: SqsListenerInvocationContext,
        action: SqsAcknowledgementAction,
        batchSize: Int,
    ): SqsObservationContext = SqsObservationContext(
        SqsObservationMetadata(
            listenerId = context.listenerId,
            queueName = observationQueueName ?: resolveSqsObservationQueueName(queueUrl),
            stage = SqsObservationStage.ACKNOWLEDGEMENT,
            batch = true,
            messageId = context.message.messageId,
            messageGroupId = context.message.messageGroupId,
            messageDeduplicationId = context.message.messageDeduplicationId,
            initialAttempt = attempt,
            batchSize = batchSize,
            acknowledgementAction = action,
            delivery = SqsObservationDelivery.UNKNOWN,
            queueNameResolved = true,
        ),
    )

    private suspend fun completeAfterAcknowledgementCancellation(
        context: SqsListenerInvocationContext,
        action: SqsAcknowledgementAction,
        cancellation: CancellationException,
        batchSize: Int,
    ) {
        val cleanupFailure = runCatchingBatchFinalization {
            afterAcknowledgement(context, action, cancellation, batchSize)
        }
        cleanupFailure?.takeUnless { it === cancellation }?.let(cancellation::addSuppressed)
    }

    private suspend fun beforeAcknowledgement(
        context: SqsListenerInvocationContext,
        action: SqsAcknowledgementAction,
        batchSize: Int,
    ) {
        interceptors.forEach {
            it.beforeAcknowledgement(context, action, correlation, batchSize)
        }
    }

    private suspend fun afterAcknowledgement(
        context: SqsListenerInvocationContext,
        action: SqsAcknowledgementAction,
        error: Throwable?,
        batchSize: Int,
    ) {
        interceptors.forEach {
            it.afterAcknowledgement(context, action, error, correlation, batchSize)
        }
    }

    private suspend fun notifyResult(
        context: SqsListenerInvocationContext?,
        action: SqsAcknowledgementAction,
        result: SqsBatchAcknowledgementResult,
        batchSize: Int,
    ) {
        context?.let {
            interceptors.forEach { interceptor ->
                interceptor.onBatchAcknowledgementResult(it, action, result, correlation, batchSize)
            }
        }
    }

    private fun validateRequested(requested: List<SqsReceivedMessage>): List<Item> {
        requireBatchSize(requested.size)
        val seenHandles = HashSet<String>(requested.size)
        val seenIds = HashSet<String>(requested.size)
        return requested.map { message ->
            require(message.queueUrl == queueUrl) {
                "batch acknowledgement queue does not match the current batch"
            }
            require(seenHandles.add(message.receiptHandle)) {
                "duplicate batch acknowledgement receipt handle"
            }
            require(seenIds.add(message.messageId)) {
                "duplicate batch acknowledgement message id"
            }
            val item = byReceiptHandle[message.receiptHandle]
            require(item != null && item.message.messageId == message.messageId) {
                "message does not belong to the current batch"
            }
            item
        }
    }

    private suspend fun reserve(
        operation: SqsBatchAcknowledgementOperation,
        requested: List<Item>,
    ): Decision = mutex.withLock {
        if (requested.isEmpty()) {
            return@withLock Decision.Done(result(operation, emptyList(), emptyList()))
        }

        val waiting = requested.mapNotNull { it.inFlight }.distinct()
        if (waiting.isNotEmpty()) {
            return@withLock Decision.Wait(waiting)
        }

        val preFailures = mutableListOf<SqsBatchAcknowledgementFailure>()
        val preSuccessIds = mutableListOf<String>()
        val runnable = mutableListOf<Item>()
        requested.forEach { item ->
            when (item.state) {
                ItemState.PENDING -> {
                    val predecessor = fifoPredecessor(item)
                    if (operation == SqsBatchAcknowledgementOperation.ACKNOWLEDGE && predecessor != null) {
                        preFailures += failure(item, "fifo_predecessor_pending")
                    } else {
                        runnable += item
                    }
                }
                ItemState.ACKED, ItemState.DEFERRED -> {
                    val outcome = item.outcomes[operation]
                    if (outcome == null) {
                        preFailures += failure(item, "already_terminal")
                    } else if (!outcome.success) {
                        preFailures += outcome.failure ?: failure(item, "previous_failure")
                    } else {
                        preSuccessIds += item.message.messageId
                    }
                }
                ItemState.IN_FLIGHT -> Unit
            }
        }
        if (runnable.isEmpty()) {
            return@withLock Decision.Done(result(operation, preSuccessIds, preFailures))
        }

        val deferred = CompletableDeferred<Unit>()
        synchronized(snapshotLock) {
            runnable.forEach {
                it.state = ItemState.IN_FLIGHT
                it.inFlight = deferred
            }
        }
        Decision.Run(requested, runnable, preSuccessIds, preFailures, deferred)
    }

    private suspend fun perform(
        operation: SqsBatchAcknowledgementOperation,
        requested: List<Item>,
        runnable: List<Item>,
        preSuccessIds: List<String>,
        preFailures: List<SqsBatchAcknowledgementFailure>,
        timeoutSeconds: Int?,
    ): SqsBatchAcknowledgementResult {
        val external: List<Pair<Item, Outcome>> = when (operation) {
            SqsBatchAcknowledgementOperation.ACKNOWLEDGE -> {
                val response = operations.deleteBatch(queueUrl, runnable.map { it.message.receiptHandle })
                validateDeleteResponse(runnable, response)
                response.successfulEntryIds.associateBy { it }
                    .let { successByEntry ->
                        val failureByEntry = response.failed.associateBy { it.entryId }
                        runnable.mapIndexed { index, item ->
                            val entryId = "entry-$index"
                            when {
                                successByEntry.containsKey(entryId) -> item to Outcome(true)
                                failureByEntry.containsKey(entryId) ->
                                    item to Outcome(false, failure(item, failureByEntry.getValue(entryId)))
                                else -> error("validated delete response did not contain $entryId")
                            }
                        }
                    }
            }
            SqsBatchAcknowledgementOperation.NACK,
            SqsBatchAcknowledgementOperation.CHANGE_VISIBILITY -> {
                val timeout = requireNotNull(timeoutSeconds)
                val requests = runnable.map {
                    SqsChangeVisibilityRequest(it.message.messageId, it.message.receiptHandle, timeout)
                }
                val response = operations.changeVisibilityBatch(queueUrl, requests)
                validateVisibilityResponse(runnable, response)
                val successIds = response.successfulMessageIds.toSet()
                val failureById = response.failed.associateBy { it.messageId }
                runnable.map { item ->
                    when {
                        item.message.messageId in successIds -> item to Outcome(true)
                        item.message.messageId in failureById ->
                            item to Outcome(false, failure(item, failureById.getValue(item.message.messageId)))
                        else -> error("validated visibility response did not contain ${item.message.messageId}")
                    }
                }
            }
        }
        val externalById = external.associateBy { it.first.message.messageId }
        val preSuccess = preSuccessIds.toSet()
        val preFailureById = preFailures.associateBy { it.messageId }
        val successful = requested.mapNotNull { item ->
            when {
                item.message.messageId in preSuccess -> item.message.messageId
                externalById[item.message.messageId]?.second?.success == true -> item.message.messageId
                else -> null
            }
        }
        val failures = requested.mapNotNull { item ->
            preFailureById[item.message.messageId]
                ?: externalById[item.message.messageId]?.second?.failure
        }
        return result(operation, successful, failures)
    }

    private suspend fun commit(
        runnable: List<Item>,
        deferred: CompletableDeferred<Unit>,
        operation: SqsBatchAcknowledgementOperation,
        result: SqsBatchAcknowledgementResult,
    ) {
        val successIds = result.successfulMessageIds.toSet()
        mutex.withLock {
            synchronized(snapshotLock) {
                runnable.forEach { item ->
                    if (item.message.messageId in successIds) {
                        item.state = when (operation) {
                            SqsBatchAcknowledgementOperation.ACKNOWLEDGE -> ItemState.ACKED
                            SqsBatchAcknowledgementOperation.NACK -> ItemState.DEFERRED
                            SqsBatchAcknowledgementOperation.CHANGE_VISIBILITY -> ItemState.PENDING
                        }
                        if (operation != SqsBatchAcknowledgementOperation.CHANGE_VISIBILITY) {
                            item.outcomes[operation] = Outcome(true)
                        }
                    } else {
                        item.state = ItemState.PENDING
                    }
                    item.inFlight = null
                }
            }
        }
        deferred.complete(Unit)
    }

    private suspend fun rollback(runnable: List<Item>, deferred: CompletableDeferred<Unit>) {
        rollbackState(runnable)
        deferred.complete(Unit)
    }

    private suspend fun rollbackState(runnable: List<Item>) {
        mutex.withLock {
            synchronized(snapshotLock) {
                runnable.forEach {
                    it.state = ItemState.PENDING
                    it.inFlight = null
                }
            }
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun rollbackAfterCancellation(
        runnable: List<Item>,
        deferred: CompletableDeferred<Unit>,
        cancellation: CancellationException,
    ) {
        withContext(NonCancellable) {
            val failures = mutableListOf<Throwable>()
            try {
                rollbackState(runnable)
            } catch (e: Throwable) {
                failures += e
            }
            try {
                deferred.complete(Unit)
            } catch (e: Throwable) {
                failures += e
            }
            failures.forEach { failure ->
                if (failure !== cancellation) {
                    cancellation.addSuppressed(failure)
                }
            }
        }
    }

    private fun fifoPredecessor(item: Item): Item? {
        val groupId = item.message.messageGroupId ?: return null
        return items.take(item.index).firstOrNull {
            it.message.messageGroupId == groupId && it.state != ItemState.ACKED && it.state != ItemState.DEFERRED
        }
    }

    private fun result(
        operation: SqsBatchAcknowledgementOperation,
        successfulIds: List<String>,
        failures: List<SqsBatchAcknowledgementFailure>,
    ): SqsBatchAcknowledgementResult {
        val status = when {
            failures.isEmpty() -> SqsBatchAcknowledgementStatus.SUCCESS
            successfulIds.isEmpty() -> SqsBatchAcknowledgementStatus.FAILURE
            else -> SqsBatchAcknowledgementStatus.PARTIAL_FAILURE
        }
        return SqsBatchAcknowledgementResult(operation, status, successfulIds.toList(), failures.toList())
    }

    private fun failure(item: Item, code: String): SqsBatchAcknowledgementFailure =
        SqsBatchAcknowledgementFailure(item.message.messageId, code, null, false)

    private fun failure(item: Item, failure: SqsBatchDeleteFailure): SqsBatchAcknowledgementFailure =
        SqsBatchAcknowledgementFailure(item.message.messageId, failure.code, failure.detail, failure.senderFault)

    private fun failure(item: Item, failure: SqsBatchAcknowledgementFailure): SqsBatchAcknowledgementFailure =
        failure.copy(messageId = item.message.messageId)

    private fun SqsBatchAcknowledgementOperation.toAcknowledgementAction(): SqsAcknowledgementAction = when (this) {
        SqsBatchAcknowledgementOperation.ACKNOWLEDGE -> SqsAcknowledgementAction.ACK
        SqsBatchAcknowledgementOperation.NACK -> SqsAcknowledgementAction.NACK
        SqsBatchAcknowledgementOperation.CHANGE_VISIBILITY -> SqsAcknowledgementAction.CHANGE_VISIBILITY
    }

    private fun validateDeleteResponse(items: List<Item>, response: SqsBatchDeleteResult) {
        val expected = items.indices.map { "entry-$it" }
        val actual = response.successfulEntryIds + response.failed.map { it.entryId }
        val hasExpectedSize = actual.size == expected.size
        val hasUniqueEntries = actual.distinct().size == expected.size
        if (!hasExpectedSize || !hasUniqueEntries || actual.toSet() != expected.toSet()) {
            throw SqsBatchDeleteProtocolException(expected, actual)
        }
    }

    private fun validateVisibilityResponse(items: List<Item>, response: SqsBatchVisibilityResult) {
        val expectedMessageIds = items.map { it.message.messageId }
        val expectedEntries = expectedMessageIds.indices.map { "entry-$it" }
        val actualMessageIds = response.successfulMessageIds + response.failed.map { it.messageId }
        val actualEntries = actualMessageIds.map { messageId ->
            expectedMessageIds.indexOf(messageId).takeIf { it >= 0 }?.let { "entry-$it" } ?: "unknown"
        }
        if (actualEntries.size != expectedEntries.size || actualEntries.distinct().size != expectedEntries.size ||
            actualEntries.toSet() != expectedEntries.toSet()
        ) {
            throw SqsBatchVisibilityProtocolException(expectedEntries, actualEntries)
        }
    }
}

@Suppress("TooGenericExceptionCaught")
private suspend fun runCatchingBatchFinalization(block: suspend () -> Unit): Throwable? =
    withContext(NonCancellable) {
        try {
            block()
            null
        } catch (e: Throwable) {
            e
        }
    }
