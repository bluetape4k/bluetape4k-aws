package io.bluetape4k.aws.spring.sqs

import java.time.Instant

/** Extended consumer rollback 단계입니다. */
enum class SqsExtendedRollbackState {
    RUNNING_EXTENDED,
    PRODUCER_DISABLED,
    LEGACY_CONSUMER_STOPPED,
    EXTENDED_DRAINING,
    DRAIN_VERIFIED,
    QUARANTINE_REHYDRATING,
    LEGACY_REDRIVE_VERIFIED,
    LEGACY_CONSUMER_STARTED,
    ROLLBACK_BLOCKED,
}

enum class SqsExtendedRollbackBlockReason {
    DEADLINE_EXCEEDED,
    REDRIVE_BUDGET_EXHAUSTED,
}

/** rollback controller가 queue에서 읽은 불변 관찰값입니다. */
data class SqsExtendedRollbackProbe(
    val observedAt: Instant,
    val pointerCount: Int,
    val inFlightCount: Int,
    val approximateReceiveCount: Int,
    val maxReceiveCount: Int? = null,
    val dlqCount: Int = 0,
    val redrivePolicyMalformed: Boolean = false,
    val visibilityWindowComplete: Boolean = false,
) {
    init {
        require(pointerCount >= 0) { "pointerCount must not be negative." }
        require(inFlightCount >= 0) { "inFlightCount must not be negative." }
        require(approximateReceiveCount >= 0) { "approximateReceiveCount must not be negative." }
        require(maxReceiveCount == null || maxReceiveCount > 0) { "maxReceiveCount must be positive." }
        require(dlqCount >= 0) { "dlqCount must not be negative." }
    }

    val empty: Boolean
        get() = pointerCount == 0 && inFlightCount == 0
}

/** quarantine pointer를 legacy-safe queue로 재발행한 결과입니다. */
data class SqsExtendedRehydrationResult(
    val quarantinedPointerCount: Int,
    val rehydratedCount: Int,
    val destinationPointerCount: Int,
    val pointerRemaining: Int,
    val idempotent: Boolean,
) {
    init {
        require(quarantinedPointerCount >= 0) { "quarantinedPointerCount must not be negative." }
        require(rehydratedCount >= 0) { "rehydratedCount must not be negative." }
        require(destinationPointerCount >= 0) { "destinationPointerCount must not be negative." }
        require(pointerRemaining >= 0) { "pointerRemaining must not be negative." }
    }

    val verified: Boolean
        get() = rehydratedCount == quarantinedPointerCount &&
            destinationPointerCount == 0 &&
            pointerRemaining == 0 &&
            idempotent
}

/** rollback controller가 수행해야 하는 외부 side effect 경계입니다. */
interface SqsExtendedRollbackRuntime {
    fun disableProducer()

    fun stopLegacyConsumer()

    suspend fun drainExtended(): SqsExtendedDrainResult

    /** Receive(max=1, visibility=0, wait=0) 기반의 read-only probe여야 합니다. */
    suspend fun probeExtendedQueue(): SqsExtendedRollbackProbe

    /** pointer를 복원해 inline body로 재발행하고 성공 후 quarantine receipt를 삭제합니다. */
    suspend fun rehydrateQuarantine(): SqsExtendedRehydrationResult

    fun startLegacyConsumer()
}

data class SqsExtendedRollbackEvidence(
    val state: SqsExtendedRollbackState,
    val stateHistory: List<SqsExtendedRollbackState>,
    val blockReason: SqsExtendedRollbackBlockReason? = null,
    val probeCount: Int = 0,
    val initialDlqCount: Int = 0,
    val sourceQueueUrl: String? = null,
    val destinationQueueUrl: String? = null,
    val globalDeadline: Instant? = null,
)

/**
 * visibility quiescence와 redrive budget을 확인한 뒤에만 legacy consumer를 시작하는
 * deterministic rollback coordinator입니다.
 */
class SqsExtendedRollbackCoordinator(
    private val policy: SqsExtendedClientProperties.Policy,
    private val sourceQueueUrl: String,
    private val destinationQueueUrl: String,
    private val runtime: SqsExtendedRollbackRuntime,
    private val now: () -> Instant = Instant::now,
) {

    private var state = SqsExtendedRollbackState.RUNNING_EXTENDED
    private val history = mutableListOf(state)
    private var blockReason: SqsExtendedRollbackBlockReason? = null
    private var probeCount = 0
    private var initialDlqCount: Int? = null
    private var globalDeadline: Instant? = null

    @Suppress("ReturnCount")
    suspend fun rollback(): SqsExtendedRollbackEvidence {
        if (state != SqsExtendedRollbackState.RUNNING_EXTENDED) return evidence()

        globalDeadline = now().plusSeconds(policy.effectiveRollbackDeadlineSeconds())
        transition(SqsExtendedRollbackState.PRODUCER_DISABLED) { runtime.disableProducer() }
        transition(SqsExtendedRollbackState.LEGACY_CONSUMER_STOPPED) { runtime.stopLegacyConsumer() }
        transition(SqsExtendedRollbackState.EXTENDED_DRAINING) { }

        val drain = runtime.drainExtended()
        if (drain.timedOut) return block(SqsExtendedRollbackBlockReason.DEADLINE_EXCEEDED)

        val quiescent = awaitQuiescence()
        if (!quiescent) return evidence()

        transition(SqsExtendedRollbackState.DRAIN_VERIFIED) { }
        transition(SqsExtendedRollbackState.QUARANTINE_REHYDRATING) { }
        val rehydration = runtime.rehydrateQuarantine()
        if (!rehydration.verified) return block(SqsExtendedRollbackBlockReason.REDRIVE_BUDGET_EXHAUSTED)

        transition(SqsExtendedRollbackState.LEGACY_REDRIVE_VERIFIED) { }
        transition(SqsExtendedRollbackState.LEGACY_CONSUMER_STARTED) { runtime.startLegacyConsumer() }
        return evidence()
    }

    @Suppress("ComplexCondition", "CyclomaticComplexMethod", "LoopWithTooManyJumpStatements", "ReturnCount")
    private suspend fun awaitQuiescence(): Boolean {
        var firstEmptyAt: Instant? = null
        val observationWindowSeconds = (policy.configuredMaxVisibilityRetryWindowSeconds ?: 1).toLong()
        while (true) {
            val probe = runtime.probeExtendedQueue()
            probeCount++
            if (initialDlqCount == null) initialDlqCount = probe.dlqCount
            if (globalDeadline?.let { probe.observedAt >= it } == true) {
                block(SqsExtendedRollbackBlockReason.DEADLINE_EXCEEDED)
                return false
            }
            if (probe.redrivePolicyMalformed ||
                (probe.maxReceiveCount != null && probe.approximateReceiveCount >= probe.maxReceiveCount) ||
                probe.dlqCount > requireNotNull(initialDlqCount)
            ) {
                block(SqsExtendedRollbackBlockReason.REDRIVE_BUDGET_EXHAUSTED)
                return false
            }
            if (!probe.empty) {
                firstEmptyAt = null
                continue
            }
            if (firstEmptyAt == null) {
                firstEmptyAt = probe.observedAt
                continue
            }
            val windowComplete = probe.visibilityWindowComplete ||
                probe.observedAt >= firstEmptyAt.plusSeconds(observationWindowSeconds)
            if (windowComplete) return true
        }
    }

    private inline fun transition(next: SqsExtendedRollbackState, action: () -> Unit) {
        check(state != SqsExtendedRollbackState.ROLLBACK_BLOCKED) { "rollback is blocked." }
        action()
        state = next
        history += next
    }

    private fun block(reason: SqsExtendedRollbackBlockReason): SqsExtendedRollbackEvidence {
        blockReason = reason
        state = SqsExtendedRollbackState.ROLLBACK_BLOCKED
        history += state
        return evidence()
    }

    private fun evidence(): SqsExtendedRollbackEvidence = SqsExtendedRollbackEvidence(
        state = state,
        stateHistory = history.toList(),
        blockReason = blockReason,
        probeCount = probeCount,
        initialDlqCount = initialDlqCount ?: 0,
        sourceQueueUrl = sourceQueueUrl,
        destinationQueueUrl = destinationQueueUrl,
        globalDeadline = globalDeadline,
    )
}
