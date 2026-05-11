package io.bluetape4k.aws.spring.sqs

import io.bluetape4k.aws.spring.AwsAutoConfiguration
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.FilteredClassLoader
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import software.amazon.awssdk.services.sqs.SqsAsyncClient

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
            assertThat(context).hasSingleBean(SqsAsyncClient::class.java)
            assertThat(context).hasSingleBean(SqsProperties::class.java)
            assertThat(context).hasSingleBean(SqsOperations::class.java)
            assertThat(context).hasSingleBean(SqsCoroutinesTemplate::class.java)
            assertThat(context).hasSingleBean(SqsMessageListenerContainerRegistry::class.java)
            assertThat(context).hasSingleBean(SqsListenerAnnotationBeanPostProcessor::class.java)
        }
    }

    @Test
    fun `back off when SQS auto configuration disabled`() {
        contextRunner
            .withPropertyValues("bluetape4k.aws.sqs.enabled=false")
            .run { context ->
                assertThat(context).doesNotHaveBean(SqsAsyncClient::class.java)
                assertThat(context).doesNotHaveBean(SqsOperations::class.java)
                assertThat(context).doesNotHaveBean(SqsMessageListenerContainerRegistry::class.java)
            }
    }

    @Test
    fun `custom operations bean backs off template`() {
        contextRunner
            .withBean(SqsOperations::class.java, { NoopSqsOperations })
            .run { context ->
                assertThat(context).hasSingleBean(SqsOperations::class.java)
                assertThat(context).doesNotHaveBean(SqsCoroutinesTemplate::class.java)
                assertThat(context.getBean(SqsOperations::class.java)).isSameAs(NoopSqsOperations)
            }
    }

    @Test
    fun `endpoint override requires region`() {
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(SqsAutoConfiguration::class.java))
            .withPropertyValues("bluetape4k.aws.sqs.endpoint-override=http://localhost:4566")
            .run { context ->
                assertThat(context).hasFailed()
                val messages = generateSequence(context.startupFailure) { it.cause }
                    .mapNotNull { it.message }
                    .joinToString("\n")
                assertThat(messages).contains("region is required")
            }
    }

    @Test
    fun `endpoint override binds when region is present`() {
        contextRunner
            .withPropertyValues("bluetape4k.aws.sqs.endpoint-override=http://localhost:4566")
            .run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context.getBean(SqsProperties::class.java).endpointOverride.toString())
                    .isEqualTo("http://localhost:4566")
            }
    }

    @Test
    fun `SQS auto configuration backs off when SQS SDK is absent`() {
        contextRunner
            .withClassLoader(FilteredClassLoader("software.amazon.awssdk.services.sqs"))
            .run { context ->
                assertThat(context).doesNotHaveBean(SqsAsyncClient::class.java)
                assertThat(context).doesNotHaveBean(SqsOperations::class.java)
            }
    }

    @Test
    fun `invalid listener properties fail binding`() {
        contextRunner
            .withPropertyValues("bluetape4k.aws.sqs.listener.max-messages=11")
            .run { context ->
                assertThat(context).hasFailed()
                val messages = generateSequence(context.startupFailure) { it.cause }
                    .mapNotNull { it.message }
                    .joinToString("\n")
                assertThat(messages).contains("maxMessages")
            }
    }

    @Test
    fun `SpEL queue value fails fast`() {
        contextRunner
            .withUserConfiguration(SpelListenerConfig::class.java)
            .run { context ->
                assertThat(context).hasFailed()
                val messages = generateSequence(context.startupFailure) { it.cause }
                    .mapNotNull { it.message }
                    .joinToString("\n")
                assertThat(messages).contains("SpEL is not supported")
            }
    }

    @Test
    fun `unresolved queue placeholder fails fast`() {
        contextRunner
            .withUserConfiguration(UnresolvedPlaceholderListenerConfig::class.java)
            .run { context ->
                assertThat(context).hasFailed()
                val messages = generateSequence(context.startupFailure) { it.cause }
                    .mapNotNull { it.message }
                    .joinToString("\n")
                assertThat(messages).contains("Could not resolve placeholder")
            }
    }

    @Test
    fun `unsupported listener signature fails fast`() {
        contextRunner
            .withUserConfiguration(UnsupportedListenerConfig::class.java)
            .run { context ->
                assertThat(context).hasFailed()
                val messages = generateSequence(context.startupFailure) { it.cause }
                    .mapNotNull { it.message }
                    .joinToString("\n")
                assertThat(messages).contains("@SqsListener method must have exactly one parameter")
            }
    }

    @Test
    fun `listener disabled ignores invalid listener annotations`() {
        contextRunner
            .withPropertyValues("bluetape4k.aws.sqs.listener.enabled=false")
            .withUserConfiguration(SpelListenerConfig::class.java)
            .run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context.getBean(SqsMessageListenerContainerRegistry::class.java)
                    .getContainer("listener.handle.#{queueName}")).isNull()
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
}
