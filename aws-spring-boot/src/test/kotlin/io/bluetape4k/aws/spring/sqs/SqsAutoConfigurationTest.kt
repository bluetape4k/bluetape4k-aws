package io.bluetape4k.aws.spring.sqs

import io.bluetape4k.aws.spring.AwsAutoConfiguration
import io.bluetape4k.aws.spring.AwsAsyncClientCustomizer
import io.bluetape4k.aws.spring.AwsClientCustomizationContext
import io.bluetape4k.aws.spring.AwsClientCustomizer
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldBeSameInstanceAs
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.assertions.shouldNotBeNull
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.micrometer.observation.Observation
import io.micrometer.observation.ObservationHandler
import io.micrometer.observation.ObservationRegistry
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.FilteredClassLoader
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.Ordered
import software.amazon.awssdk.awscore.client.builder.AwsAsyncClientBuilder
import software.amazon.awssdk.services.sqs.SqsAsyncClient
import software.amazon.awssdk.services.sqs.SqsAsyncClientBuilder
import tools.jackson.databind.ObjectMapper

class SqsAutoConfigurationTest {

    private val contextRunner = ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                AwsAutoConfiguration::class.java,
                SqsAutoConfiguration::class.java,
                SqsObservationAutoConfiguration::class.java,
                SqsMicrometerAutoConfiguration::class.java,
                SqsJacksonMessageConverterAutoConfiguration::class.java,
            )
        )
        .withPropertyValues("bluetape4k.aws.sqs.region=us-east-1")

    @Test
    fun `register SQS client operations registry and processor`() {
        contextRunner.run { context ->
            context.getBeansOfType(SqsAsyncClient::class.java).size shouldBeEqualTo 1
            context.getBeansOfType(SqsProperties::class.java).size shouldBeEqualTo 1
            context.getBeansOfType(SqsOperations::class.java).size shouldBeEqualTo 1
            context.getBeansOfType(SqsCoroutinesTemplate::class.java).size shouldBeEqualTo 1
            context.getBeansOfType(SqsMessageListenerContainerRegistry::class.java).size shouldBeEqualTo 1
            context.getBeansOfType(SqsListenerAnnotationBeanPostProcessor::class.java).size shouldBeEqualTo 1
            context.getBeansOfType(SqsMessageConverter::class.java).size shouldBeEqualTo 0
        }
    }

    @Test
    fun `register Micrometer SQS adapters when registry exists`() {
        contextRunner
            .withBean(SimpleMeterRegistry::class.java, { SimpleMeterRegistry() })
            .run { context ->
                context.getBean(SqsOperations::class.java).javaClass shouldBeEqualTo MicrometerSqsOperations::class.java
                context.getBeansOfType(SqsOperations::class.java).size shouldBeEqualTo 2
                context.getBeansOfType(SqsCoroutinesTemplate::class.java).size shouldBeEqualTo 1
                context.getBeansOfType(MicrometerSqsListenerInterceptor::class.java).size shouldBeEqualTo 1
            }
    }

    @Test
    fun `active observation suppresses only automatic listener meter`() {
        contextRunner
            .withPropertyValues("bluetape4k.aws.sqs.observation.enabled=true")
            .withBean(SimpleMeterRegistry::class.java, { SimpleMeterRegistry() })
            .withBean(ObservationRegistry::class.java, { ObservationRegistry.create() })
            .withBean(ObservationHandler::class.java, { SupportingSqsObservationHandler })
            .withBean(SqsListenerInterceptor::class.java, { UserSqsListenerInterceptor })
            .run { context ->
                context.startupFailure.shouldBeNull()
                context.getBeansOfType(SqsObservationActivation::class.java).size shouldBeEqualTo 1
                context.getBeansOfType(MicrometerSqsListenerInterceptor::class.java).size shouldBeEqualTo 0
                context.getBeansOfType(SqsListenerInterceptor::class.java).values.contains(
                    UserSqsListenerInterceptor,
                ) shouldBeEqualTo true
                context.getBeansOfType(MicrometerSqsOperations::class.java).size shouldBeEqualTo 1
                context.getBean(SqsOperations::class.java).javaClass shouldBeEqualTo MicrometerSqsOperations::class.java
            }
    }

    @Test
    fun `NOOP observation registry keeps automatic listener meter and operations`() {
        contextRunner
            .withPropertyValues("bluetape4k.aws.sqs.observation.enabled=true")
            .withBean(SimpleMeterRegistry::class.java, { SimpleMeterRegistry() })
            .withBean(ObservationRegistry::class.java, { ObservationRegistry.NOOP })
            .withBean(ObservationHandler::class.java, { SupportingSqsObservationHandler })
            .run { context ->
                context.startupFailure.shouldBeNull()
                context.getBeansOfType(SqsObservationActivation::class.java).size shouldBeEqualTo 0
                context.getBeansOfType(MicrometerSqsListenerInterceptor::class.java).size shouldBeEqualTo 1
                context.getBeansOfType(MicrometerSqsOperations::class.java).size shouldBeEqualTo 1
            }
    }

    @Test
    fun `missing observation handler keeps automatic listener meter and operations`() {
        contextRunner
            .withPropertyValues("bluetape4k.aws.sqs.observation.enabled=true")
            .withBean(SimpleMeterRegistry::class.java, { SimpleMeterRegistry() })
            .withBean(ObservationRegistry::class.java, { ObservationRegistry.create() })
            .run { context ->
                context.startupFailure.shouldBeNull()
                context.getBeansOfType(SqsObservationActivation::class.java).size shouldBeEqualTo 0
                context.getBeansOfType(MicrometerSqsListenerInterceptor::class.java).size shouldBeEqualTo 1
                context.getBeansOfType(MicrometerSqsOperations::class.java).size shouldBeEqualTo 1
            }
    }

    @Test
    fun `active observation keeps full request operations`() {
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(SqsMicrometerAutoConfiguration::class.java))
            .withPropertyValues("bluetape4k.aws.sqs.extended.enabled=true")
            .withBean(SqsCoroutinesTemplate::class.java, { mockk(relaxed = true) })
            .withBean(SimpleMeterRegistry::class.java, { SimpleMeterRegistry() })
            .withBean(SqsObservationActivation::class.java, { SqsObservationActivation() })
            .run { context ->
                context.startupFailure.shouldBeNull()
                context.getBeansOfType(MicrometerSqsListenerInterceptor::class.java).size shouldBeEqualTo 0
                context.getBeansOfType(MicrometerFullRequestSqsOperations::class.java).size shouldBeEqualTo 1
            }
    }

    @Test
    fun `back off when SQS auto configuration disabled`() {
        contextRunner
            .withPropertyValues("bluetape4k.aws.sqs.enabled=false")
            .run { context ->
                context.getBeansOfType(SqsAsyncClient::class.java).size shouldBeEqualTo 0
                context.getBeansOfType(SqsOperations::class.java).size shouldBeEqualTo 0
                context.getBeansOfType(SqsMessageListenerContainerRegistry::class.java).size shouldBeEqualTo 0
            }
    }

    @Test
    fun `custom operations bean backs off template`() {
        contextRunner
            .withBean(SqsOperations::class.java, { NoopSqsOperations })
            .run { context ->
                context.getBeansOfType(SqsOperations::class.java).size shouldBeEqualTo 1
                context.getBeansOfType(SqsCoroutinesTemplate::class.java).size shouldBeEqualTo 0
                context.getBean(SqsOperations::class.java) shouldBeSameInstanceAs NoopSqsOperations
            }
    }

    @Test
    fun `endpoint override requires region`() {
        ApplicationContextRunner()
            .withConfiguration(
                AutoConfigurations.of(
                    AwsAutoConfiguration::class.java,
                    SqsAutoConfiguration::class.java,
                )
            )
            .withPropertyValues("bluetape4k.aws.sqs.endpoint-override=http://localhost:4566")
            .run { context ->
                context.startupFailure.shouldNotBeNull()
                val messages = generateSequence(context.startupFailure) { it.cause }
                    .mapNotNull { it.message }
                    .joinToString("\n")
                messages shouldContain "region is required"
            }
    }

    @Test
    fun `shared defaults provide SQS region and endpoint override`() {
        ApplicationContextRunner()
            .withConfiguration(
                AutoConfigurations.of(
                    AwsAutoConfiguration::class.java,
                    SqsAutoConfiguration::class.java,
                )
            )
            .withPropertyValues(
                "bluetape4k.aws.region=us-west-2",
                "bluetape4k.aws.sqs.endpoint-override=http://localhost:4566",
            )
            .run { context ->
                context.startupFailure.shouldBeNull()
                context.getBeansOfType(SqsAsyncClient::class.java).size shouldBeEqualTo 1
            }
    }

    @Test
    fun `global and SQS async customizers are applied in order`() {
        SqsCustomizerConfig.calls.clear()

        contextRunner
            .withUserConfiguration(SqsCustomizerConfig::class.java)
            .run { context ->
                context.getBean(SqsAsyncClient::class.java).shouldNotBeNull()
                SqsCustomizerConfig.calls shouldBeEqualTo listOf("global:sqs", "sqs")
            }
    }

    @Test
    fun `endpoint override binds when region is present`() {
        contextRunner
            .withPropertyValues("bluetape4k.aws.sqs.endpoint-override=http://localhost:4566")
            .run { context ->
                context.startupFailure.shouldBeNull()
                context.getBean(SqsProperties::class.java).endpointOverride.toString() shouldBeEqualTo
                    "http://localhost:4566"
            }
    }

    @Test
    fun `SQS auto configuration backs off when SQS SDK is absent`() {
        contextRunner
            .withClassLoader(FilteredClassLoader("software.amazon.awssdk.services.sqs"))
            .run { context ->
                context.getBeansOfType(SqsAsyncClient::class.java).size shouldBeEqualTo 0
                context.getBeansOfType(SqsOperations::class.java).size shouldBeEqualTo 0
            }
    }

    @Test
    fun `invalid listener properties fail binding`() {
        contextRunner
            .withPropertyValues("bluetape4k.aws.sqs.listener.max-messages=11")
            .run { context ->
                context.startupFailure.shouldNotBeNull()
                val messages = generateSequence(context.startupFailure) { it.cause }
                    .mapNotNull { it.message }
                    .joinToString("\n")
                messages shouldContain "maxMessages"
            }
    }

    @Test
    fun `invalid listener retry properties fail binding`() {
        contextRunner
            .withPropertyValues("bluetape4k.aws.sqs.listener.retry.max-attempts=0")
            .run { context ->
                context.startupFailure.shouldNotBeNull()
                val messages = generateSequence(context.startupFailure) { it.cause }
                    .mapNotNull { it.message }
                    .joinToString("\n")
                messages shouldContain "maxAttempts"
            }
    }

    @Test
    fun `batch endpoint accepts inherited max messages within SQS limit`() {
        contextRunner
            .withPropertyValues("bluetape4k.aws.sqs.listener.max-messages=7")
            .withUserConfiguration(BatchListenerConfig::class.java)
            .run { context ->
                context.startupFailure.shouldBeNull()
            }
    }

    @Test
    fun `batch manual endpoint accepts batch acknowledgement`() {
        contextRunner
            .withUserConfiguration(BatchManualListenerConfig::class.java)
            .run { context ->
                context.startupFailure.shouldBeNull()
            }
    }

    @Test
    fun `batch endpoint rejects max messages above SQS limit`() {
        contextRunner
            .withPropertyValues("bluetape4k.aws.sqs.listener.max-messages=11")
            .withUserConfiguration(BatchListenerConfig::class.java)
            .run { context ->
                context.startupFailure.shouldNotBeNull()
                val messages = generateSequence(context.startupFailure) { it.cause }
                    .mapNotNull { it.message }
                    .joinToString("\n")
                messages shouldContain "maxMessages"
            }
    }

    @Test
    fun `on success rejects acknowledgement parameter`() {
        contextRunner
            .withUserConfiguration(OnSuccessAcknowledgementListenerConfig::class.java)
            .run { context ->
                context.startupFailure.shouldNotBeNull()
                val messages = generateSequence(context.startupFailure) { it.cause }
                    .mapNotNull { it.message }
                    .joinToString("\n")
                messages shouldContain "ON_SUCCESS cannot declare SqsAcknowledgement"
            }
    }

    @Test
    fun `Jackson message converter registers when ObjectMapper is available`() {
        contextRunner
            .withBean(ObjectMapper::class.java, { ObjectMapper() })
            .run { context ->
                context.getBeansOfType(SqsMessageConverter::class.java).size shouldBeEqualTo 1
                context.getBean(SqsMessageConverter::class.java)
                    .javaClass shouldBeEqualTo JacksonSqsMessageConverter::class.java
            }
    }

    @Test
    fun `SpEL queue value fails fast`() {
        contextRunner
            .withUserConfiguration(SpelListenerConfig::class.java)
            .run { context ->
                context.startupFailure.shouldNotBeNull()
                val messages = generateSequence(context.startupFailure) { it.cause }
                    .mapNotNull { it.message }
                    .joinToString("\n")
                messages shouldContain "SpEL is not supported"
            }
    }

    @Test
    fun `unresolved queue placeholder fails fast`() {
        contextRunner
            .withUserConfiguration(UnresolvedPlaceholderListenerConfig::class.java)
            .run { context ->
                context.startupFailure.shouldNotBeNull()
                val messages = generateSequence(context.startupFailure) { it.cause }
                    .mapNotNull { it.message }
                    .joinToString("\n")
                messages shouldContain "Could not resolve placeholder"
            }
    }

    @Test
    fun `unsupported listener signature fails fast`() {
        contextRunner
            .withUserConfiguration(UnsupportedListenerConfig::class.java)
            .run { context ->
                context.startupFailure.shouldNotBeNull()
                val messages = generateSequence(context.startupFailure) { it.cause }
                    .mapNotNull { it.message }
                    .joinToString("\n")
                messages shouldContain "@SqsListener method must have at least one parameter"
            }
    }

    @Test
    fun `listener disabled ignores invalid listener annotations`() {
        contextRunner
            .withPropertyValues("bluetape4k.aws.sqs.listener.enabled=false")
            .withUserConfiguration(SpelListenerConfig::class.java)
            .run { context ->
                context.startupFailure.shouldBeNull()
                context.getBean(SqsMessageListenerContainerRegistry::class.java)
                    .getContainer("listener.handle.#{queueName}")
                    .shouldBeNull()
            }
    }

    @Test
    fun `default listener id contains only the sanitized queue name`() {
        contextRunner
            .withUserConfiguration(UrlQueueListenerConfig::class.java)
            .run { context ->
                context.startupFailure.shouldBeNull()
                val registry = context.getBean(SqsMessageListenerContainerRegistry::class.java)

                registry.getContainer("listener.handle.orders").shouldNotBeNull()
                registry.containers.size shouldBeEqualTo 1
            }
    }

    @Configuration(proxyBeanMethods = false)
    internal class SpelListenerConfig {
        @Bean
        fun listener(): SpelListener = SpelListener()
    }

    internal class SpelListener {
        @SqsListener("#{queueName}")
        fun handle(body: String) {
            check(body.isNotBlank())
        }
    }

    @Configuration(proxyBeanMethods = false)
    internal class UrlQueueListenerConfig {
        @Bean
        fun listener(): UrlQueueListener = UrlQueueListener()
    }

    internal class UrlQueueListener {
        @SqsListener(
            queue = "https://user:password@sqs.us-east-1.amazonaws.com/123456789012/orders?token=secret",
            autoStartup = false,
        )
        fun handle(body: String) {
            check(body.isNotBlank())
        }
    }

    @Test
    fun `single endpoint rejects batch acknowledgement`() {
        contextRunner
            .withUserConfiguration(SingleBatchAcknowledgementListenerConfig::class.java)
            .run { context ->
                val messages = generateSequence(context.startupFailure) { it.cause }
                    .mapNotNull { it.message }
                    .joinToString("\n")
                messages shouldContain "SqsBatchAcknowledgement requires batch=true"
            }
    }

    @Test
    fun `manual batch endpoint rejects single acknowledgement`() {
        contextRunner
            .withUserConfiguration(BatchSingleAcknowledgementListenerConfig::class.java)
            .run { context ->
                val messages = generateSequence(context.startupFailure) { it.cause }
                    .mapNotNull { it.message }
                    .joinToString("\n")
                messages shouldContain "MANUAL requires SqsBatchAcknowledgement"
            }
    }

    @Configuration(proxyBeanMethods = false)
    internal class UnresolvedPlaceholderListenerConfig {
        @Bean
        fun listener(): UnresolvedPlaceholderListener = UnresolvedPlaceholderListener()
    }

    internal class UnresolvedPlaceholderListener {
        @SqsListener("\${missing.queue-url}")
        fun handle(body: String) {
            check(body.isNotBlank())
        }
    }

    @Configuration(proxyBeanMethods = false)
    internal class UnsupportedListenerConfig {
        @Bean
        fun listener(): UnsupportedListener = UnsupportedListener()
    }

    internal class UnsupportedListener {
        @SqsListener("queue")
        fun handle() = Unit
    }

    @Configuration(proxyBeanMethods = false)
    internal class BatchListenerConfig {
        @Bean
        fun listener(): BatchListener = BatchListener()
    }

    internal class BatchListener {
        @SqsListener(queue = "orders", batch = true)
        fun handle(messages: List<String>) {
            check(messages.isNotEmpty())
        }
    }

    @Configuration(proxyBeanMethods = false)
    internal class BatchManualListenerConfig {
        @Bean
        fun listener(): BatchManualListener = BatchManualListener()
    }

    internal class BatchManualListener {
        @SqsListener(
            queue = "orders",
            batch = true,
            acknowledgementMode = SqsAcknowledgementMode.MANUAL,
        )
        fun handle(messages: List<String>, acknowledgement: SqsBatchAcknowledgement) {
            check(messages.isNotEmpty())
            check(!acknowledgement.completed)
        }
    }

    @Configuration(proxyBeanMethods = false)
    internal class OnSuccessAcknowledgementListenerConfig {
        @Bean
        fun listener(): OnSuccessAcknowledgementListener = OnSuccessAcknowledgementListener()
    }

    internal class OnSuccessAcknowledgementListener {
        @SqsListener(queue = "orders", acknowledgementMode = SqsAcknowledgementMode.ON_SUCCESS)
        fun handle(body: String, acknowledgement: SqsAcknowledgement) {
            check(body.isNotBlank())
            check(!acknowledgement.completed)
        }
    }

    @Configuration(proxyBeanMethods = false)
    internal class SingleBatchAcknowledgementListenerConfig {
        @Bean
        fun listener(): SingleBatchAcknowledgementListener = SingleBatchAcknowledgementListener()
    }

    internal class SingleBatchAcknowledgementListener {
        @SqsListener("orders")
        fun handle(acknowledgement: SqsBatchAcknowledgement) {
            check(!acknowledgement.completed)
        }
    }

    @Configuration(proxyBeanMethods = false)
    internal class BatchSingleAcknowledgementListenerConfig {
        @Bean
        fun listener(): BatchSingleAcknowledgementListener = BatchSingleAcknowledgementListener()
    }

    internal class BatchSingleAcknowledgementListener {
        @SqsListener(queue = "orders", batch = true, acknowledgementMode = SqsAcknowledgementMode.MANUAL)
        fun handle(messages: List<String>, acknowledgement: SqsAcknowledgement) {
            check(messages.isNotEmpty())
            check(!acknowledgement.completed)
        }
    }

    @Configuration(proxyBeanMethods = false)
    internal class SqsCustomizerConfig {
        @Bean
        fun globalAsyncCustomizer(): AwsAsyncClientCustomizer =
            RecordingAsyncCustomizer("global")

        @Bean
        fun sqsClientCustomizer(): AwsClientCustomizer<SqsAsyncClientBuilder> =
            AwsClientCustomizer { calls += "sqs" }

        private class RecordingAsyncCustomizer(
            private val name: String,
        ): AwsAsyncClientCustomizer, Ordered {
            override fun customize(
                context: AwsClientCustomizationContext,
                builder: AwsAsyncClientBuilder<*, *>,
            ) {
                calls += "$name:${context.serviceName}"
            }

            override fun getOrder(): Int = 0
        }

        companion object {
            val calls: MutableList<String> = mutableListOf()
        }
    }

    private object SupportingSqsObservationHandler : ObservationHandler<Observation.Context> {
        override fun onStart(context: Observation.Context) = Unit

        override fun supportsContext(context: Observation.Context): Boolean =
            context is SqsObservationContext && context.metadata.stage == SqsObservationStage.PROCESS
    }

    private object UserSqsListenerInterceptor : SqsListenerInterceptor
}
