package io.bluetape4k.aws.spring.sqs

import io.micrometer.observation.Observation
import io.micrometer.observation.ObservationHandler
import io.micrometer.observation.ObservationRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.core.annotation.Order

class SqsObservationCustomizationExampleTest {

    @Test
    fun `ordered customizers run exactly once before the user factory`() {
        val calls = mutableListOf<String>()
        val context = processContext()
        val registry = ObservationRegistry.create()
        registry.observationConfig().observationHandler(RecordingHandler())
        val factory = SqsObservationFactory { suppliedContext, suppliedRegistry ->
            calls += "factory"
            assertSame(context, suppliedContext)
            assertSame(registry, suppliedRegistry)
            Observation.createNotStarted("custom", { suppliedContext }, suppliedRegistry)
        }

        val observation = prepareSqsObservation(
            context = context,
            registry = registry,
            customizers = listOf(SecondCustomizer(calls), FirstCustomizer(calls)),
            factory = factory,
        )

        assertEquals(listOf("first", "second", "factory"), calls)
        assertSame(context, observation.context)
        assertEquals("first-second", context.get<String>("customizer-order"))
    }

    @Test
    fun `factory returns a not started observation bound to the supplied registry and same context`() {
        val context = processContext()
        val registry = ObservationRegistry.create()
        val handler = RecordingHandler()
        registry.observationConfig().observationHandler(handler)
        val factory = defaultSqsObservationFactory(defaultSqsObservationConventions())

        val observation = prepareSqsObservation(context, registry, emptyList(), factory)

        assertSame(context, observation.context)
        assertNull(registry.currentObservation)
        assertEquals(0, handler.starts)

        observation.start()
        observation.openScope().use {
            requireSqsObservationRegistryBinding(registry, observation)
            assertSame(observation, registry.currentObservation)
        }
        observation.stop()

        assertEquals(1, handler.starts)
        assertEquals(1, handler.stops)
    }

    @Test
    fun `factory with a different context fails before lifecycle starts`() {
        val context = processContext()
        val registry = ObservationRegistry.create()
        val factory = SqsObservationFactory { _, suppliedRegistry ->
            val other = processContext()
            Observation.createNotStarted("custom", { other }, suppliedRegistry)
        }

        val error = assertThrows(IllegalStateException::class.java) {
            prepareSqsObservation(context, registry, emptyList(), factory)
        }

        assertTrue(error.message.orEmpty().contains("context"))
        assertNull(registry.currentObservation)
    }

    @Test
    fun `factory bound to another registry fails before lifecycle starts`() {
        val context = processContext()
        val suppliedRegistry = ObservationRegistry.create()
        val otherRegistry = ObservationRegistry.create()
        otherRegistry.observationConfig().observationHandler(RecordingHandler())
        val factory = SqsObservationFactory { suppliedContext, _ ->
            Observation.createNotStarted("custom", { suppliedContext }, otherRegistry)
        }
        val error = assertThrows(IllegalStateException::class.java) {
            prepareSqsObservation(context, suppliedRegistry, emptyList(), factory)
        }

        assertTrue(error.message.orEmpty().contains("registry"))
        assertNull(suppliedRegistry.currentObservation)
        assertNull(otherRegistry.currentObservation)
    }

    @Test
    fun `Observation NOOP is an accepted factory result without a lifecycle`() {
        val context = processContext()
        val observation = prepareSqsObservation(
            context = context,
            registry = ObservationRegistry.create(),
            customizers = emptyList(),
            factory = SqsObservationFactory { _, _ -> Observation.NOOP },
        )

        assertSame(Observation.NOOP, observation)
        assertTrue(observation.isNoop)
    }

    @Test
    fun `redacted telemetry exception exposes no message cause or stack`() {
        val error = SqsObservationTelemetryException("handler")

        assertNull(error.message)
        assertNull(error.cause)
        assertEquals(0, error.stackTrace.size)
        assertEquals("handler", error.failureStage)
        assertFalse(error.toString().contains("handler"))
    }

    private fun processContext(): SqsObservationContext = SqsObservationContext(
        SqsObservationMetadata(
            listenerId = "listener-1",
            queueName = "orders",
            stage = SqsObservationStage.PROCESS,
            batch = false,
            initialAttempt = 1,
        ),
    )

    @Order(1)
    private class FirstCustomizer(
        private val calls: MutableList<String>,
    ) : SqsObservationContextCustomizer {
        override fun customize(context: SqsObservationContext) {
            calls += "first"
            context.put("customizer-order", "first")
        }
    }

    @Order(2)
    private class SecondCustomizer(
        private val calls: MutableList<String>,
    ) : SqsObservationContextCustomizer {
        override fun customize(context: SqsObservationContext) {
            calls += "second"
            context.put("customizer-order", context.get<String>("customizer-order") + "-second")
        }
    }

    private class RecordingHandler : ObservationHandler<SqsObservationContext> {
        var starts: Int = 0
        var stops: Int = 0

        override fun supportsContext(context: Observation.Context): Boolean = context is SqsObservationContext

        override fun onStart(context: SqsObservationContext) {
            starts++
        }

        override fun onStop(context: SqsObservationContext) {
            stops++
        }
    }
}
