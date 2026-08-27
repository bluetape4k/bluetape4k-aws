package io.bluetape4k.aws.spring.modulith

import io.bluetape4k.logging.KLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import org.springframework.modulith.events.RoutingTarget
import org.springframework.modulith.events.support.EventExternalizationTransport
import java.time.Duration
import java.util.LinkedHashMap
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * 실제 AWS publish 완료에 Spring Modulith publication future를 결박하는 bounded transport입니다.
 *
 * 이 transport는 자기 coroutine scope만 닫으며 publisher, operations, AWS client의 lifecycle은
 * application과 Spring context가 계속 소유합니다.
 */
@Suppress("TooManyFunctions", "TooGenericExceptionCaught")
internal class AwsModulithEventExternalizationTransport(
    targets: Map<String, AwsModulithEventsProperties.Target>,
    private val codec: AwsModulithEventCodec,
    publishers: Map<AwsModulithTargetService, AwsModulithTargetPublisher>,
    maxInFlight: Int,
    private val shutdownTimeout: Duration,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val futureFactory: () -> CompletableFuture<AwsModulithPublishResult> = { CompletableFuture() },
    private val beforeJobStart: (() -> Unit)? = null,
) : EventExternalizationTransport, AutoCloseable {

    companion object : KLogging()

    private val targets = targets.toMap()
    private val publishers = publishers.toMap()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val permits = Semaphore(maxInFlight)
    private val lifecycleLock = ReentrantLock()
    private val accepted = LinkedHashMap<Long, AcceptedPublication>()
    private val tokenSequence = AtomicLong()
    private val closeCompletion = CompletableFuture<Unit>()
    private var state = LifecycleState.OPEN

    init {
        require(maxInFlight > 0) { "maxInFlight must be positive." }
        require(!shutdownTimeout.isZero && !shutdownTimeout.isNegative) {
            "shutdownTimeout must be positive."
        }
    }

    override fun externalize(payload: Any, target: RoutingTarget): CompletableFuture<*> {
        val admission = lifecycleLock.withLock { admitLocked(payload, target) }
        admission.job?.let { job ->
            beforeJobStart?.invoke()
            job.start()
        }
        return admission.future
    }

    private fun admitLocked(payload: Any, target: RoutingTarget): Admission = when {
        state != LifecycleState.OPEN -> Admission(lifecycleFailure())
        !permits.tryAcquire() -> Admission(failedFuture(AwsModulithProducerCapacityException()))
        else -> createPublication(payload, target)
    }

    private fun createPublication(payload: Any, target: RoutingTarget): Admission {
        val token = tokenSequence.incrementAndGet()
        val operation = AcceptedPublication(token)
        accepted[token] = operation
        operation.future.whenComplete { _, _ ->
            if (operation.future.isCancelled) {
                operation.cancelChild()
            }
        }
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val result = publish(payload, target)
                operation.future.complete(result)
            } catch (cancellation: CancellationException) {
                operation.future.completeExceptionally(cancellation)
                throw cancellation
            } catch (error: Error) {
                operation.future.completeExceptionally(error)
            } catch (error: Exception) {
                logPublishFailure(target.target)
                operation.future.completeExceptionally(sanitizeAwsModulithPublishFailure(error))
            }
        }
        operation.attach(job)
        job.invokeOnCompletion { failure ->
            if (failure != null && !operation.future.isDone) {
                operation.future.completeExceptionally(failure)
            }
            finish(operation)
        }
        return Admission(operation.future, job)
    }

    override fun close() {
        val claim = lifecycleLock.withLock {
            when (state) {
                LifecycleState.OPEN -> {
                    state = LifecycleState.CLOSING
                    CloseClaim.Owner(accepted.values.toList())
                }

                LifecycleState.CLOSING, LifecycleState.CLOSED -> CloseClaim.Observer
            }
        }
        if (claim is CloseClaim.Owner) {
            closeOwned(claim.snapshot)
        }
        closeCompletion.join()
    }

    internal fun metrics(): AwsModulithTransportMetrics = lifecycleLock.withLock {
        AwsModulithTransportMetrics(
            acceptedCount = accepted.size,
            residentChildCount = accepted.values.count(AcceptedPublication::hasResidentChild),
            availablePermits = permits.availablePermits(),
            closing = state != LifecycleState.OPEN,
        )
    }

    internal suspend fun awaitIdle() {
        while (lifecycleLock.withLock { accepted.isNotEmpty() }) {
            kotlinx.coroutines.yield()
        }
    }

    private suspend fun publish(payload: Any, target: RoutingTarget): AwsModulithPublishResult {
        currentCoroutineContext().ensureActive()
        val targetAlias = target.target
        val configured = requireTarget(targetAlias)
        val routingKey = validateAwsModulithRoutingKey(configured.destination, target.key)
        val encoded = codec.encode(payload)
        val eventId = requireEncodedEventId(encoded)
        val publisher = requirePublisher(configured.service)
        currentCoroutineContext().ensureActive()
        return publisher.publish(
            AwsModulithPublishCommand(
                targetAlias = targetAlias,
                destination = configured.destination,
                routingKey = routingKey,
                eventId = eventId,
                encoded = encoded,
            )
        )
    }

    private fun requireTarget(targetAlias: String): AwsModulithEventsProperties.Target =
        targets[targetAlias] ?: throw AwsModulithConfigurationException()

    private fun requireEncodedEventId(encoded: AwsModulithEncodedEvent): String =
        encoded.messageAttributes[DefaultAwsModulithEventCodec.SYSTEM_EVENT_ID]
            ?: throw AwsModulithOutboundEnvelopeException()

    private fun requirePublisher(service: AwsModulithTargetService): AwsModulithTargetPublisher =
        publishers[service] ?: throw AwsModulithConfigurationException()

    private fun logPublishFailure(targetAlias: String) {
        val loggedAlias = targetAlias.takeIf(targets::containsKey) ?: "<unconfigured>"
        log.warn(
            "AWS Modulith outbound publish failed " +
                "(code=PUBLISH_FAILED, phase=publish, targetAlias=$loggedAlias)."
        )
    }

    private fun closeOwned(snapshot: List<AcceptedPublication>) {
        log.info("AWS Modulith outbound transport close started (acceptedCount=${snapshot.size}).")
        try {
            awaitSnapshot(snapshot)
        } catch (_: TimeoutException) {
            snapshot.forEach { operation ->
                operation.future.cancel(false)
                operation.cancelChild()
            }
        } finally {
            scope.cancel()
            lifecycleLock.withLock { state = LifecycleState.CLOSED }
            closeCompletion.complete(Unit)
            log.info("AWS Modulith outbound transport close completed.")
        }
    }

    private fun awaitSnapshot(snapshot: List<AcceptedPublication>) {
        if (snapshot.isEmpty()) return
        try {
            CompletableFuture.allOf(*snapshot.map { it.completion }.toTypedArray())
                .get(shutdownTimeout.toMillis().coerceAtLeast(1), TimeUnit.MILLISECONDS)
        } catch (timeout: TimeoutException) {
            throw timeout
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            throw TimeoutException()
        }
    }

    private fun finish(operation: AcceptedPublication) {
        if (!operation.finished.compareAndSet(false, true)) return
        lifecycleLock.withLock {
            accepted.remove(operation.token, operation)
        }
        permits.release()
        operation.completion.complete(Unit)
    }

    private fun lifecycleFailure(): CompletableFuture<AwsModulithPublishResult> =
        failedFuture(AwsModulithProducerClosedException())

    private fun failedFuture(error: Throwable): CompletableFuture<AwsModulithPublishResult> =
        CompletableFuture<AwsModulithPublishResult>().also { it.completeExceptionally(error) }

    private inner class AcceptedPublication(val token: Long) {
        val future = futureFactory()
        val completion = CompletableFuture<Unit>()
        val finished = AtomicBoolean()
        private val child = AtomicReference<Job?>()
        private val cancellationRequested = AtomicBoolean()

        fun attach(job: Job) {
            check(child.compareAndSet(null, job)) { "publication child is already attached." }
            if (cancellationRequested.get()) {
                job.cancel()
            }
        }

        fun cancelChild() {
            cancellationRequested.set(true)
            child.get()?.cancel()
        }

        fun hasResidentChild(): Boolean = child.get()?.isCompleted == false
    }

    private data class Admission(
        val future: CompletableFuture<AwsModulithPublishResult>,
        val job: Job? = null,
    )

    private sealed interface CloseClaim {
        class Owner(val snapshot: List<AcceptedPublication>) : CloseClaim
        data object Observer : CloseClaim
    }

    private enum class LifecycleState {
        OPEN,
        CLOSING,
        CLOSED,
    }
}

/** deterministic lifecycle tests에만 노출하는 bounded transport 상태입니다. */
internal data class AwsModulithTransportMetrics(
    val acceptedCount: Int,
    val residentChildCount: Int,
    val availablePermits: Int,
    val closing: Boolean,
)
