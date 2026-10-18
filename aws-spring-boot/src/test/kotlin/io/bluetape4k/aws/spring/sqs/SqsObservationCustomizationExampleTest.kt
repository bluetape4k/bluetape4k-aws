package io.bluetape4k.aws.spring.sqs

import io.bluetape4k.assertions.shouldBe
import io.bluetape4k.assertions.shouldBeEmpty
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.assertions.shouldNotContain
import io.micrometer.observation.Observation
import io.micrometer.observation.ObservationHandler
import io.micrometer.observation.ObservationRegistry
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.core.annotation.Order

// tag::sqs-observation-customization[]
@Order(1)
private class DeploymentEnvironmentCustomizer: SqsObservationContextCustomizer {
    override fun customize(context: SqsObservationContext) {
        context.put("deployment.environment", "production")
    }
}

@Order(2)
private class ObservationOwnerCustomizer: SqsObservationContextCustomizer {
    override fun customize(context: SqsObservationContext) {
        val environment = context.get<String>("deployment.environment")
        context.put("observation.owner", "$environment:sqs-platform")
    }
}

fun sqsObservationFactory(): SqsObservationFactory = SqsObservationFactory { context, registry ->
    val observationName = when (context.metadata.stage) {
        SqsObservationStage.RECEIVE -> "custom.sqs.receive"
        SqsObservationStage.PROCESS -> "custom.sqs.process"
        SqsObservationStage.ACKNOWLEDGEMENT -> "custom.sqs.acknowledgement"
    }
    Observation.createNotStarted(observationName, { context }, registry)
}
// end::sqs-observation-customization[]

class SqsObservationCustomizationExampleTest {

    @Test
    fun `global factory keeps distinct names for every observation stage`() {
        val registry = ObservationRegistry.create()
        val names = mutableMapOf<SqsObservationStage, String>()
        registry.observationConfig().observationHandler(RecordingHandler())
        registry.observationConfig().observationPredicate { name, context ->
            if (context is SqsObservationContext) names[context.metadata.stage] = name
            true
        }

        SqsObservationStage.entries.forEach { stage ->
            val context = observationContext(stage)
            val observation = sqsObservationFactory().createNotStarted(context, registry).start()
            observation.stop()
        }

        names[SqsObservationStage.RECEIVE] shouldBeEqualTo "custom.sqs.receive"
        names[SqsObservationStage.PROCESS] shouldBeEqualTo "custom.sqs.process"
        names[SqsObservationStage.ACKNOWLEDGEMENT] shouldBeEqualTo "custom.sqs.acknowledgement"
    }

    @Test
    fun `ordered customizers run exactly once before the user factory`() {
        val context = processContext()
        val registry = ObservationRegistry.create()
        registry.observationConfig().observationHandler(RecordingHandler())

        val factory = SqsObservationFactory { suppliedContext, suppliedRegistry ->
            suppliedContext shouldBe context
            suppliedRegistry shouldBe registry
            suppliedContext.get<String>("deployment.environment") shouldBeEqualTo "production"
            suppliedContext.get<String>("observation.owner") shouldBeEqualTo "production:sqs-platform"
            sqsObservationFactory().createNotStarted(suppliedContext, suppliedRegistry)
        }

        val observation = prepareSqsObservation(
            context = context,
            registry = registry,
            customizers = orderedSqsObservationCustomizers(
                listOf(ObservationOwnerCustomizer(), DeploymentEnvironmentCustomizer()),
            ),
            factory = factory,
        )

        observation.context shouldBe context
        context.get<String>("deployment.environment") shouldBeEqualTo "production"
        context.get<String>("observation.owner") shouldBeEqualTo "production:sqs-platform"
    }

    @Test
    fun `factory returns a not started observation bound to the supplied registry and same context`() {
        val context = processContext()
        val registry = ObservationRegistry.create()
        val handler = RecordingHandler()
        registry.observationConfig().observationHandler(handler)
        val factory = defaultSqsObservationFactory(defaultSqsObservationConventions())

        val observation = prepareSqsObservation(context, registry, emptyList(), factory)

        observation.context shouldBe context
        registry.currentObservation.shouldBeNull()
        handler.starts shouldBeEqualTo 0

        observation.start()
        observation.openScope().use {
            requireSqsObservationRegistryBinding(registry, observation)
            registry.currentObservation shouldBe observation
        }
        observation.stop()

        handler.starts shouldBeEqualTo 1
        handler.stops shouldBeEqualTo 1
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

        error.message shouldContain "context"
        registry.currentObservation.shouldBeNull()
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

        error.message shouldContain "registry"
        suppliedRegistry.currentObservation.shouldBeNull()
        otherRegistry.currentObservation.shouldBeNull()
    }

    @Test
    fun `Observation NOOP is an accepted factory result without a lifecycle`() {
        val context = processContext()
        val observation = prepareSqsObservation(
            context = context,
            registry = ObservationRegistry.create(),
            customizers = emptyList(),
            factory = { _, _ -> Observation.NOOP },
        )

        observation shouldBe Observation.NOOP
        observation.isNoop.shouldBeTrue()
    }

    @Test
    fun `redacted telemetry exception exposes no message cause or stack`() {
        val error = SqsObservationTelemetryException("handler")

        error.message.shouldBeNull()
        error.cause.shouldBeNull()
        error.stackTrace.shouldBeEmpty()
        error.failureStage shouldBeEqualTo "handler"
        error.toString() shouldNotContain "handler"
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

    private fun observationContext(stage: SqsObservationStage): SqsObservationContext = SqsObservationContext(
        SqsObservationMetadata(
            listenerId = "listener-1",
            queueName = "orders",
            stage = stage,
            batch = false,
            initialAttempt = 1,
            acknowledgementAction = if (stage == SqsObservationStage.ACKNOWLEDGEMENT) {
                SqsAcknowledgementAction.ACK
            } else {
                null
            },
        ),
    )

    private class RecordingHandler: ObservationHandler<SqsObservationContext> {
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
