package io.bluetape4k.aws.spring.modulith

import io.bluetape4k.aws.spring.sns.SnsOperations
import io.bluetape4k.aws.spring.sqs.SqsFullRequestOperations
import io.bluetape4k.aws.spring.sqs.SqsOperations
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.assertions.shouldNotBeNull
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.FilteredClassLoader
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.modulith.events.EventExternalizationConfiguration
import org.springframework.modulith.events.core.EventSerializer
import org.springframework.modulith.events.support.EventExternalizerModuleListener
import org.springframework.modulith.events.support.EventExternalizationTransport

class AwsModulithEventsClasspathIsolationTest {

    private val contextRunner = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(AwsModulithEventsAutoConfiguration::class.java))
        .withPropertyValues("bluetape4k.aws.modulith.events.enabled=true")

    @Test
    fun `outer configuration uses only name conditions and stable ordering`() {
        val annotation = AwsModulithEventsAutoConfiguration::class.java.getAnnotation(AutoConfiguration::class.java)

        annotation.afterName.toSet() shouldBeEqualTo setOf(
            "io.bluetape4k.aws.spring.sns.SnsAutoConfiguration",
            "io.bluetape4k.aws.spring.sns.SnsHttpMessageVerificationAutoConfiguration",
            "io.bluetape4k.aws.spring.sqs.SqsAutoConfiguration",
        )
    }

    @Test
    fun `imports registers only the outer Modulith configuration`() {
        val imports = requireNotNull(
            javaClass.classLoader.getResourceAsStream(
                "META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports"
            )
        ).bufferedReader().use { it.readLines() }

        imports.count { it == AwsModulithEventsAutoConfiguration::class.qualifiedName } shouldBeEqualTo 1
        imports.none {
            it.startsWith("io.bluetape4k.aws.spring.modulith.") &&
                it != AwsModulithEventsAutoConfiguration::class.qualifiedName
        }.shouldBeEqualTo(true)
    }

    @Test
    fun `missing Modulith package leaves adapter absent`() {
        contextRunner
            .withClassLoader(FilteredClassLoader("org.springframework.modulith"))
            .run { context ->
                context.startupFailure.shouldBeNull()
                adapterBeanCount(context.beanFactory.beanDefinitionNames) shouldBeEqualTo 0
            }
    }

    @Test
    fun `missing module listener alone leaves adapter absent`() {
        contextRunner
            .withClassLoader(FilteredClassLoader(EventExternalizerModuleListener::class.java))
            .run { context ->
                context.startupFailure.shouldBeNull()
                adapterBeanCount(context.beanFactory.beanDefinitionNames) shouldBeEqualTo 0
            }
    }

    @Test
    fun `SNS SDK absence does not prevent SQS-only producer`() {
        configuredRunner()
            .withClassLoader(FilteredClassLoader("software.amazon.awssdk.services.sns"))
            .withBean(SqsOperations::class.java, { mockk<SqsFullRequestOperations>(relaxed = true) })
            .withPropertyValues(
                "bluetape4k.aws.modulith.events.producer.enabled=true",
                "bluetape4k.aws.modulith.events.targets.events.service=sqs",
                "bluetape4k.aws.modulith.events.targets.events.destination=events",
            )
            .run { context ->
                context.startupFailure.shouldBeNull()
                context.getBeansOfType(EventExternalizationTransport::class.java).size shouldBeEqualTo 1
            }
    }

    @Test
    fun `SQS SDK absence does not prevent SNS-only producer`() {
        configuredRunner()
            .withClassLoader(FilteredClassLoader("software.amazon.awssdk.services.sqs"))
            .withBean(SnsOperations::class.java, { mockk(relaxed = true) })
            .withPropertyValues(
                "bluetape4k.aws.modulith.events.producer.enabled=true",
                "bluetape4k.aws.modulith.events.targets.events.service=sns",
                "bluetape4k.aws.modulith.events.targets.events.destination=events",
            )
            .run { context ->
                context.startupFailure.shouldBeNull()
                context.getBeansOfType(EventExternalizationTransport::class.java).size shouldBeEqualTo 1
            }
    }

    @Test
    fun `SNS message manager absence permits DIRECT consumer`() {
        configuredRunner()
            .withClassLoader(FilteredClassLoader("software.amazon.awssdk.messagemanager.sns"))
            .withBean(SqsOperations::class.java, { mockk<SqsFullRequestOperations>(relaxed = true) })
            .withBean(EventExternalizationConfiguration::class.java, { EventExternalizationConfiguration.disabled() })
            .withPropertyValues(*consumerProperties("direct"))
            .run { context ->
                context.startupFailure.shouldBeNull()
                context.getBeansOfType(AwsModulithSqsEventConsumer::class.java).size shouldBeEqualTo 1
            }
    }

    @Test
    fun `SNS message manager absence fails SNS consumer closed`() {
        configuredRunner()
            .withClassLoader(FilteredClassLoader("software.amazon.awssdk.messagemanager.sns"))
            .withBean(SqsOperations::class.java, { mockk<SqsFullRequestOperations>(relaxed = true) })
            .withBean(EventExternalizationConfiguration::class.java, { EventExternalizationConfiguration.disabled() })
            .withPropertyValues(
                *consumerProperties("sns"),
                "bluetape4k.aws.modulith.events.consumer.expected-topic-arns[0]=" +
                    "arn:aws:sns:us-east-1:000000000000:events",
            )
            .run { context ->
                context.startupFailure.shouldNotBeNull()
                failureMessages(context.startupFailure) shouldContain AwsModulithDiagnosticCode.CONFIGURATION.value
            }
    }

    private fun configuredRunner(): ApplicationContextRunner = contextRunner
        .withBean(AwsModulithEventTypeRegistry::class.java, { eventRegistry() })
        .withBean(EventSerializer::class.java, { TestEventSerializer })

    private fun eventRegistry(): AwsModulithEventTypeRegistry = AwsModulithEventTypeRegistry.of(
        AwsModulithEventTypeRegistration(
            type = "test.event",
            version = 1,
            eventClass = TestEvent::class.java,
            eventId = TestEvent::id,
        )
    )

    private fun consumerProperties(sourceMode: String): Array<String> = arrayOf(
        "bluetape4k.aws.modulith.events.consumer.enabled=true",
        "bluetape4k.aws.modulith.events.consumer.queue=events",
        "bluetape4k.aws.modulith.events.consumer.source-mode=$sourceMode",
        "bluetape4k.aws.modulith.events.consumer.redrive-required=false",
    )

    private fun failureMessages(failure: Throwable?): String =
        generateSequence(failure) { it.cause }
            .mapNotNull { it.message }
            .joinToString("\n")

    private fun adapterBeanCount(names: Array<String>): Int =
        names.count { it.contains("awsModulith", ignoreCase = true) }

    private data class TestEvent(val id: String)

    private object TestEventSerializer : EventSerializer {
        override fun serialize(event: Any): Any = "{\"id\":\"${(event as TestEvent).id}\"}"

        override fun <T : Any> deserialize(serialized: Any, type: Class<T>): T =
            type.cast(TestEvent("decoded"))
    }
}
