package io.bluetape4k.aws.spring.sqs

import io.bluetape4k.logging.KLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import java.util.LinkedHashMap
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** 자동 배치 entry의 admission, 결과 수집, 취소와 close claim을 조정합니다. */
@Suppress("TooManyFunctions")
internal class SqsBatchCoordinator(
    private val properties: SqsBatchProperties,
    private val transport: SqsBatchTransport,
    private val hooks: SqsBatchCoordinatorHooks = SqsBatchCoordinatorHooks.None,
) {
    companion object : KLogging()

    private val permits = Semaphore(properties.maxInFlightEntries)
    private val lifecycleLock = ReentrantLock()
    private val closeSignal = CompletableDeferred<Unit>()
    private val closeCompletion = CompletableFuture<SqsBatchCloseOutcome>()
    private val tokenSequence = AtomicLong()
    private val acceptedEntries = LinkedHashMap<Long, AcceptedEntry>()
    private var state = CoordinatorState.OPEN

    private val activeFutureCount = AtomicInteger()
    private val residentChildCount = AtomicInteger()
    private val pendingResultCount = AtomicInteger()
    private val peakActiveFutureCount = AtomicInteger()
    private val peakAcceptedEntryCount = AtomicInteger()
    private val peakResidentChildCount = AtomicInteger()
    private val peakPendingResultCount = AtomicInteger()

    suspend fun sendMany(
        entries: Collection<SqsBatchSendEntry>,
        strategy: SendBatchFailureStrategy = SendBatchFailureStrategy.RETURN,
    ): SqsSendManyResult {
        val callerCancellation = AtomicReference<CancellationException?>()
        try {
            currentCoroutineContext().ensureActive()
            val snapshot = boundedSnapshot(entries) { SqsBatchSendEntry(it.entryId, it.request) }
            validateDistinctEntryIds(snapshot.map(SqsBatchSendEntry::entryId))
            ensureOpen()
            val outcomes = executeWindows(snapshot, transport::send, callerCancellation)
            val result = BatchResultNormalizer.send(snapshot.map(SqsBatchSendEntry::entryId), outcomes)
            if (strategy == SendBatchFailureStrategy.THROW && result.failed.isNotEmpty()) {
                throw SqsSendBatchFailedException(result)
            }
            return result
        } catch (cancellation: CancellationException) {
            throw callerCancellation.preserve(cancellation)
        }
    }

    suspend fun deleteMany(entries: Collection<SqsBatchDeleteEntry>): SqsDeleteManyResult {
        val callerCancellation = AtomicReference<CancellationException?>()
        try {
            currentCoroutineContext().ensureActive()
            val snapshot = boundedSnapshot(entries) {
                SqsBatchDeleteEntry(it.entryId, it.queueUrl, it.receiptHandle)
            }
            validateDistinctEntryIds(snapshot.map(SqsBatchDeleteEntry::entryId))
            ensureOpen()
            val outcomes = executeWindows(snapshot, transport::delete, callerCancellation)
            return BatchResultNormalizer.delete(snapshot.map(SqsBatchDeleteEntry::entryId), outcomes)
        } catch (cancellation: CancellationException) {
            throw callerCancellation.preserve(cancellation)
        }
    }

    fun beginClose(): SqsBatchCloseClaim {
        val decision = lifecycleLock.withLock {
            if (state == CoordinatorState.OPEN) {
                state = CoordinatorState.CLOSING
                CloseDecision.Owner(acceptedEntries.values.toList())
            } else {
                CloseDecision.Observer
            }
        }
        return when (decision) {
            is CloseDecision.Owner -> {
                closeSignal.complete(Unit)
                log.info("SQS batch coordinator close started (acceptedCount=${decision.accepted.size}).")
                SqsBatchCloseClaim.Owner(decision.accepted, closeCompletion)
            }
            CloseDecision.Observer -> SqsBatchCloseClaim.Observer(closeCompletion)
        }
    }

    fun finishClose(outcome: SqsBatchCloseOutcome) {
        val complete = lifecycleLock.withLock {
            when (state) {
                CoordinatorState.OPEN -> throw IllegalStateException("SQS batch close has not started.")
                CoordinatorState.CLOSING -> {
                    state = CoordinatorState.CLOSED
                    true
                }
                CoordinatorState.CLOSED -> false
            }
        }
        if (complete) {
            closeCompletion.complete(outcome)
            when (outcome) {
                SqsBatchCloseOutcome.Success -> log.info("SQS batch coordinator close completed.")
                is SqsBatchCloseOutcome.Failure -> log.warn(
                    "SQS batch coordinator close failed " +
                        "(components=${outcome.exception.components.joinToString(",")}, " +
                        "failureCount=${outcome.exception.failureCount}).",
                )
            }
        }
    }

    internal fun metrics(): SqsBatchCoordinatorMetrics = lifecycleLock.withLock {
        SqsBatchCoordinatorMetrics(
            activeFutureCount = activeFutureCount.get(),
            acceptedEntryCount = acceptedEntries.size,
            residentChildCount = residentChildCount.get(),
            pendingResultCount = pendingResultCount.get(),
            availablePermits = permits.availablePermits,
            peakActiveFutureCount = peakActiveFutureCount.get(),
            peakAcceptedEntryCount = peakAcceptedEntryCount.get(),
            peakResidentChildCount = peakResidentChildCount.get(),
            peakPendingResultCount = peakPendingResultCount.get(),
        )
    }

    private fun ensureOpen() {
        lifecycleLock.withLock {
            if (state != CoordinatorState.OPEN) {
                throw coordinatorClosedException()
            }
        }
    }

    private fun <T> boundedSnapshot(
        entries: Collection<T>,
        canonicalize: (T) -> T,
    ): List<T> {
        val iterator = entries.iterator()
        val snapshot = ArrayList<T>(properties.maxEntriesPerCall.coerceAtMost(DEFAULT_SNAPSHOT_CAPACITY))
        while (snapshot.size <= properties.maxEntriesPerCall && iterator.hasNext()) {
            snapshot += canonicalize(iterator.next())
        }
        require(snapshot.size <= properties.maxEntriesPerCall) {
            "SQS batch entries exceed max-entries-per-call."
        }
        return snapshot
    }

    private suspend fun <T> executeWindows(
        entries: List<T>,
        submit: (T) -> CompletableFuture<SqsBatchOutcome>,
        callerCancellation: AtomicReference<CancellationException?>,
    ): List<SqsBatchOutcome> {
        if (entries.isEmpty()) return emptyList()
        val outcomes = ArrayList<SqsBatchOutcome>(entries.size)
        var windowStart = 0
        while (windowStart < entries.size) {
            currentCoroutineContext().ensureActive()
            val windowEnd = minOf(windowStart + properties.maxInFlightEntries, entries.size)
            val window = entries.subList(windowStart, windowEnd)
            val pending = java.util.concurrent.ConcurrentHashMap<Int, SqsBatchOutcome>()
            try {
                supervisorScope {
                    window.mapIndexed { index, entry ->
                        async {
                            incrementResidentChildren()
                            try {
                                pending[index] = executeEntry(entry, submit, callerCancellation)
                                incrementPendingResults()
                            } finally {
                                residentChildCount.decrementAndGet()
                            }
                        }
                    }.awaitAll()
                }
                window.indices.forEach { outcomes += pending.getValue(it) }
            } finally {
                val retained = pending.size
                pending.clear()
                if (retained > 0) {
                    pendingResultCount.addAndGet(-retained)
                }
            }
            windowStart = windowEnd
        }
        return outcomes
    }

    @Suppress("ReturnCount", "ThrowsCount", "TooGenericExceptionCaught")
    private suspend fun <T> executeEntry(
        entry: T,
        submit: (T) -> CompletableFuture<SqsBatchOutcome>,
        callerCancellation: AtomicReference<CancellationException?>,
    ): SqsBatchOutcome {
        if (!acquirePermitCloseAware()) {
            throw coordinatorClosedException()
        }
        var accepted: AcceptedEntry? = null
        var removed = false
        try {
            hooks.afterPermitAcquired()
            accepted = registerAcceptedEntry(entryIdOf(entry))
            hooks.afterPlaceholderRegistered(accepted.token)
            val future = try {
                submit(entry)
            } catch (cancellation: CancellationException) {
                if (!currentCoroutineContext().isActive) {
                    throw callerCancellation.preserve(cancellation)
                }
                return normalizeEntryFailure(accepted.entryId, cancellation)
            } catch (failure: Exception) {
                return normalizeEntryFailure(accepted.entryId, failure)
            }
            hooks.afterTransportSubmitted(accepted.token, future)
            attachFuture(accepted, future)
            hooks.afterFutureAttached(accepted.token)
            val outcome = awaitOutcome(accepted, future)
            hooks.afterTransportResponse(accepted.token)
            return outcome
        } catch (cancellation: CancellationException) {
            if (!currentCoroutineContext().isActive) {
                throw callerCancellation.preserve(cancellation)
            }
            throw cancellation
        } finally {
            accepted?.cancelIfIncomplete()
            if (accepted != null) {
                removed = removeAcceptedEntry(accepted)
            }
            permits.release()
            accepted?.takeIf { removed }?.completion?.complete(Unit)
        }
    }

    private suspend fun acquirePermitCloseAware(): Boolean = coroutineScope {
        val acquired = AtomicBoolean()
        val ready = CompletableDeferred<Unit>()
        val waiter = launch(start = CoroutineStart.UNDISPATCHED) {
            permits.acquire()
            acquired.set(true)
            ready.complete(Unit)
        }
        var transferred = false
        try {
            val permitSelected = select {
                ready.onAwait { true }
                closeSignal.onAwait { false }
            }
            if (permitSelected) {
                acquired.set(false)
                transferred = true
            }
            permitSelected
        } finally {
            withContext(NonCancellable) {
                waiter.cancel()
                waiter.join()
            }
            if (!transferred && acquired.compareAndSet(true, false)) {
                permits.release()
            }
        }
    }

    private fun registerAcceptedEntry(entryId: String): AcceptedEntry = lifecycleLock.withLock {
        if (state != CoordinatorState.OPEN) {
            throw coordinatorClosedException()
        }
        val token = tokenSequence.incrementAndGet()
        AcceptedEntry(token, entryId).also { accepted ->
            acceptedEntries[token] = accepted
            updatePeak(peakAcceptedEntryCount, acceptedEntries.size)
        }
    }

    private fun attachFuture(
        accepted: AcceptedEntry,
        future: CompletableFuture<SqsBatchOutcome>,
    ) {
        lifecycleLock.withLock {
            check(acceptedEntries[accepted.token] === accepted) {
                "SQS batch accepted entry is not registered."
            }
            accepted.attach(future)
            val current = activeFutureCount.incrementAndGet()
            updatePeak(peakActiveFutureCount, current)
        }
        future.whenComplete { _, _ -> activeFutureCount.decrementAndGet() }
        accepted.cancelAttachedIfRequested()
    }

    private fun removeAcceptedEntry(accepted: AcceptedEntry): Boolean = lifecycleLock.withLock {
        acceptedEntries.remove(accepted.token, accepted)
    }

    private suspend fun awaitOutcome(
        accepted: AcceptedEntry,
        future: CompletableFuture<SqsBatchOutcome>,
    ): SqsBatchOutcome = suspendCancellableCoroutine { continuation ->
        future.whenComplete { outcome, failure ->
            val token = when {
                failure != null -> continuation.tryResume(
                    SqsBatchOutcome.Failure(normalizeBatchFailure(accepted.entryId, failure)),
                )
                outcome != null -> continuation.tryResume(outcome)
                else -> continuation.tryResumeWithException(
                    IllegalStateException("SQS batch transport completed without an outcome."),
                )
            }
            token?.let(continuation::completeResume)
        }
        continuation.invokeOnCancellation { accepted.cancelIfIncomplete() }
    }

    private fun incrementResidentChildren() {
        val current = residentChildCount.incrementAndGet()
        updatePeak(peakResidentChildCount, current)
    }

    private fun incrementPendingResults() {
        val current = pendingResultCount.incrementAndGet()
        updatePeak(peakPendingResultCount, current)
    }

    private fun normalizeEntryFailure(entryId: String, failure: Throwable): SqsBatchOutcome.Failure =
        SqsBatchOutcome.Failure(normalizeBatchFailure(entryId, failure))

    private fun entryIdOf(entry: Any?): String = when (entry) {
        is SqsBatchSendEntry -> entry.entryId
        is SqsBatchDeleteEntry -> entry.entryId
        else -> error("Unsupported SQS batch entry type.")
    }

    private inner class AcceptedEntry(
        val token: Long,
        val entryId: String,
    ) : SqsAcceptedBatchEntry {
        private val future = AtomicReference<CompletableFuture<SqsBatchOutcome>?>()
        private val cancelRequested = AtomicBoolean()
        private val cancelInvoked = AtomicBoolean()

        override val completion = CompletableFuture<Unit>()

        fun attach(attached: CompletableFuture<SqsBatchOutcome>) {
            check(future.compareAndSet(null, attached)) { "SQS batch future is already attached." }
        }

        override fun cancelIfIncomplete(): Boolean {
            if (!cancelRequested.compareAndSet(false, true)) return false
            cancelAttachedIfRequested()
            return true
        }

        fun cancelAttachedIfRequested() {
            val attached = future.get() ?: return
            if (cancelRequested.get() && !attached.isDone && cancelInvoked.compareAndSet(false, true)) {
                attached.cancel(false)
            }
        }
    }

    private sealed interface CloseDecision {
        class Owner(val accepted: List<AcceptedEntry>) : CloseDecision
        data object Observer : CloseDecision
    }

    private enum class CoordinatorState {
        OPEN,
        CLOSING,
        CLOSED,
    }
}

/** close owner와 observer가 공유하는 coordinator close claim입니다. */
internal sealed interface SqsBatchCloseClaim {
    val completion: CompletableFuture<SqsBatchCloseOutcome>

    class Owner(
        val accepted: List<SqsAcceptedBatchEntry>,
        override val completion: CompletableFuture<SqsBatchCloseOutcome>,
    ) : SqsBatchCloseClaim

    class Observer(
        override val completion: CompletableFuture<SqsBatchCloseOutcome>,
    ) : SqsBatchCloseClaim
}

/** close owner가 drain과 timeout cancel에 사용하는 accepted entry handle입니다. */
internal interface SqsAcceptedBatchEntry {
    val completion: CompletableFuture<Unit>

    fun cancelIfIncomplete(): Boolean
}

/** 모든 close caller가 같은 identity로 관찰하는 coordinator close 결과입니다. */
internal sealed interface SqsBatchCloseOutcome {
    data object Success : SqsBatchCloseOutcome

    class Failure(val exception: SqsBatchCloseException) : SqsBatchCloseOutcome
}

/** deterministic concurrency test에만 노출하는 coordinator 계측 스냅숏입니다. */
internal class SqsBatchCoordinatorMetrics(
    val activeFutureCount: Int,
    val acceptedEntryCount: Int,
    val residentChildCount: Int,
    val pendingResultCount: Int,
    val availablePermits: Int,
    val peakActiveFutureCount: Int,
    val peakAcceptedEntryCount: Int,
    val peakResidentChildCount: Int,
    val peakPendingResultCount: Int,
)

/** 외부 호출 없이 race 지점을 고정하는 internal test hook입니다. */
internal interface SqsBatchCoordinatorHooks {
    suspend fun afterPermitAcquired() = Unit

    suspend fun afterPlaceholderRegistered(token: Long) = Unit

    suspend fun afterTransportSubmitted(
        token: Long,
        future: CompletableFuture<SqsBatchOutcome>,
    ) = Unit

    suspend fun afterFutureAttached(token: Long) = Unit

    suspend fun afterTransportResponse(token: Long) = Unit

    companion object {
        val None: SqsBatchCoordinatorHooks = object : SqsBatchCoordinatorHooks {}
    }
}

private fun validateDistinctEntryIds(entryIds: List<String>) {
    require(entryIds.size == entryIds.toSet().size) { "SQS batch entryIds must be distinct." }
}

private fun coordinatorClosedException(): IllegalStateException =
    IllegalStateException("SQS batch coordinator is closing or closed.")

private fun updatePeak(peak: AtomicInteger, candidate: Int) {
    peak.accumulateAndGet(candidate, ::maxOf)
}

private fun AtomicReference<CancellationException?>.preserve(
    cancellation: CancellationException,
): CancellationException {
    compareAndSet(null, cancellation)
    return checkNotNull(get())
}

private const val DEFAULT_SNAPSHOT_CAPACITY = 16
