package io.bluetape4k.aws.spring.sqs

import io.micrometer.context.ContextRegistry
import io.micrometer.context.ContextSnapshot
import io.micrometer.context.ContextSnapshotFactory
import io.micrometer.observation.Observation
import io.micrometer.observation.ObservationRegistry
import io.micrometer.observation.contextpropagation.ObservationThreadLocalAccessor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ThreadContextElement
import kotlinx.coroutines.withContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

internal class SqsObservationRuntime(
    internal val registry: ObservationRegistry,
    private val customizers: List<SqsObservationContextCustomizer>,
    private val factory: SqsObservationFactory,
    contextSnapshotFactory: ContextSnapshotFactory? = null,
) {
    private val snapshotFactory: ContextSnapshotFactory = contextSnapshotFactory ?: run {
        val contextRegistry = ContextRegistry()
            .registerThreadLocalAccessor(ObservationThreadLocalAccessor(registry))
        ContextSnapshotFactory.builder()
            .contextRegistry(contextRegistry)
            .build()
    }

    @Suppress("TooGenericExceptionCaught")
    internal suspend fun <T> observe(
        context: SqsObservationContext,
        block: suspend SqsObservationExecution.() -> T,
    ): T {
        val observation = prepareSqsObservation(context, registry, customizers, factory)
        val execution = SqsObservationExecution(context, observation)
        if (observation === Observation.NOOP) {
            return execution.block()
        }

        val parentScope = registry.currentObservationScope
        var started = false
        val observedResult = try {
            observation.start()
            started = true
            val snapshot = observation.openScope().use {
                requireSqsObservationRegistryBinding(registry, observation)
                snapshotFactory.captureAll()
            }
            runObservedBlock(snapshot, execution, context, block)
        } catch (e: CancellationException) {
            context.outcome = SqsObservationOutcome.CANCELLED
            context.failureStage = context.failureStage ?: "observation"
            SqsObservedResult.Failure(e)
        } catch (e: Throwable) {
            context.outcome = SqsObservationOutcome.ERROR
            context.failureStage = context.failureStage ?: "observation"
            SqsObservedResult.Failure(e)
        }

        val cleanupFailure = try {
            if (started) {
                withContext(NonCancellable) {
                    cleanupObservation(observation, context, observedResult.failureOrNull())
                }
            } else {
                null
            }
        } finally {
            registry.currentObservationScope = parentScope
        }
        return observedResult.resolve(cleanupFailure)
    }
}

@Suppress("TooGenericExceptionCaught")
private suspend fun <T> runObservedBlock(
    snapshot: ContextSnapshot,
    execution: SqsObservationExecution,
    context: SqsObservationContext,
    block: suspend SqsObservationExecution.() -> T,
): SqsObservedResult<T> = withContext(snapshot.asContextElement()) {
    try {
        val result = execution.block()
        if (context.outcome == SqsObservationOutcome.UNKNOWN) {
            context.outcome = SqsObservationOutcome.SUCCESS
        }
        SqsObservedResult.Success(result)
    } catch (e: CancellationException) {
        context.outcome = SqsObservationOutcome.CANCELLED
        context.failureStage = context.failureStage ?: "handler"
        SqsObservedResult.Failure(e)
    } catch (e: Throwable) {
        context.outcome = SqsObservationOutcome.ERROR
        context.failureStage = context.failureStage ?: "handler"
        SqsObservedResult.Failure(e)
    }
}

private sealed interface SqsObservedResult<out T> {
    data class Success<T>(val value: T) : SqsObservedResult<T>
    data class Failure(val throwable: Throwable) : SqsObservedResult<Nothing>
}

private fun SqsObservedResult<*>.failureOrNull(): Throwable? =
    (this as? SqsObservedResult.Failure)?.throwable

private fun <T> SqsObservedResult<T>.resolve(cleanupFailure: Throwable?): T = when (this) {
    is SqsObservedResult.Success -> {
        cleanupFailure?.let { throw it }
        value
    }

    is SqsObservedResult.Failure -> {
        cleanupFailure?.takeUnless { it === throwable }?.let(throwable::addSuppressed)
        throw throwable
    }
}

internal class SqsObservationExecution internal constructor(
    private val observationContext: SqsObservationContext?,
    val observation: Observation,
) {
    private var retryEventEmitted: Boolean = false

    val context: SqsObservationContext
        get() = checkNotNull(observationContext) { "No observation context exists on the direct path." }

    fun retry(attempt: Int) {
        require(attempt >= 1) { "attempt must be greater than or equal to 1." }
        context.currentAttempt = attempt
        context.retryCount++
        context.outcome = SqsObservationOutcome.RETRIED
        if (!retryEventEmitted) {
            observation.event(Observation.Event.of("retry"))
            retryEventEmitted = true
        }
    }
}

internal suspend fun <T> observeSqs(
    runtime: SqsObservationRuntime?,
    contextFactory: () -> SqsObservationContext,
    block: suspend SqsObservationExecution.() -> T,
): T {
    if (runtime == null || runtime.registry === ObservationRegistry.NOOP) {
        return SqsObservationExecution(null, Observation.NOOP).block()
    }
    return runtime.observe(contextFactory(), block)
}

@Suppress("TooGenericExceptionCaught")
private fun cleanupObservation(
    observation: Observation,
    context: SqsObservationContext,
    primaryFailure: Throwable?,
): Throwable? {
    var cleanupFailure: Throwable? = null
    if (primaryFailure != null) {
        try {
            observation.error(SqsObservationTelemetryException(context.failureStage ?: "observation"))
        } catch (e: Throwable) {
            cleanupFailure = e
        }
    }
    try {
        observation.stop()
    } catch (e: Throwable) {
        cleanupFailure = mergeSqsObservationCleanupFailure(cleanupFailure, e)
    }
    return cleanupFailure
}

internal fun mergeSqsObservationCleanupFailure(
    current: Throwable?,
    next: Throwable,
): Throwable = when {
    current == null -> next
    current === next -> current
    else -> current.apply { addSuppressed(next) }
}

private fun ContextSnapshot.asContextElement(): CoroutineContext = ContextSnapshotElement(this)

private class ContextSnapshotElement(
    private val snapshot: ContextSnapshot,
) : ThreadContextElement<ContextSnapshot.Scope?>,
    AbstractCoroutineContextElement(Key) {

    override fun updateThreadContext(context: CoroutineContext): ContextSnapshot.Scope =
        snapshot.setThreadLocals()

    override fun restoreThreadContext(
        context: CoroutineContext,
        oldState: ContextSnapshot.Scope?,
    ) {
        oldState?.close()
    }

    private companion object Key : CoroutineContext.Key<ContextSnapshotElement>
}
