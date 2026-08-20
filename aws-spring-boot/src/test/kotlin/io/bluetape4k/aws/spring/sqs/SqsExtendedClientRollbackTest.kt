package io.bluetape4k.aws.spring.sqs

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.codec.Base58
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.time.Instant

class SqsExtendedClientRollbackTest {

    @Test
    fun `rollback verifies two empty visibility windows before legacy start`() = runTest {
        val start = Instant.parse("2026-08-20T00:00:00Z")
        val runtime = FakeRollbackRuntime(
            probes = ArrayDeque(
                listOf(
                    probe(start.plusSeconds(1)),
                    probe(start.plusSeconds(2)),
                ),
            ),
            rehydration = SqsExtendedRehydrationResult(2, 2, 0, 0, idempotent = true),
        )
        val evidence = coordinator(runtime, start, retryWindowSeconds = 1, deadlineSeconds = 30).rollback()

        evidence.state shouldBeEqualTo SqsExtendedRollbackState.LEGACY_CONSUMER_STARTED
        evidence.stateHistory shouldBeEqualTo listOf(
            SqsExtendedRollbackState.RUNNING_EXTENDED,
            SqsExtendedRollbackState.PRODUCER_DISABLED,
            SqsExtendedRollbackState.LEGACY_CONSUMER_STOPPED,
            SqsExtendedRollbackState.EXTENDED_DRAINING,
            SqsExtendedRollbackState.DRAIN_VERIFIED,
            SqsExtendedRollbackState.QUARANTINE_REHYDRATING,
            SqsExtendedRollbackState.LEGACY_REDRIVE_VERIFIED,
            SqsExtendedRollbackState.LEGACY_CONSUMER_STARTED,
        )
        runtime.startCalls shouldBeEqualTo 1
        runtime.deleteCalls shouldBeEqualTo 0
    }

    @Test
    fun `rollback blocks before rehydration when receive budget or DLQ gate fails`() = runTest {
        val start = Instant.parse("2026-08-20T00:00:00Z")
        val runtime = FakeRollbackRuntime(
            probes = ArrayDeque(listOf(probe(start.plusSeconds(1), receiveCount = 3, maxReceiveCount = 3))),
        )

        val evidence = coordinator(runtime, start, retryWindowSeconds = 1, deadlineSeconds = 30).rollback()

        evidence.state shouldBeEqualTo SqsExtendedRollbackState.ROLLBACK_BLOCKED
        evidence.blockReason shouldBeEqualTo SqsExtendedRollbackBlockReason.REDRIVE_BUDGET_EXHAUSTED
        runtime.rehydrateCalls shouldBeEqualTo 0
        runtime.startCalls shouldBeEqualTo 0
    }

    @Test
    fun `rollback fixes global deadline even when a pointer reappears`() = runTest {
        val start = Instant.parse("2026-08-20T00:00:00Z")
        val runtime = FakeRollbackRuntime(
            probes = ArrayDeque(
                listOf(
                    probe(start.plusSeconds(1)),
                    probe(start.plusSeconds(5), pointerCount = 1),
                ),
            ),
        )

        val evidence = coordinator(runtime, start, retryWindowSeconds = 1, deadlineSeconds = 5).rollback()

        evidence.state shouldBeEqualTo SqsExtendedRollbackState.ROLLBACK_BLOCKED
        evidence.blockReason shouldBeEqualTo SqsExtendedRollbackBlockReason.DEADLINE_EXCEEDED
        runtime.rehydrateCalls shouldBeEqualTo 0
        runtime.startCalls shouldBeEqualTo 0
    }

    @Test
    fun `rollback blocks when rehydration counts are not idempotently verified`() = runTest {
        val start = Instant.parse("2026-08-20T00:00:00Z")
        val runtime = FakeRollbackRuntime(
            probes = ArrayDeque(listOf(probe(start.plusSeconds(1)), probe(start.plusSeconds(2)))),
            rehydration = SqsExtendedRehydrationResult(2, 1, 1, 1, idempotent = false),
        )

        val evidence = coordinator(runtime, start, retryWindowSeconds = 1, deadlineSeconds = 30).rollback()

        evidence.state shouldBeEqualTo SqsExtendedRollbackState.ROLLBACK_BLOCKED
        evidence.blockReason shouldBeEqualTo SqsExtendedRollbackBlockReason.REDRIVE_BUDGET_EXHAUSTED
        runtime.startCalls shouldBeEqualTo 0
    }

    private fun coordinator(
        runtime: FakeRollbackRuntime,
        start: Instant,
        retryWindowSeconds: Int,
        deadlineSeconds: Int,
    ): SqsExtendedRollbackCoordinator = SqsExtendedRollbackCoordinator(
        policy = SqsExtendedClientProperties.Policy(
            bucket = "bucket-${Base58.randomString(16)}",
            orphanRetentionHours = 1,
            configuredMaxVisibilityRetryWindowSeconds = retryWindowSeconds,
            rollbackDeadlineSeconds = deadlineSeconds,
        ),
        sourceQueueUrl = "https://sqs.us-east-1.amazonaws.com/123456789012/${Base58.randomString(16)}",
        destinationQueueUrl = "https://sqs.us-east-1.amazonaws.com/123456789012/${Base58.randomString(16)}",
        runtime = runtime,
        now = { start },
    )

    private fun probe(
        observedAt: Instant,
        pointerCount: Int = 0,
        receiveCount: Int = 0,
        maxReceiveCount: Int? = 10,
    ): SqsExtendedRollbackProbe = SqsExtendedRollbackProbe(
        observedAt = observedAt,
        pointerCount = pointerCount,
        inFlightCount = 0,
        approximateReceiveCount = receiveCount,
        maxReceiveCount = maxReceiveCount,
    )

    private class FakeRollbackRuntime(
        private val probes: ArrayDeque<SqsExtendedRollbackProbe>,
        private val rehydration: SqsExtendedRehydrationResult = SqsExtendedRehydrationResult(0, 0, 0, 0, true),
    ) : SqsExtendedRollbackRuntime {
        var startCalls = 0
        var rehydrateCalls = 0
        var deleteCalls = 0

        override fun disableProducer() = Unit

        override fun stopLegacyConsumer() = Unit

        override suspend fun drainExtended(): SqsExtendedDrainResult = SqsExtendedDrainResult(0, 0, false)

        override suspend fun probeExtendedQueue(): SqsExtendedRollbackProbe = probes.removeFirst()

        override suspend fun rehydrateQuarantine(): SqsExtendedRehydrationResult {
            rehydrateCalls++
            return rehydration
        }

        override fun startLegacyConsumer() {
            startCalls++
        }
    }
}
