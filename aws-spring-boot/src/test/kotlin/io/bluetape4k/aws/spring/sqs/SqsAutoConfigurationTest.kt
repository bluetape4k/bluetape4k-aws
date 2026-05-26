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

class SqsAutoConfigurationTest {

    private val contextRunner = ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                AwsAutoConfiguration::class.java,
                SqsAutoConfiguration::class.java,
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
                messages shouldContain "@SqsListener method must have exactly one parameter"
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
}
