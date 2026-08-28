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
    customizers: List<SqsObservationContextCustomizer>,
    private val factory: SqsObservationFactory,
    contextSnapshotFactory: ContextSnapshotFactory? = null,
) {
    private val customizers: List<SqsObservationContextCustomizer> = orderedSqsObservationCustomizers(customizers)
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
    ): T = prepare(context).observe(block = block)

    internal fun prepare(context: SqsObservationContext): SqsPreparedObservation {
        val observation = prepareSqsObservation(context, registry, customizers, factory)
        return SqsPreparedObservation(this, context, observation)
    }

    @Suppress("TooGenericExceptionCaught")
    internal suspend fun <T> observePrepared(
        context: SqsObservationContext,
        observation: Observation,
        onSetupFailure: ((Throwable) -> Unit)?,
        onCleanupFailure: ((Throwable) -> Unit)?,
        block: suspend SqsObservationExecution.() -> T,
    ): T {
        val execution = SqsObservationExecution(context, observation)
        val parentScope = registry.currentObservationScope
        var started = false
        var blockStarted = false
        val observedResult = try {
            observation.start()
            started = true
            val snapshot = try {
                observation.openScope().use {
                    requireSqsObservationRegistryBinding(registry, observation)
                    snapshotFactory.captureAll()
                }
            } catch (e: Throwable) {
                registry.currentObservationScope = parentScope
                throw e
            }
            runObservedBlock(snapshot, execution, context) {
                blockStarted = true
                block()
            }
        } catch (e: CancellationException) {
            context.outcome = SqsObservationOutcome.CANCELLED
            context.failureStage = context.failureStage ?: "observation"
            SqsObservedResult.Failure(e)
        } catch (e: Error) {
            throw e
        } catch (e: Throwable) {
            if (!blockStarted) {
                onSetupFailure?.invoke(e)
            }
            context.outcome = SqsObservationOutcome.ERROR
            context.failureStage = context.failureStage ?: "observation"
            SqsObservedResult.Failure(e)
        }

        val cleanupFailure = if (started) {
            withContext(NonCancellable) {
                cleanupObservation(observation, context, observedResult.failureOrNull())
            }
        } else {
            null
        }
        val unhandledCleanupFailure = if (cleanupFailure != null && onCleanupFailure != null) {
            onCleanupFailure(cleanupFailure)
            null
        } else {
            cleanupFailure
        }
        return observedResult.resolve(unhandledCleanupFailure)
    }
}

internal class SqsPreparedObservation internal constructor(
    private val runtime: SqsObservationRuntime?,
    private val context: SqsObservationContext?,
    private val observation: Observation,
) {
    internal suspend fun <T> observe(
        onSetupFailure: ((Throwable) -> Unit)? = null,
        onCleanupFailure: ((Throwable) -> Unit)? = null,
        block: suspend SqsObservationExecution.() -> T,
    ): T = when {
        runtime == null || context == null -> SqsObservationExecution(null, Observation.NOOP).block()
        observation === Observation.NOOP -> SqsObservationExecution(context, observation).block()
        else -> runtime.observePrepared(context, observation, onSetupFailure, onCleanupFailure, block)
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
        val currentContext = observationContext ?: return
        currentContext.currentAttempt = attempt
        currentContext.retryCount++
        currentContext.outcome = SqsObservationOutcome.RETRIED
        currentContext.failureStage = null
        if (!retryEventEmitted) {
            observation.event(Observation.Event.of("retry"))
            retryEventEmitted = true
        }
    }

    fun partial() {
        observationContext?.apply {
            outcome = SqsObservationOutcome.PARTIAL
            failureStage = null
        }
    }

    fun fail(stage: String) {
        observationContext?.apply {
            failureStage = stage
            outcome = SqsObservationOutcome.ERROR
        }
    }

    fun cancel(stage: String) {
        observationContext?.apply {
            failureStage = stage
            outcome = SqsObservationOutcome.CANCELLED
        }
    }
}

private val DISABLED_SQS_OBSERVATION_EXECUTION = SqsObservationExecution(null, Observation.NOOP)

internal suspend fun <T> observeSqs(
    runtime: SqsObservationRuntime?,
    contextFactory: () -> SqsObservationContext,
    block: suspend SqsObservationExecution.() -> T,
): T {
    if (runtime == null || runtime.registry === ObservationRegistry.NOOP) {
        return DISABLED_SQS_OBSERVATION_EXECUTION.block()
    }
    return runtime.observe(contextFactory(), block)
}

internal fun prepareSqsObservation(
    runtime: SqsObservationRuntime?,
    contextFactory: () -> SqsObservationContext,
): SqsPreparedObservation {
    if (runtime == null || runtime.registry === ObservationRegistry.NOOP) {
        return SqsPreparedObservation(null, null, Observation.NOOP)
    }
    return runtime.prepare(contextFactory())
}

internal fun SqsObservationRuntime?.activeOrNull(): SqsObservationRuntime? =
    this?.takeUnless { it.registry === ObservationRegistry.NOOP }

@Suppress("TooGenericExceptionCaught")
private fun cleanupObservation(
    observation: Observation,
    context: SqsObservationContext,
    primaryFailure: Throwable?,
): Throwable? {
    var cleanupFailure: Throwable? = null
    if (
        primaryFailure != null ||
        context.outcome == SqsObservationOutcome.ERROR ||
        context.outcome == SqsObservationOutcome.CANCELLED
    ) {
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
