package io.bluetape4k.aws.spring.sqs

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEmpty
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldBeSameInstanceAs
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldHaveSize
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.aws.spring.AwsAutoConfiguration
import io.bluetape4k.logging.KLogging
import io.micrometer.observation.Observation
import io.micrometer.observation.ObservationHandler
import io.micrometer.observation.ObservationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.getBean
import org.springframework.beans.factory.getBeansOfType
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.autoconfigure.condition.ConditionEvaluationReport
import org.springframework.boot.test.context.FilteredClassLoader
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import software.amazon.awssdk.services.sqs.SqsAsyncClient
import java.util.concurrent.atomic.AtomicInteger

class SqsObservationAutoConfigurationTest {

    companion object: KLogging()

    private val contextRunner = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(SqsObservationAutoConfiguration::class.java))

    @Test
    fun `observation is disabled by default`() {
        contextRunner
            .withBean(ObservationRegistry::class.java, { ObservationRegistry.create() })
            .withBean(ObservationHandler::class.java, { SupportingObservationHandler })
            .run { context ->
                context.startupFailure.shouldBeNull()
                context.getBeansOfType<SqsObservationActivation>().shouldBeEmpty()
                context.getBeansOfType<SqsObservationRuntime>().shouldBeEmpty()
            }
    }

    @Test
    fun `explicit observation disable wins over valid prerequisites`() {
        validPrerequisitesRunner()
            .withPropertyValues("bluetape4k.aws.sqs.observation.enabled=false")
            .run { context ->
                context.startupFailure.shouldBeNull()
                context.getBeansOfType<SqsObservationActivation>().shouldBeEmpty()
            }
    }

    @Test
    fun `global AWS disable prevents observation activation`() {
        validPrerequisitesRunner()
            .withPropertyValues("bluetape4k.aws.enabled=false")
            .run { context ->
                context.startupFailure.shouldBeNull()
                context.getBeansOfType<SqsObservationActivation>().shouldBeEmpty()
            }
    }

    @Test
    fun `SQS disable prevents observation activation`() {
        validPrerequisitesRunner()
            .withPropertyValues("bluetape4k.aws.sqs.enabled=false")
            .run { context ->
                context.startupFailure.shouldBeNull()
                context.getBeansOfType<SqsObservationActivation>().shouldBeEmpty()
            }
    }

    @Test
    fun `observation activates only with registry and supporting Spring handler`() {
        contextRunner
            .withPropertyValues("bluetape4k.aws.sqs.observation.enabled=true")
            .withBean(ObservationRegistry::class.java, { ObservationRegistry.create() })
            .withBean(ObservationHandler::class.java, { SupportingObservationHandler })
            .run { context ->
                context.startupFailure.shouldBeNull()
                context.getBeansOfType<SqsObservationProperties>() shouldHaveSize 1
                context.getBean<SqsObservationProperties>().enabled.shouldBeTrue()
                context.getBean<SqsObservationActivation>().shouldNotBeNull()
                context.getBean<SqsObservationRuntime>().shouldNotBeNull()
                context.getBeansOfType<SqsAsyncClient>().shouldBeEmpty()
            }
    }

    @Test
    fun `runtime connector initializes the SQS processor before every listener container`() {
        ApplicationContextRunner()
            .withConfiguration(
                AutoConfigurations.of(
                    AwsAutoConfiguration::class.java,
                    SqsObservationAutoConfiguration::class.java,
                    SqsAutoConfiguration::class.java,
                ),
            )
            .withPropertyValues(
                "bluetape4k.aws.sqs.region=us-east-1",
                "bluetape4k.aws.sqs.observation.enabled=true",
                "bluetape4k.aws.sqs.listener.auto-startup=false",
            )
            .withBean(SqsOperations::class.java, { NoopSqsOperations })
            .withBean(ObservationRegistry::class.java, { ObservationRegistry.create() })
            .withBean(ObservationHandler::class.java, { SupportingObservationHandler })
            .withUserConfiguration(RecordingListenerConfiguration::class.java)
            .run { context ->
                context.startupFailure.shouldBeNull()
                val runtime = context.getBean<SqsObservationRuntime>()
                val processor = context.getBean<SqsListenerAnnotationBeanPostProcessor>()
                val containers = context.getBean<SqsMessageListenerContainerRegistry>().containers

                processor.observationRuntimeOrNull() shouldBeSameInstanceAs runtime
                containers.size shouldBeEqualTo 2
                containers.forEach { container ->
                    container.observationRuntimeOrNull() shouldBeSameInstanceAs runtime
                }
                assertFailsWith<IllegalStateException> {
                    processor.setObservationRuntime(runtime)
                }
            }
    }

    @Test
    fun `NOOP registry does not activate observation`() {
        contextRunner
            .withPropertyValues("bluetape4k.aws.sqs.observation.enabled=true")
            .withBean(ObservationRegistry::class.java, { ObservationRegistry.NOOP })
            .withBean(ObservationHandler::class.java, { SupportingObservationHandler })
            .run { context ->
                context.startupFailure.shouldBeNull()
                context.getBeansOfType<SqsObservationActivation>().shouldBeEmpty()
                context.getBeansOfType<SqsObservationRuntime>().shouldBeEmpty()
            }
    }

    @Test
    fun `missing registry does not activate observation`() {
        contextRunner
            .withPropertyValues("bluetape4k.aws.sqs.observation.enabled=true")
            .withBean(ObservationHandler::class.java, { SupportingObservationHandler })
            .run { context ->
                context.startupFailure.shouldBeNull()
                context.getBeansOfType<SqsObservationActivation>().shouldBeEmpty()
            }
    }

    @Test
    fun `failed prerequisites expose the bounded diagnostic code`() {
        contextRunner
            .withPropertyValues("bluetape4k.aws.sqs.observation.enabled=true")
            .run { context ->
                val messages = ConditionEvaluationReport.get(context.beanFactory)
                    .conditionAndOutcomesBySource
                    .values
                    .asSequence()
                    .flatMap { outcomes -> outcomes.asSequence() }
                    .mapNotNull { conditionAndOutcome -> conditionAndOutcome.outcome.message }
                    .toList()

                messages.any { message -> message.contains("BT4K-SQS-OBS-101 registry-missing") }.shouldBeTrue()
            }
    }

    @Test
    fun `missing supporting handler exposes the documented diagnostic reason`() {
        contextRunner
            .withPropertyValues("bluetape4k.aws.sqs.observation.enabled=true")
            .withBean(ObservationRegistry::class.java, { ObservationRegistry.create() })
            .run { context ->
                val messages = ConditionEvaluationReport.get(context.beanFactory)
                    .conditionAndOutcomesBySource
                    .values
                    .asSequence()
                    .flatMap { outcomes -> outcomes.asSequence() }
                    .mapNotNull { conditionAndOutcome -> conditionAndOutcome.outcome.message }
                    .toList()

                messages.any { message -> message.contains("BT4K-SQS-OBS-101 handler-missing") }.shouldBeTrue()
            }
    }

    @Test
    fun `registry handler that is not a Spring bean does not activate observation`() {
        contextRunner
            .withPropertyValues("bluetape4k.aws.sqs.observation.enabled=true")
            .withBean(
                ObservationRegistry::class.java,
                {
                    ObservationRegistry.create().apply {
                        observationConfig().observationHandler(SupportingObservationHandler)
                    }
                },
            )
            .run { context ->
                context.startupFailure.shouldBeNull()
                context.getBeansOfType<SqsObservationActivation>().shouldBeEmpty()
            }
    }

    @Test
    fun `unsupported Spring handler does not activate observation`() {
        contextRunner
            .withPropertyValues("bluetape4k.aws.sqs.observation.enabled=true")
            .withBean(ObservationRegistry::class.java, { ObservationRegistry.create() })
            .withBean(ObservationHandler::class.java, { UnsupportedObservationHandler })
            .run { context ->
                context.startupFailure.shouldBeNull()
                context.getBeansOfType<SqsObservationActivation>().shouldBeEmpty()
            }
    }

    @Test
    fun `handler probe failure is not hidden as a missing handler`() {
        contextRunner
            .withPropertyValues("bluetape4k.aws.sqs.observation.enabled=true")
            .withBean(ObservationRegistry::class.java, { ObservationRegistry.create() })
            .withBean(ObservationHandler::class.java, { FailingObservationHandler })
            .run { context ->
                context.startupFailure.shouldNotBeNull()
                val messages = generateSequence(context.startupFailure) { it.cause }
                    .mapNotNull { it.message }
                    .joinToString("\n")
                messages.contains("probe failure").shouldBeTrue()
            }
    }

    @Test
    fun `missing Spring handler does not activate observation`() {
        contextRunner
            .withPropertyValues("bluetape4k.aws.sqs.observation.enabled=true")
            .withBean(ObservationRegistry::class.java, { ObservationRegistry.create() })
            .run { context ->
                context.startupFailure.shouldBeNull()
                context.getBeansOfType<SqsObservationActivation>().shouldBeEmpty()
            }
    }

    @Test
    fun `user factory replaces only the default factory`() {
        val calls = AtomicInteger()
        validPrerequisitesRunner()
            .withBean(
                SqsObservationFactory::class.java,
                {
                    SqsObservationFactory { _, _ ->
                        calls.incrementAndGet()
                        Observation.NOOP
                    }
                },
            )
            .run { context ->
                val runtime = context.getBean<SqsObservationRuntime>()
                runBlocking {
                    runtime.observe(processContext()) { }
                }
                calls.get() shouldBeEqualTo 1

                val conditionMessages = ConditionEvaluationReport.get(context.beanFactory)
                    .conditionAndOutcomesBySource
                    .values
                    .asSequence()
                    .flatMap { outcomes -> outcomes.asSequence() }
                    .mapNotNull { conditionAndOutcome -> conditionAndOutcome.outcome.message }
                    .toList()

                conditionMessages.any { message ->
                    message.contains("BT4K-SQS-OBS-101 user-factory")
                }.shouldBeTrue()
            }
    }

    @Test
    fun `user factory cannot bypass missing handler prerequisite`() {
        contextRunner
            .withPropertyValues("bluetape4k.aws.sqs.observation.enabled=true")
            .withBean(ObservationRegistry::class.java, { ObservationRegistry.create() })
            .withBean(
                SqsObservationFactory::class.java,
                { SqsObservationFactory { _, _ -> Observation.NOOP } },
            )
            .run { context ->
                context.startupFailure.shouldBeNull()
                context.getBeansOfType<SqsObservationRuntime>().shouldBeEmpty()
            }
    }

    @Test
    fun `duplicate conventions for one stage fail fast`() {
        validPrerequisitesRunner()
            .withBean("firstProcessConvention", SqsObservationConvention::class.java, { ProcessConvention })
            .withBean("secondProcessConvention", SqsObservationConvention::class.java, { ProcessConvention })
            .run { context ->
                context.startupFailure.shouldNotBeNull()
                val messages = generateSequence(context.startupFailure) { it.cause }
                    .mapNotNull { it.message }
                    .joinToString("\n")
                messages.contains("Only one SqsObservationConvention").shouldBeTrue()
            }
    }

    @Test
    fun `context recreation follows false true false property transitions`() {
        val activeCounts = listOf(false, true, false).map { enabled ->
            validPrerequisitesRunner()
                .withPropertyValues("bluetape4k.aws.sqs.observation.enabled=$enabled")
                .runAndCountActivations()
        }

        activeCounts shouldBeEqualTo listOf(0, 1, 0)
    }

    @Test
    fun `missing ContextSnapshot class backs off without linkage failure`() {
        contextRunner
            .withClassLoader(FilteredClassLoader("io.micrometer.context.ContextSnapshot"))
            .withPropertyValues("bluetape4k.aws.sqs.observation.enabled=true")
            .withBean(ObservationRegistry::class.java, { ObservationRegistry.create() })
            .withBean(ObservationHandler::class.java, { SupportingObservationHandler })
            .run { context ->
                context.startupFailure.shouldBeNull()
                context.getBeansOfType<SqsObservationActivation>().shouldBeEmpty()

                val messages = ConditionEvaluationReport.get(context.beanFactory)
                    .conditionAndOutcomesBySource
                    .values
                    .asSequence()
                    .flatMap { outcomes -> outcomes.asSequence() }
                    .mapNotNull { conditionAndOutcome -> conditionAndOutcome.outcome.message }
                    .toList()

                messages.any { message ->
                    message.contains("BT4K-SQS-OBS-101 context-propagation-missing")
                }.shouldBeTrue()
            }
    }

    @Test
    fun `auto configuration declares observation and SQS ordering`() {
        val annotation = SqsObservationAutoConfiguration::class.java
            .getAnnotation(AutoConfiguration::class.java)

        annotation.afterName.toList() shouldBeEqualTo listOf(
            "org.springframework.boot.micrometer.observation.autoconfigure.ObservationAutoConfiguration",
        )
        annotation.before.toList() shouldBeEqualTo listOf(SqsAutoConfiguration::class)
    }

    @Test
    fun `auto configuration is registered in imports`() {
        val resource = Thread.currentThread().contextClassLoader
            .getResource("META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports")
            .shouldNotBeNull()

        resource.readText().lineSequence().map(String::trim).toList().contains(
            SqsObservationAutoConfiguration::class.java.name,
        ).shouldBeTrue()
    }

    @Test
    fun `observation activation does not contribute health or readiness beans`() {
        validPrerequisitesRunner().run { context ->
            context.beanDefinitionNames.none { name ->
                name.contains("sqsObservationHealth", ignoreCase = true) ||
                        name.contains("sqsObservationReadiness", ignoreCase = true)
            }.shouldBeTrue()
        }
    }

    private fun validPrerequisitesRunner(): ApplicationContextRunner = contextRunner
        .withPropertyValues("bluetape4k.aws.sqs.observation.enabled=true")
        .withBean(ObservationRegistry::class.java, { ObservationRegistry.create() })
        .withBean(ObservationHandler::class.java, { SupportingObservationHandler })

    private fun ApplicationContextRunner.runAndCountActivations(): Int {
        var count = -1
        run { context ->
            context.startupFailure.shouldBeNull()
            count = context.getBeansOfType<SqsObservationActivation>().size
        }
        return count
    }

    private fun processContext(): SqsObservationContext = SqsObservationContext(
        SqsObservationMetadata(
            listenerId = "test-listener",
            queueName = "orders",
            stage = SqsObservationStage.PROCESS,
            batch = false,
            initialAttempt = 1,
        ),
    )

    private object SupportingObservationHandler: ObservationHandler<Observation.Context> {
        override fun onStart(context: Observation.Context) = Unit

        override fun supportsContext(context: Observation.Context): Boolean =
            context is SqsObservationContext && context.metadata.stage == SqsObservationStage.PROCESS
    }

    private object UnsupportedObservationHandler: ObservationHandler<Observation.Context> {
        override fun onStart(context: Observation.Context) = Unit

        override fun supportsContext(context: Observation.Context): Boolean = false
    }

    private object FailingObservationHandler: ObservationHandler<Observation.Context> {
        override fun onStart(context: Observation.Context) = Unit

        override fun supportsContext(context: Observation.Context): Boolean = error("probe failure")
    }

    private object ProcessConvention: SqsObservationConvention {
        override val stage: SqsObservationStage = SqsObservationStage.PROCESS

        override fun getName(): String = "test.process"
    }

    @Configuration(proxyBeanMethods = false)
    internal class RecordingListenerConfiguration {
        @Bean
        fun recordingListener(): RecordingListener = RecordingListener()
    }

    internal class RecordingListener {
        @SqsListener(id = "recording-first", queue = "orders")
        fun first(body: String) {
            check(body.isNotBlank())
        }

        @SqsListener(id = "recording-second", queue = "orders")
        fun second(body: String) {
            check(body.isNotBlank())
        }
    }
}
