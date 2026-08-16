package io.bluetape4k.aws.spring.sqs

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeInstanceOf
import io.bluetape4k.assertions.shouldBeSameInstanceAs
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldHaveSize
import io.bluetape4k.codec.Base58
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.CompletableFuture

class SqsBatchCoordinatorRaceTest {

    @Test
    fun `close claim has one owner shared completion and safe lifecycle rejection`() = runTest {
        val coordinator = coordinator(CoordinatorTestTransport(), maxInFlight = 1)

        val owner = coordinator.beginClose()
        val observer = coordinator.beginClose()

        owner.shouldBeOwner().accepted shouldHaveSize 0
        observer.shouldBeObserver().completion shouldBeSameInstanceAs owner.completion
        val lifecycle = assertFailsWith<IllegalStateException> {
            coordinator.sendMany(emptyList())
        }
        lifecycle.message shouldBeEqualTo "SQS batch coordinator is closing or closed."

        val closeError = SqsBatchCloseException(listOf(SqsBatchCleanupComponent.TIMEOUT))
        val outcome = SqsBatchCloseOutcome.Failure(closeError)
        coordinator.finishClose(outcome)

        owner.completion.join() shouldBeSameInstanceAs outcome
        owner.completion.join().shouldBeFailure().exception shouldBeSameInstanceAs closeError
        val closed = assertFailsWith<IllegalStateException> {
            coordinator.sendMany(emptyList())
        }
        closed.message shouldBeEqualTo "SQS batch coordinator is closing or closed."
        coordinator.beginClose().completion shouldBeSameInstanceAs owner.completion
    }

    @Test
    fun `close after permit acquisition rejects without placeholder or submit and releases permit`() = runTest {
        val permitReached = CompletableDeferred<Unit>()
        val releasePermit = CompletableDeferred<Unit>()
        val hooks = object : SqsBatchCoordinatorHooks {
            override suspend fun afterPermitAcquired() {
                permitReached.complete(Unit)
                releasePermit.await()
            }
        }
        val transport = CoordinatorTestTransport()
        val coordinator = coordinator(transport, maxInFlight = 1, hooks = hooks)
        val operation = async {
            assertFailsWith<IllegalStateException> {
                coordinator.sendMany(listOf(sendEntry("permit-close")))
            }
        }
        permitReached.await()

        val owner = coordinator.beginClose().shouldBeOwner()
        releasePermit.complete(Unit)

        operation.await().message shouldBeEqualTo "SQS batch coordinator is closing or closed."
        owner.accepted shouldHaveSize 0
        transport.sendEntries shouldHaveSize 0
        with(coordinator.metrics()) {
            acceptedEntryCount shouldBeEqualTo 0
            residentChildCount shouldBeEqualTo 0
            availablePermits shouldBeEqualTo 1
        }
        coordinator.finishClose(SqsBatchCloseOutcome.Success)
    }

    @Test
    fun `caller cancellation after placeholder registration preserves identity and skips submit`() = runTest {
        val placeholderReached = CompletableDeferred<Unit>()
        val hooks = object : SqsBatchCoordinatorHooks {
            override suspend fun afterPlaceholderRegistered(token: Long) {
                placeholderReached.complete(Unit)
                CompletableDeferred<Unit>().await()
            }
        }
        val transport = CoordinatorTestTransport()
        val coordinator = coordinator(transport, maxInFlight = 1, hooks = hooks)
        val operation = async { coordinator.sendMany(listOf(sendEntry("placeholder-cancel"))) }
        placeholderReached.await()
        val cancellation = CancellationException("caller-${Base58.randomString(16)}")

        operation.cancel(cancellation)
        val observed = assertFailsWith<CancellationException> { operation.await() }

        observed.rootCancellation() shouldBeSameInstanceAs cancellation
        transport.sendEntries shouldHaveSize 0
        with(coordinator.metrics()) {
            acceptedEntryCount shouldBeEqualTo 0
            residentChildCount shouldBeEqualTo 0
            availablePermits shouldBeEqualTo 1
        }
    }

    @Test
    fun `accepted before future handoff participates in close cancellation exactly once`() = runTest {
        val submitted = CompletableDeferred<Unit>()
        val releaseHandoff = CompletableDeferred<Unit>()
        val hooks = object : SqsBatchCoordinatorHooks {
            override suspend fun afterTransportSubmitted(
                token: Long,
                future: CompletableFuture<SqsBatchOutcome>,
            ) {
                submitted.complete(Unit)
                releaseHandoff.await()
            }
        }
        val future = CoordinatorCountingFuture<SqsBatchOutcome>()
        val transport = CoordinatorTestTransport().apply { enqueueSend(future) }
        val coordinator = coordinator(transport, maxInFlight = 1, hooks = hooks)
        val entry = sendEntry("handoff-close")
        val operation = async { coordinator.sendMany(listOf(entry)) }
        submitted.await()

        val owner = coordinator.beginClose().shouldBeOwner()
        owner.accepted shouldHaveSize 1
        owner.accepted.single().cancelIfIncomplete().shouldBeTrue()
        releaseHandoff.complete(Unit)

        val result = operation.await()
        result.failed shouldBeEqualTo listOf(
            SqsBatchEntryFailure(entry.entryId, SqsBatchFailureKind.TRANSPORT, null),
        )
        future.cancelCount.get() shouldBeEqualTo 1
        owner.accepted.single().completion.isDone.shouldBeTrue()
        coordinator.finishClose(SqsBatchCloseOutcome.Success)
    }

    @Test
    fun `SDK future cancellation while caller is active becomes transport outcome`() = runTest {
        val future = CoordinatorCountingFuture<SqsBatchOutcome>()
        val transport = CoordinatorTestTransport().apply { enqueueSend(future) }
        val coordinator = coordinator(transport, maxInFlight = 1)
        val entry = sendEntry("sdk-cancel")
        val operation = async { coordinator.sendMany(listOf(entry)) }
        runCurrent()
        transport.sendEntries shouldHaveSize 1

        future.cancel(false).shouldBeTrue()

        val result = operation.await()
        result.failed shouldBeEqualTo listOf(
            SqsBatchEntryFailure(entry.entryId, SqsBatchFailureKind.TRANSPORT, null),
        )
        operation.isCancelled.shouldBeFalse()
        future.cancelCount.get() shouldBeEqualTo 1
    }

    @Test
    fun `response then caller cancellation preserves root identity without cancelling completed future`() = runTest {
        val responseReached = CompletableDeferred<Unit>()
        val hooks = object : SqsBatchCoordinatorHooks {
            override suspend fun afterTransportResponse(token: Long) {
                responseReached.complete(Unit)
                CompletableDeferred<Unit>().await()
            }
        }
        val future = CoordinatorCountingFuture<SqsBatchOutcome>()
        val transport = CoordinatorTestTransport().apply { enqueueSend(future) }
        val coordinator = coordinator(transport, maxInFlight = 1, hooks = hooks)
        val entry = sendEntry("response-cancel")
        val operation = async { coordinator.sendMany(listOf(entry)) }
        runCurrent()
        transport.sendEntries shouldHaveSize 1
        future.complete(sendSuccess(entry)).shouldBeTrue()
        responseReached.await()
        val cancellation = CancellationException("response-${Base58.randomString(16)}")

        operation.cancel(cancellation)
        val observed = assertFailsWith<CancellationException> { operation.await() }

        observed.rootCancellation() shouldBeSameInstanceAs cancellation
        future.cancelCount.get() shouldBeEqualTo 0
        with(coordinator.metrics()) {
            acceptedEntryCount shouldBeEqualTo 0
            availablePermits shouldBeEqualTo 1
        }
    }

    @Test
    fun `close wakes permit waiter without second submit or orphan child`() = runTest {
        val firstFuture = CoordinatorCountingFuture<SqsBatchOutcome>()
        val transport = CoordinatorTestTransport().apply { enqueueSend(firstFuture) }
        val coordinator = coordinator(transport, maxInFlight = 1)
        val firstEntry = sendEntry("waiter-first")
        val first = async { coordinator.sendMany(listOf(firstEntry)) }
        runCurrent()
        transport.sendEntries shouldHaveSize 1
        val second = async {
            assertFailsWith<IllegalStateException> {
                coordinator.sendMany(listOf(sendEntry("waiter-second")))
            }
        }
        runCurrent()
        coordinator.metrics().residentChildCount shouldBeEqualTo 2

        val owner = coordinator.beginClose().shouldBeOwner()
        second.await().message shouldBeEqualTo "SQS batch coordinator is closing or closed."
        transport.sendEntries shouldHaveSize 1
        owner.accepted.single().cancelIfIncomplete().shouldBeTrue()

        first.await().failed shouldBeEqualTo listOf(
            SqsBatchEntryFailure(firstEntry.entryId, SqsBatchFailureKind.TRANSPORT, null),
        )
        owner.accepted.single().completion.isDone.shouldBeTrue()
        with(coordinator.metrics()) {
            acceptedEntryCount shouldBeEqualTo 0
            residentChildCount shouldBeEqualTo 0
            availablePermits shouldBeEqualTo 1
        }
        coordinator.finishClose(SqsBatchCloseOutcome.Success)
    }

    @Test
    fun `close timeout and caller cancellation share one future cancel guard`() = runTest {
        val attached = CompletableDeferred<Unit>()
        val hooks = object : SqsBatchCoordinatorHooks {
            override suspend fun afterFutureAttached(token: Long) {
                attached.complete(Unit)
                CompletableDeferred<Unit>().await()
            }
        }
        val future = CoordinatorCountingFuture<SqsBatchOutcome>()
        val transport = CoordinatorTestTransport().apply { enqueueSend(future) }
        val coordinator = coordinator(transport, maxInFlight = 1, hooks = hooks)
        val operation = async { coordinator.sendMany(listOf(sendEntry("timeout-cancel"))) }
        attached.await()
        val owner = coordinator.beginClose().shouldBeOwner()
        val cancellation = CancellationException("timeout-${Base58.randomString(16)}")
        val readyCount = java.util.concurrent.atomic.AtomicInteger()
        val bothReady = CompletableDeferred<Unit>()
        val startRace = CompletableDeferred<Unit>()

        val racers = listOf(
            async(Dispatchers.Default) {
                if (readyCount.incrementAndGet() == 2) bothReady.complete(Unit)
                startRace.await()
                owner.accepted.single().cancelIfIncomplete()
            },
            async(Dispatchers.Default) {
                if (readyCount.incrementAndGet() == 2) bothReady.complete(Unit)
                startRace.await()
                operation.cancel(cancellation)
            },
        )
        bothReady.await()
        startRace.complete(Unit)
        racers.awaitAll()
        val observed = assertFailsWith<CancellationException> { operation.await() }

        observed.rootCancellation() shouldBeSameInstanceAs cancellation
        future.cancelCount.get() shouldBeEqualTo 1
        owner.accepted.single().completion.isDone.shouldBeTrue()
        coordinator.finishClose(SqsBatchCloseOutcome.Success)
    }

    @Test
    fun `already cancelled caller wins over lifecycle rejection`() = runTest {
        val coordinator = coordinator(CoordinatorTestTransport(), maxInFlight = 1)
        coordinator.beginClose()
        val gate = CompletableDeferred<Unit>()
        val operation = async(start = CoroutineStart.UNDISPATCHED) {
            gate.await()
            coordinator.sendMany(emptyList())
        }
        val cancellation = CancellationException("priority-${Base58.randomString(16)}")

        operation.cancel(cancellation)
        gate.complete(Unit)
        val observed = assertFailsWith<CancellationException> { operation.await() }

        observed.rootCancellation() shouldBeSameInstanceAs cancellation
        coordinator.finishClose(SqsBatchCloseOutcome.Success)
    }

    private fun coordinator(
        transport: SqsBatchTransport,
        maxInFlight: Int,
        hooks: SqsBatchCoordinatorHooks = SqsBatchCoordinatorHooks.None,
    ): SqsBatchCoordinator = SqsBatchCoordinator(
        transport = transport,
        properties = SqsBatchProperties(
            enabled = false,
            maxBatchSize = 10,
            flushInterval = Duration.ofMillis(50),
            maxEntriesPerCall = 8,
            maxInFlightEntries = maxInFlight,
            schedulerThreads = 1,
            shutdownTimeout = Duration.ofSeconds(1),
        ),
        hooks = hooks,
    )
}

private fun SqsBatchCloseClaim.shouldBeOwner(): SqsBatchCloseClaim.Owner =
    shouldBeInstanceOf<SqsBatchCloseClaim.Owner>()

private fun SqsBatchCloseClaim.shouldBeObserver(): SqsBatchCloseClaim.Observer =
    shouldBeInstanceOf<SqsBatchCloseClaim.Observer>()

private fun SqsBatchCloseOutcome.shouldBeFailure(): SqsBatchCloseOutcome.Failure =
    shouldBeInstanceOf<SqsBatchCloseOutcome.Failure>()

private tailrec fun CancellationException.rootCancellation(): CancellationException {
    val parent = cause as? CancellationException
    return if (parent == null || parent === this) this else parent.rootCancellation()
}
