package io.bluetape4k.aws.spring.sqs

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeGreaterOrEqualTo
import io.bluetape4k.assertions.shouldBeInstanceOf
import io.bluetape4k.assertions.shouldBeLessOrEqualTo
import io.bluetape4k.assertions.shouldBeSameInstanceAs
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldHaveSize
import io.bluetape4k.codec.Base58
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import software.amazon.awssdk.awscore.exception.AwsErrorDetails
import software.amazon.awssdk.core.exception.SdkClientException
import software.amazon.awssdk.services.sqs.model.SqsException
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

class SqsBatchCoordinatorTest {

    @Test
    fun `bounded snapshot covers count matrix and never reads beyond limit plus one`() = runTest {
        val maxInFlight = 2
        val maxEntries = 4

        listOf(0, 1, maxInFlight, maxInFlight + 1, maxEntries).forEach { count ->
            val transport = CoordinatorTestTransport()
            val coordinator = coordinator(transport, maxInFlight, maxEntries)
            val entries = List(count) { sendEntry("matrix-$count-$it") }

            val result = coordinator.sendMany(entries)

            result.successful shouldHaveSize count
            result.failed shouldHaveSize 0
            transport.sendEntries shouldHaveSize count
            if (count == 0) {
                with(coordinator.metrics()) {
                    peakActiveFutureCount shouldBeEqualTo 0
                    peakAcceptedEntryCount shouldBeEqualTo 0
                    peakResidentChildCount shouldBeEqualTo 0
                    peakPendingResultCount shouldBeEqualTo 0
                }
            }
        }

        val oversizedTransport = CoordinatorTestTransport()
        val oversizedCoordinator = coordinator(oversizedTransport, maxInFlight, maxEntries)
        val oversized = BoundedProbeCollection(
            values = List(maxEntries + 2) { sendEntry("oversized-$it") },
            maximumAllowedReads = maxEntries + 1,
        )

        assertFailsWith<IllegalArgumentException> {
            oversizedCoordinator.sendMany(oversized)
        }

        oversized.readCount shouldBeEqualTo maxEntries + 1
        oversizedTransport.sendEntries shouldHaveSize 0
        with(oversizedCoordinator.metrics()) {
            peakActiveFutureCount shouldBeEqualTo 0
            peakAcceptedEntryCount shouldBeEqualTo 0
            peakResidentChildCount shouldBeEqualTo 0
            peakPendingResultCount shouldBeEqualTo 0
        }

        val duplicateTransport = CoordinatorTestTransport()
        val duplicateCoordinator = coordinator(duplicateTransport, maxInFlight, maxEntries)
        val duplicateId = entryId("duplicate")
        assertFailsWith<IllegalArgumentException> {
            duplicateCoordinator.sendMany(
                listOf(sendEntry("first", duplicateId), sendEntry("second", duplicateId)),
            )
        }
        duplicateTransport.sendEntries shouldHaveSize 0
        with(duplicateCoordinator.metrics()) {
            peakActiveFutureCount shouldBeEqualTo 0
            peakAcceptedEntryCount shouldBeEqualTo 0
            peakResidentChildCount shouldBeEqualTo 0
            peakPendingResultCount shouldBeEqualTo 0
        }
    }

    @Test
    fun `admission snapshots mutable collection and message attributes before suspension`() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val hooks = object : SqsBatchCoordinatorHooks {
            override suspend fun afterPermitAcquired() {
                entered.complete(Unit)
                release.await()
            }
        }
        val transport = CoordinatorTestTransport()
        val coordinator = coordinator(transport, maxInFlight = 2, maxEntries = 4, hooks = hooks)
        val attributes = linkedMapOf("trace" to messageAttribute("before"))
        val first = sendEntry("snapshot-first", attributes = attributes)
        val second = sendEntry("snapshot-second")
        val entries = mutableListOf(first, second)
        val operation = async(start = CoroutineStart.UNDISPATCHED) { coordinator.sendMany(entries) }
        entered.await()

        attributes["trace"] = messageAttribute("after")
        entries.clear()
        entries += sendEntry("late")
        release.complete(Unit)

        val result = operation.await()
        result.successful.map { it.entryId } shouldBeEqualTo listOf(first.entryId, second.entryId)
        transport.sendEntries.map { it.entryId }.toSet() shouldBeEqualTo setOf(first.entryId, second.entryId)
        transport.sendEntries.first { it.entryId == first.entryId }
            .request.messageAttributes shouldBeEqualTo first.request.messageAttributes
    }

    @Test
    fun `global admission and per call windows bound active resident and pending results`() = runTest {
        val transport = CoordinatorTestTransport()
        val futures = List(3) { CoordinatorCountingFuture<SqsBatchOutcome>() }
        futures.forEach(transport::enqueueSend)
        val coordinator = coordinator(transport, maxInFlight = 2, maxEntries = 5)
        val entries = List(3) { sendEntry("window-$it") }
        val operation = async { coordinator.sendMany(entries) }
        runCurrent()

        transport.maxActive shouldBeLessOrEqualTo 2
        coordinator.metrics().acceptedEntryCount shouldBeLessOrEqualTo 2
        coordinator.metrics().residentChildCount shouldBeLessOrEqualTo 2
        transport.sendEntries shouldHaveSize 2

        futures[1].complete(sendSuccess(entries[1]))
        runCurrent()
        coordinator.metrics().pendingResultCount shouldBeEqualTo 1
        coordinator.metrics().peakPendingResultCount shouldBeLessOrEqualTo 2
        transport.sendEntries shouldHaveSize 2

        futures[0].complete(sendSuccess(entries[0]))
        runCurrent()
        transport.sendEntries.size shouldBeGreaterOrEqualTo 3
        futures[2].complete(sendSuccess(entries[2]))

        val result = operation.await()
        result.successful.map { it.entryId } shouldBeEqualTo entries.map { it.entryId }
        with(coordinator.metrics()) {
            activeFutureCount shouldBeEqualTo 0
            acceptedEntryCount shouldBeEqualTo 0
            residentChildCount shouldBeEqualTo 0
            pendingResultCount shouldBeEqualTo 0
            peakActiveFutureCount shouldBeLessOrEqualTo 2
            peakAcceptedEntryCount shouldBeLessOrEqualTo 2
            peakResidentChildCount shouldBeLessOrEqualTo 2
            peakPendingResultCount shouldBeLessOrEqualTo 2
        }
    }

    @Test
    fun `supervisor collection preserves partial outcomes order and failure strategy without retry`() = runTest {
        val transport = CoordinatorTestTransport()
        val groupId = "group-${Base58.randomString(16)}"
        val entries = List(3) { sendEntry("partial-$it", groupId = groupId) }
        repeat(2) {
            transport.enqueueSend(CompletableFuture.completedFuture(sendSuccess(entries[0])))
            transport.enqueueSend(
                CompletableFuture.completedFuture(
                    SqsBatchOutcome.Failure(
                        SqsBatchEntryFailure(entries[1].entryId, SqsBatchFailureKind.SERVICE, "ThrottlingException"),
                    ),
                ),
            )
            transport.enqueueSend(
                CompletableFuture.completedFuture(
                    SqsBatchOutcome.Failure(
                        SqsBatchEntryFailure(entries[2].entryId, SqsBatchFailureKind.TRANSPORT, null),
                    ),
                ),
            )
        }
        val coordinator = coordinator(transport, maxInFlight = 3, maxEntries = 6)

        val returned = coordinator.sendMany(entries, SendBatchFailureStrategy.RETURN)
        returned.successful.map { it.entryId } shouldBeEqualTo listOf(entries[0].entryId)
        returned.failed.map { it.entryId } shouldBeEqualTo listOf(entries[1].entryId, entries[2].entryId)

        val thrown = assertFailsWith<SqsSendBatchFailedException> {
            coordinator.sendMany(entries, SendBatchFailureStrategy.THROW)
        }
        thrown.result.status shouldBeEqualTo returned.status
        thrown.result.successful.map { it.entryId } shouldBeEqualTo returned.successful.map { it.entryId }
        thrown.result.failed shouldBeEqualTo returned.failed
        transport.sendEntries shouldHaveSize entries.size * 2
    }

    @Test
    fun `delete results and synchronous or already completed failures are normalized`() = runTest {
        val transport = CoordinatorTestTransport()
        val deletes = List(3) { deleteEntry("delete-$it") }
        transport.enqueueDelete(CompletableFuture.completedFuture(SqsBatchOutcome.DeleteSuccess(deletes[0].entryId)))
        transport.enqueueDelete(
            CompletableFuture.failedFuture(
                SqsException.builder()
                    .awsErrorDetails(AwsErrorDetails.builder().errorCode("AccessDenied").build())
                    .build(),
            ),
        )
        transport.enqueueDelete(
            CompletableFuture.failedFuture(SdkClientException.create("transport-${Base58.randomString(16)}")),
        )
        val coordinator = coordinator(transport, maxInFlight = 3, maxEntries = 4)

        val result = coordinator.deleteMany(deletes)

        result.successfulEntryIds shouldBeEqualTo listOf(deletes[0].entryId)
        result.failed shouldBeEqualTo listOf(
            SqsBatchEntryFailure(deletes[1].entryId, SqsBatchFailureKind.SERVICE, "AccessDenied"),
            SqsBatchEntryFailure(deletes[2].entryId, SqsBatchFailureKind.TRANSPORT, null),
        )

        val throwingTransport = CoordinatorTestTransport().apply {
            sendFailure = SdkClientException.create("submit-${Base58.randomString(16)}")
        }
        val throwingEntry = sendEntry("submit-throw")
        val throwingCoordinator = coordinator(throwingTransport, 1, 1)
        val throwingResult = throwingCoordinator.sendMany(listOf(throwingEntry))
        throwingResult.failed shouldBeEqualTo listOf(
            SqsBatchEntryFailure(throwingEntry.entryId, SqsBatchFailureKind.TRANSPORT, null),
        )
        with(throwingCoordinator.metrics()) {
            activeFutureCount shouldBeEqualTo 0
            acceptedEntryCount shouldBeEqualTo 0
            residentChildCount shouldBeEqualTo 0
            pendingResultCount shouldBeEqualTo 0
            availablePermits shouldBeEqualTo 1
        }
    }

    @Suppress("UNCHECKED_CAST")
    @Test
    fun `malformed null future outcome propagates invariant failure and releases resources`() = runTest {
        val malformed = CompletableFuture.completedFuture<SqsBatchOutcome?>(null) as CompletableFuture<SqsBatchOutcome>
        val transport = CoordinatorTestTransport().apply { enqueueSend(malformed) }
        val coordinator = coordinator(transport, maxInFlight = 1, maxEntries = 1)

        val failure = assertFailsWith<IllegalStateException> {
            withTimeout(100) {
                coordinator.sendMany(listOf(sendEntry("malformed")))
            }
        }

        failure.message shouldBeEqualTo "SQS batch transport completed without an outcome."
        with(coordinator.metrics()) {
            activeFutureCount shouldBeEqualTo 0
            acceptedEntryCount shouldBeEqualTo 0
            residentChildCount shouldBeEqualTo 0
            pendingResultCount shouldBeEqualTo 0
            availablePermits shouldBeEqualTo 1
        }
        val owner = coordinator.beginClose().shouldBeInstanceOf<SqsBatchCloseClaim.Owner>()
        owner.accepted shouldHaveSize 0
        coordinator.finishClose(SqsBatchCloseOutcome.Success)
        owner.completion.join() shouldBeSameInstanceAs SqsBatchCloseOutcome.Success
    }

    @Test
    fun `concurrent calls with the same public entry id use distinct internal tokens`() = runTest {
        val tokens = CopyOnWriteArrayList<Long>()
        val bothRegistered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val hooks = object : SqsBatchCoordinatorHooks {
            override suspend fun afterPlaceholderRegistered(token: Long) {
                tokens += token
                if (tokens.size == 2) {
                    bothRegistered.complete(Unit)
                }
                release.await()
            }
        }
        val transport = CoordinatorTestTransport()
        val coordinator = coordinator(transport, maxInFlight = 2, maxEntries = 2, hooks = hooks)
        val sharedId = entryId("shared")

        val operations = listOf(
            async { coordinator.sendMany(listOf(sendEntry("first", sharedId))) },
            async { coordinator.sendMany(listOf(sendEntry("second", sharedId))) },
        )
        bothRegistered.await()
        release.complete(Unit)
        val results = operations.awaitAll()

        results.forEach { it.successful.single().entryId shouldBeEqualTo sharedId }
        tokens shouldHaveSize 2
        tokens.toSet() shouldHaveSize 2
        coordinator.metrics().acceptedEntryCount shouldBeEqualTo 0
    }

    private fun coordinator(
        transport: SqsBatchTransport,
        maxInFlight: Int,
        maxEntries: Int,
        hooks: SqsBatchCoordinatorHooks = SqsBatchCoordinatorHooks.None,
    ): SqsBatchCoordinator = SqsBatchCoordinator(
        transport = transport,
        properties = SqsBatchProperties(
            enabled = false,
            maxBatchSize = 10,
            flushInterval = Duration.ofMillis(50),
            maxEntriesPerCall = maxEntries,
            maxInFlightEntries = maxInFlight,
            schedulerThreads = 1,
            shutdownTimeout = Duration.ofSeconds(1),
        ),
        hooks = hooks,
    )
}

internal class CoordinatorTestTransport : SqsBatchTransport {
    private val sendQueue = ConcurrentLinkedQueue<CompletableFuture<SqsBatchOutcome>>()
    private val deleteQueue = ConcurrentLinkedQueue<CompletableFuture<SqsBatchOutcome>>()
    private val active = AtomicInteger()
    private val peakActive = AtomicInteger()

    val sendEntries = CopyOnWriteArrayList<SqsBatchSendEntry>()
    val deleteEntries = CopyOnWriteArrayList<SqsBatchDeleteEntry>()
    var sendFailure: Throwable? = null
    var deleteFailure: Throwable? = null

    val maxActive: Int get() = peakActive.get()

    fun enqueueSend(future: CompletableFuture<SqsBatchOutcome>) {
        sendQueue += future
    }

    fun enqueueDelete(future: CompletableFuture<SqsBatchOutcome>) {
        deleteQueue += future
    }

    override fun send(entry: SqsBatchSendEntry): CompletableFuture<SqsBatchOutcome> {
        sendEntries += entry
        sendFailure?.let { throw it }
        return track(
            sendQueue.poll() ?: CompletableFuture.completedFuture(sendSuccess(entry)),
        )
    }

    override fun delete(entry: SqsBatchDeleteEntry): CompletableFuture<SqsBatchOutcome> {
        deleteEntries += entry
        deleteFailure?.let { throw it }
        return track(
            deleteQueue.poll() ?: CompletableFuture.completedFuture(SqsBatchOutcome.DeleteSuccess(entry.entryId)),
        )
    }

    private fun track(future: CompletableFuture<SqsBatchOutcome>): CompletableFuture<SqsBatchOutcome> {
        if (!future.isDone) {
            val current = active.incrementAndGet()
            peakActive.accumulateAndGet(current, ::maxOf)
            future.whenComplete { _, _ -> active.decrementAndGet() }
        }
        return future
    }
}

internal class CoordinatorCountingFuture<T> : CompletableFuture<T>() {
    val cancelCount = AtomicInteger()

    override fun cancel(mayInterruptIfRunning: Boolean): Boolean {
        cancelCount.incrementAndGet()
        return super.cancel(mayInterruptIfRunning)
    }
}

internal fun sendEntry(
    prefix: String,
    entryId: String = entryId(prefix),
    attributes: Map<String, software.amazon.awssdk.services.sqs.model.MessageAttributeValue> = emptyMap(),
    groupId: String? = null,
): SqsBatchSendEntry = SqsBatchSendEntry(
    entryId = entryId,
    request = SqsSendRequest(
        queueUrl = "https://sqs.local/${Base58.randomString(16)}",
        body = Base58.randomString(16),
        messageGroupId = groupId,
        messageDeduplicationId = groupId?.let { Base58.randomString(16) },
        messageAttributes = attributes,
    ),
)

internal fun deleteEntry(prefix: String): SqsBatchDeleteEntry = SqsBatchDeleteEntry(
    entryId = entryId(prefix),
    queueUrl = "https://sqs.local/${Base58.randomString(16)}",
    receiptHandle = Base58.randomString(16),
)

internal fun entryId(prefix: String): String = "$prefix-${Base58.randomString(16)}"

internal fun sendSuccess(entry: SqsBatchSendEntry): SqsBatchOutcome.SendSuccess = SqsBatchOutcome.SendSuccess(
    entryId = entry.entryId,
    messageId = "message-${Base58.randomString(16)}",
    sequenceNumber = entry.request.messageGroupId?.let { "sequence-${Base58.randomString(16)}" },
)

private fun messageAttribute(value: String) = software.amazon.awssdk.services.sqs.model.MessageAttributeValue.builder()
    .dataType("String")
    .stringValue(value)
    .build()

private class BoundedProbeCollection<T>(
    private val values: List<T>,
    private val maximumAllowedReads: Int,
) : AbstractCollection<T>() {
    private val observedReads = AtomicInteger()

    override val size: Int
        get() = error("size must not be read")

    val readCount: Int get() = observedReads.get()

    override fun iterator(): Iterator<T> = object : Iterator<T> {
        private var index = 0

        override fun hasNext(): Boolean = index < values.size

        override fun next(): T {
            if (!hasNext()) throw NoSuchElementException()
            val current = observedReads.incrementAndGet()
            check(current <= maximumAllowedReads) { "iterator read beyond the bounded probe" }
            return values[index++]
        }
    }
}
