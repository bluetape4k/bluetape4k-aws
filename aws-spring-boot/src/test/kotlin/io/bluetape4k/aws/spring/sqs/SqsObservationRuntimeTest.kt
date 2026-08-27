package io.bluetape4k.aws.spring.sqs

import io.micrometer.context.ContextSnapshotFactory
import io.micrometer.observation.Observation
import io.micrometer.observation.ObservationHandler
import io.micrometer.observation.ObservationRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy

@OptIn(ExperimentalCoroutinesApi::class)
class SqsObservationRuntimeTest {

    @Test
    fun `process observation survives suspension and restores its parent`() = runTest {
        val registry = ObservationRegistry.create()
        val handler = RecordingHandler()
        registry.observationConfig().observationHandler(handler)
        val parent = Observation.start("parent", registry)
        val runtime = runtime(registry)

        parent.openScope().use {
            val result = observeSqs(runtime, ::processContext) {
                assertSame(observation, registry.currentObservation)
                withContext(StandardTestDispatcher(testScheduler)) {
                    assertSame(observation, registry.currentObservation)
                }
                "done"
            }

            assertEquals("done", result)
            assertSame(parent, registry.currentObservation)
        }
        parent.stop()

        assertEquals(2, handler.starts)
        assertEquals(2, handler.stops)
        assertSame(parent, handler.processParent)
    }

    @Test
    fun `child observation is linked below the process observation`() = runTest {
        val registry = ObservationRegistry.create()
        registry.observationConfig().observationHandler(RecordingHandler())
        val runtime = runtime(registry)

        observeSqs(runtime, ::processContext) {
            val child = Observation.start("child", registry)
            try {
                assertSame(observation, child.context.parentObservation)
            } finally {
                child.stop()
            }
        }
    }

    @Test
    fun `business error is rethrown while telemetry sees only a redacted error`() = runTest {
        val registry = ObservationRegistry.create()
        val handler = RecordingHandler()
        registry.observationConfig().observationHandler(handler)
        val runtime = runtime(registry)
        val failure = IllegalStateException("secret-body")

        val actual = runCatching {
            observeSqs(runtime, ::processContext) { throw failure }
        }.exceptionOrNull()

        assertSame(failure, actual)
        assertEquals(1, handler.starts)
        assertEquals(1, handler.stops)
        assertTrue(handler.error is SqsObservationTelemetryException)
        assertNull(handler.error?.message)
        assertTrue(handler.error?.stackTrace.orEmpty().isEmpty())
        assertNull(registry.currentObservation)
    }

    @Test
    fun `cancellation keeps identity and completes observation cleanup`() = runTest {
        val registry = ObservationRegistry.create()
        val handler = RecordingHandler()
        registry.observationConfig().observationHandler(handler)
        val runtime = runtime(registry)
        val cancellation = CancellationException("secret-cancellation")

        val actual = runCatching {
            observeSqs(runtime, ::processContext) { throw cancellation }
        }.exceptionOrNull()

        assertSame(cancellation, actual)
        assertEquals(1, handler.stops)
        assertTrue(handler.error is SqsObservationTelemetryException)
        assertNull(handler.error?.message)
        assertNull(registry.currentObservation)
    }

    @Test
    fun `retry updates every attempt but emits one event`() = runTest {
        val registry = ObservationRegistry.create()
        val handler = RecordingHandler()
        registry.observationConfig().observationHandler(handler)
        val runtime = runtime(registry)

        observeSqs(runtime, ::processContext) {
            retry(2)
            retry(3)
            assertEquals(3, context.attempt)
        }

        assertEquals(1, handler.events)
        assertEquals(SqsObservationOutcome.RETRIED, handler.processOutcome)
    }

    @Test
    fun `null runtime and noop registry do not create context or invoke extensions`() = runTest {
        var contexts = 0
        var customizers = 0
        var factories = 0
        val noopRuntime = SqsObservationRuntime(
            registry = ObservationRegistry.NOOP,
            customizers = listOf(SqsObservationContextCustomizer { customizers++ }),
            factory = SqsObservationFactory { _, _ -> factories++; Observation.NOOP },
        )

        assertEquals("null", observeSqs(null, { contexts++; processContext() }) { "null" })
        assertEquals("noop", observeSqs(noopRuntime, { contexts++; processContext() }) { "noop" })

        assertEquals(0, contexts)
        assertEquals(0, customizers)
        assertEquals(0, factories)
    }

    @Test
    fun `noop factory runs context and extensions once without observation lifecycle`() = runTest {
        val registry = ObservationRegistry.create()
        var contexts = 0
        var customizers = 0
        var factories = 0
        val runtime = SqsObservationRuntime(
            registry = registry,
            customizers = listOf(SqsObservationContextCustomizer { customizers++ }),
            factory = SqsObservationFactory { _, _ -> factories++; Observation.NOOP },
            contextSnapshotFactory = throwingSnapshotFactory(AssertionError("capture must not run")),
        )

        val result = observeSqs(runtime, { contexts++; processContext() }) {
            retry(2)
            "direct"
        }

        assertEquals("direct", result)
        assertEquals(1, contexts)
        assertEquals(1, customizers)
        assertEquals(1, factories)
        assertNull(registry.currentObservation)
    }

    @Test
    fun `customizer and factory failures preserve identity without starting an observation`() = runTest {
        val registry = ObservationRegistry.create()
        val customizerFailure = IllegalArgumentException("customizer")
        val factoryFailure = IllegalStateException("factory")
        var factoryCalls = 0
        val customizerRuntime = SqsObservationRuntime(
            registry = registry,
            customizers = listOf(SqsObservationContextCustomizer { throw customizerFailure }),
            factory = SqsObservationFactory { _, _ -> factoryCalls++; Observation.NOOP },
        )
        val factoryRuntime = SqsObservationRuntime(
            registry = registry,
            customizers = emptyList(),
            factory = SqsObservationFactory { _, _ -> throw factoryFailure },
        )

        assertSame(customizerFailure, failureOf { observeSqs(customizerRuntime, ::processContext) {} })
        assertEquals(0, factoryCalls)
        assertNull(registry.currentObservation)
        assertSame(factoryFailure, failureOf { observeSqs(factoryRuntime, ::processContext) {} })
        assertNull(registry.currentObservation)
    }

    @Test
    fun `start failure is primary and does not stop an observation that never started`() = runTest {
        val registry = ObservationRegistry.create()
        val startFailure = IllegalStateException("start")
        val handler = FailingHandler(startFailure = startFailure)
        registry.observationConfig().observationHandler(handler)

        val actual = failureOf { observeSqs(runtime(registry), ::processContext) {} }

        assertSame(startFailure, actual)
        assertEquals(1, handler.starts)
        assertEquals(0, handler.stops)
        assertNull(registry.currentObservation)
    }

    @Test
    fun `scope failure stops once and restores the original parent`() = runTest {
        val registry = ObservationRegistry.create()
        val scopeFailure = IllegalStateException("scope")
        val handler = FailingHandler(scopeFailure = scopeFailure)
        registry.observationConfig().observationHandler(handler)
        val parent = Observation.start("parent", registry)

        parent.openScope().use {
            val actual = failureOf { observeSqs(runtime(registry), ::processContext) {} }

            assertSame(scopeFailure, actual)
            assertSame(parent, registry.currentObservation)
        }
        parent.stop()

        assertEquals(2, handler.starts)
        assertEquals(2, handler.stops)
    }

    @Test
    fun `capture failure stops once and restores the original parent`() = runTest {
        val registry = ObservationRegistry.create()
        val captureFailure = IllegalStateException("capture")
        val handler = RecordingHandler()
        registry.observationConfig().observationHandler(handler)
        val parent = Observation.start("parent", registry)
        val runtime = SqsObservationRuntime(
            registry = registry,
            customizers = emptyList(),
            factory = defaultSqsObservationFactory(defaultSqsObservationConventions()),
            contextSnapshotFactory = throwingSnapshotFactory(captureFailure),
        )

        parent.openScope().use {
            val actual = failureOf { observeSqs(runtime, ::processContext) {} }

            assertSame(captureFailure, actual)
            assertSame(parent, registry.currentObservation)
        }
        parent.stop()

        assertEquals(2, handler.starts)
        assertEquals(2, handler.stops)
    }

    @Test
    fun `business failure stays primary while error and stop failures keep cleanup order`() = runTest {
        val registry = ObservationRegistry.create()
        val businessFailure = IllegalStateException("business")
        val errorFailure = IllegalArgumentException("error")
        val stopFailure = UnsupportedOperationException("stop")
        registry.observationConfig().observationHandler(
            FailingHandler(errorFailure = errorFailure, stopFailure = stopFailure),
        )

        val actual = failureOf {
            observeSqs(runtime(registry), ::processContext) { throw businessFailure }
        }

        assertSame(businessFailure, actual)
        assertSame(errorFailure, actual.suppressed.single())
        assertSame(stopFailure, errorFailure.suppressed.single())
        assertNull(registry.currentObservation)
    }

    @Test
    fun `stop failure becomes primary after successful business work`() = runTest {
        val registry = ObservationRegistry.create()
        val stopFailure = IllegalStateException("stop")
        registry.observationConfig().observationHandler(FailingHandler(stopFailure = stopFailure))

        val actual = failureOf { observeSqs(runtime(registry), ::processContext) { "done" } }

        assertSame(stopFailure, actual)
        assertNull(registry.currentObservation)
    }

    private fun runtime(registry: ObservationRegistry): SqsObservationRuntime =
        SqsObservationRuntime(
            registry = registry,
            customizers = emptyList(),
            factory = defaultSqsObservationFactory(defaultSqsObservationConventions()),
        )

    private fun processContext(): SqsObservationContext = SqsObservationContext(
        SqsObservationMetadata(
            listenerId = "listener-1",
            queueName = "orders",
            stage = SqsObservationStage.PROCESS,
            batch = false,
            initialAttempt = 1,
        ),
    )

    private suspend fun failureOf(block: suspend () -> Any?): Throwable =
        checkNotNull(runCatching { block() }.exceptionOrNull()) { "Expected the block to fail." }

    private fun throwingSnapshotFactory(failure: Throwable): ContextSnapshotFactory =
        Proxy.newProxyInstance(
            ContextSnapshotFactory::class.java.classLoader,
            arrayOf(ContextSnapshotFactory::class.java),
        ) { _, method, _ ->
            if (method.name == "captureAll") {
                throw failure
            }
            throw UnsupportedOperationException(method.name)
        } as ContextSnapshotFactory

    private class RecordingHandler : ObservationHandler<Observation.Context> {
        var starts: Int = 0
        var stops: Int = 0
        var events: Int = 0
        var error: Throwable? = null
        var processParent: io.micrometer.observation.ObservationView? = null
        var processOutcome: SqsObservationOutcome? = null

        override fun supportsContext(context: Observation.Context): Boolean = true

        override fun onStart(context: Observation.Context) {
            starts++
            if (context is SqsObservationContext) {
                processParent = context.parentObservation
            }
        }

        override fun onError(context: Observation.Context) {
            if (context is SqsObservationContext) {
                error = context.error
            }
        }

        override fun onEvent(event: Observation.Event, context: Observation.Context) {
            if (context is SqsObservationContext && event.name == "retry") {
                events++
            }
        }

        override fun onStop(context: Observation.Context) {
            stops++
            if (context is SqsObservationContext) {
                processOutcome = context.outcome
            }
        }
    }

    private class FailingHandler(
        private val startFailure: Throwable? = null,
        private val scopeFailure: Throwable? = null,
        private val errorFailure: Throwable? = null,
        private val stopFailure: Throwable? = null,
    ) : ObservationHandler<Observation.Context> {
        var starts: Int = 0
        var stops: Int = 0

        override fun supportsContext(context: Observation.Context): Boolean = true

        override fun onStart(context: Observation.Context) {
            starts++
            if (context is SqsObservationContext) {
                startFailure?.let { throw it }
            }
        }

        override fun onScopeOpened(context: Observation.Context) {
            if (context is SqsObservationContext) {
                scopeFailure?.let { throw it }
            }
        }

        override fun onError(context: Observation.Context) {
            if (context is SqsObservationContext) {
                errorFailure?.let { throw it }
            }
        }

        override fun onStop(context: Observation.Context) {
            stops++
            if (context is SqsObservationContext) {
                stopFailure?.let { throw it }
            }
        }
    }
}
