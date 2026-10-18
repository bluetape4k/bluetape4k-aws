package io.bluetape4k.aws.spring.sqs

import io.bluetape4k.assertions.shouldBe
import io.bluetape4k.assertions.shouldBeEmpty
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeInstanceOf
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldNotBe
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.debug
import io.micrometer.context.ContextSnapshotFactory
import io.micrometer.observation.Observation
import io.micrometer.observation.ObservationHandler
import io.micrometer.observation.ObservationRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy
import java.util.concurrent.Executors

class SqsObservationRuntimeTest {

    companion object: KLoggingChannel()

    @Test
    fun `process observation survives suspension and restores its parent`() = runTest {
        val registry = ObservationRegistry.create()
        val handler = RecordingHandler()
        registry.observationConfig().observationHandler(handler)
        val parent = Observation.start("parent", registry)
        val runtime = runtime(registry)

        parent.openScope().use {
            val result = observeSqs(runtime, ::processContext) {
                registry.currentObservation shouldBe observation
                withContext(StandardTestDispatcher(testScheduler)) {
                    registry.currentObservation shouldBe observation
                }
                "done"
            }


            result shouldBeEqualTo "done"
            registry.currentObservation shouldBe parent
        }
        parent.stop()

        log.debug { "handler=$handler" }
        handler.starts shouldBeEqualTo 2
        handler.stops shouldBeEqualTo 2
        handler.processParent shouldBe parent
    }

    @Test
    fun `process observation crosses a worker thread and clears its thread local`() = runTest {
        val registry = ObservationRegistry.create()
        registry.observationConfig().observationHandler(RecordingHandler())
        val parent = Observation.start("parent", registry)
        val callerThread = Thread.currentThread()
        val workerDispatcher = Executors.newSingleThreadExecutor { task ->
            Thread(task, "sqs-observation-runtime-test")
        }.asCoroutineDispatcher()

        try {
            parent.openScope().use {
                observeSqs(runtime(registry), ::processContext) {
                    withContext(workerDispatcher) {
                        Thread.currentThread() shouldNotBe callerThread
                        registry.currentObservation shouldBe observation
                    }
                }
                registry.currentObservation shouldBe parent
            }
            withContext(workerDispatcher) {
                registry.currentObservation.shouldBeNull()
            }
        } finally {
            workerDispatcher.close()
            parent.stop()
        }
    }

    @Test
    fun `child observation is linked below the process observation`() = runTest {
        val registry = ObservationRegistry.create()
        registry.observationConfig().observationHandler(RecordingHandler())
        val runtime = runtime(registry)

        observeSqs(runtime, ::processContext) {
            val child = Observation.start("child", registry)
            try {
                child.context.parentObservation shouldBe observation
            } finally {
                child.stop()
            }
        }
    }

    @Test
    fun `nested cleanup failure preserves the process scope until coroutine restoration completes`() = runTest {
        val registry = ObservationRegistry.create()
        val stopFailure = IllegalStateException("nested-stop")
        registry.observationConfig().observationHandler(
            object: ObservationHandler<SqsObservationContext> {
                override fun supportsContext(context: Observation.Context): Boolean =
                    context is SqsObservationContext

                override fun onStop(context: SqsObservationContext) {
                    if (context.metadata.listenerId == "nested") {
                        throw stopFailure
                    }
                }
            },
        )
        val runtime = runtime(registry)

        observeSqs(runtime, ::processContext) {
            val processObservation = observation
            var cleanupFailure: Throwable? = null

            runtime.prepare(processContext(listenerId = "nested")).observe(
                onCleanupFailure = { cleanupFailure = it },
            ) {
                withContext(StandardTestDispatcher(testScheduler)) {
                    registry.currentObservation shouldBe observation
                }
            }

            cleanupFailure shouldBe stopFailure
            registry.currentObservation shouldBe processObservation
        }

        log.debug { "registry=$registry" }
        registry.currentObservation.shouldBeNull()
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

        actual shouldBe failure

        log.debug { "handler=$handler" }
        handler.starts shouldBeEqualTo 1
        handler.stops shouldBeEqualTo 1
        handler.error.shouldBeInstanceOf<SqsObservationTelemetryException>()
        handler.error?.message.shouldBeNull()
        handler.error?.stackTrace.shouldBeEmpty()

        log.debug { "registry=$registry" }
        registry.currentObservation.shouldBeNull()
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

        actual shouldBe cancellation

        log.debug { "handler=$handler" }
        handler.stops shouldBeEqualTo 1
        handler.error.shouldBeInstanceOf<SqsObservationTelemetryException>()
        handler.error?.message.shouldBeNull()

        log.debug { "registry=$registry" }
        registry.currentObservation.shouldBeNull()
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
            context.attempt shouldBeEqualTo 3
        }

        log.debug { "handler=$handler" }
        handler.events shouldBeEqualTo 1
        handler.processOutcome shouldBeEqualTo SqsObservationOutcome.RETRIED
    }

    @Test
    fun `null runtime and noop registry do not create context or invoke extensions`() = runTest {
        var contexts = 0
        var customizers = 0
        var factories = 0
        val noopRuntime = SqsObservationRuntime(
            registry = ObservationRegistry.NOOP,
            customizers = listOf(SqsObservationContextCustomizer { customizers++ }),
            factory = { _, _ -> factories++; Observation.NOOP },
        )

        observeSqs(null, { contexts++; processContext() }) { "null" } shouldBeEqualTo "null"
        observeSqs(noopRuntime, { contexts++; processContext() }) { "noop" } shouldBeEqualTo "noop"

        contexts shouldBeEqualTo 0
        customizers shouldBeEqualTo 0
        factories shouldBeEqualTo 0
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
            factory = { _, _ -> factories++; Observation.NOOP },
            contextSnapshotFactory = throwingSnapshotFactory(AssertionError("capture must not run")),
        )

        val result = observeSqs(runtime, { contexts++; processContext() }) {
            retry(2)
            "direct"
        }

        result shouldBeEqualTo "direct"
        contexts shouldBeEqualTo 1
        customizers shouldBeEqualTo 1
        factories shouldBeEqualTo 1
        registry.currentObservation.shouldBeNull()
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
            factory = { _, _ -> factoryCalls++; Observation.NOOP },
        )
        val factoryRuntime = SqsObservationRuntime(
            registry = registry,
            customizers = emptyList(),
            factory = { _, _ -> throw factoryFailure },
        )

        failureOf { observeSqs(customizerRuntime, ::processContext) {} } shouldBe customizerFailure
        factoryCalls shouldBeEqualTo 0
        registry.currentObservation.shouldBeNull()
        failureOf { observeSqs(factoryRuntime, ::processContext) {} } shouldBe factoryFailure
        registry.currentObservation.shouldBeNull()
    }

    @Test
    fun `start failure is primary and does not stop an observation that never started`() = runTest {
        val registry = ObservationRegistry.create()
        val startFailure = IllegalStateException("start")
        val handler = FailingHandler(startFailure = startFailure)
        registry.observationConfig().observationHandler(handler)

        val actual = failureOf { observeSqs(runtime(registry), ::processContext) {} }

        actual shouldBe startFailure
        handler.starts shouldBeEqualTo 1
        handler.stops shouldBeEqualTo 0
        registry.currentObservation.shouldBeNull()
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

            actual shouldBe scopeFailure
            registry.currentObservation shouldBe parent
        }
        parent.stop()

        handler.starts shouldBeEqualTo 2
        handler.stops shouldBeEqualTo 2
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

            actual shouldBe captureFailure
            registry.currentObservation shouldBe parent
        }
        parent.stop()

        handler.starts shouldBeEqualTo 2
        handler.stops shouldBeEqualTo 2
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

        actual shouldBe businessFailure
        actual.suppressed.single() shouldBe errorFailure
        errorFailure.suppressed.single() shouldBe stopFailure
        registry.currentObservation.shouldBeNull()
    }

    @Test
    fun `same telemetry failure instance is suppressed once without self suppression`() = runTest {
        val registry = ObservationRegistry.create()
        val businessFailure = IllegalStateException("business")
        val telemetryFailure = IllegalArgumentException("telemetry")
        registry.observationConfig().observationHandler(
            FailingHandler(errorFailure = telemetryFailure, stopFailure = telemetryFailure),
        )

        val actual = failureOf {
            observeSqs(runtime(registry), ::processContext) { throw businessFailure }
        }

        actual shouldBe businessFailure
        actual.suppressed.single() shouldBe telemetryFailure
        telemetryFailure.suppressed.shouldBeEmpty()
        registry.currentObservation.shouldBeNull()
    }

    @Test
    fun `cleanup failure merge ignores the same instance and preserves distinct order`() {
        val first = IllegalArgumentException("first")
        val second = UnsupportedOperationException("second")

        mergeSqsObservationCleanupFailure(first, first) shouldBe first
        first.suppressed.shouldBeEmpty()
        mergeSqsObservationCleanupFailure(first, second) shouldBe first
        first.suppressed.single() shouldBe second
    }

    @Test
    fun `stop failure becomes primary after successful business work`() = runTest {
        val registry = ObservationRegistry.create()
        val stopFailure = IllegalStateException("stop")
        registry.observationConfig().observationHandler(FailingHandler(stopFailure = stopFailure))

        val actual = failureOf { observeSqs(runtime(registry), ::processContext) { "done" } }

        actual shouldBe stopFailure
        registry.currentObservation.shouldBeNull()
    }

    private fun runtime(registry: ObservationRegistry): SqsObservationRuntime =
        SqsObservationRuntime(
            registry = registry,
            customizers = emptyList(),
            factory = defaultSqsObservationFactory(defaultSqsObservationConventions()),
        )

    private fun processContext(listenerId: String = "listener-1"): SqsObservationContext = SqsObservationContext(
        SqsObservationMetadata(
            listenerId = listenerId,
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

    private class RecordingHandler: ObservationHandler<Observation.Context> {
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
    ): ObservationHandler<Observation.Context> {
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
