package io.bluetape4k.aws.spring.kinesis

import io.bluetape4k.aws.spring.AwsAsyncClientCustomizer
import io.bluetape4k.aws.spring.AwsAutoConfiguration
import io.bluetape4k.aws.spring.AwsClientCustomizationContext
import io.bluetape4k.aws.spring.AwsClientCustomizer
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldBeSameInstanceAs
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.assertions.shouldNotBeNull
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.FilteredClassLoader
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.Ordered
import software.amazon.awssdk.awscore.client.builder.AwsAsyncClientBuilder
import software.amazon.awssdk.services.kinesis.KinesisAsyncClient
import software.amazon.awssdk.services.kinesis.KinesisAsyncClientBuilder
import java.time.Duration

class KinesisAutoConfigurationTest {

    private val contextRunner = ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                AwsAutoConfiguration::class.java,
                KinesisAutoConfiguration::class.java,
            )
        )
        .withPropertyValues("bluetape4k.aws.kinesis.region=us-east-1")

    @Test
    fun `register Kinesis client and operations`() {
        contextRunner.run { context ->
            context.getBeansOfType(KinesisAsyncClient::class.java).size shouldBeEqualTo 1
            context.getBeansOfType(KinesisProperties::class.java).size shouldBeEqualTo 1
            context.getBeansOfType(KinesisOperations::class.java).size shouldBeEqualTo 1
            context.getBeansOfType(KinesisCoroutinesTemplate::class.java).size shouldBeEqualTo 1
        }
    }

    @Test
    fun `back off when Kinesis auto configuration disabled`() {
        contextRunner
            .withPropertyValues("bluetape4k.aws.kinesis.enabled=false")
            .run { context ->
                context.getBeansOfType(KinesisAsyncClient::class.java).size shouldBeEqualTo 0
                context.getBeansOfType(KinesisOperations::class.java).size shouldBeEqualTo 0
            }
    }

    @Test
    fun `custom client bean backs off auto configured client`() {
        val customClient = mockk<KinesisAsyncClient>(relaxed = true)

        contextRunner
            .withBean(KinesisAsyncClient::class.java, { customClient })
            .run { context ->
                context.getBeansOfType(KinesisAsyncClient::class.java).size shouldBeEqualTo 1
                context.getBean(KinesisAsyncClient::class.java) shouldBeSameInstanceAs customClient
                context.getBeansOfType(KinesisOperations::class.java).size shouldBeEqualTo 1
            }
    }

    @Test
    fun `custom operations bean backs off template`() {
        contextRunner
            .withBean(KinesisOperations::class.java, { NoopKinesisOperations })
            .run { context ->
                context.getBeansOfType(KinesisOperations::class.java).size shouldBeEqualTo 1
                context.getBeansOfType(KinesisCoroutinesTemplate::class.java).size shouldBeEqualTo 0
                context.getBean(KinesisOperations::class.java) shouldBeSameInstanceAs NoopKinesisOperations
            }
    }

    @Test
    fun `endpoint override requires region`() {
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(KinesisAutoConfiguration::class.java))
            .withPropertyValues("bluetape4k.aws.kinesis.endpoint-override=http://localhost:4566")
            .run { context ->
                context.startupFailure.shouldNotBeNull()
                val messages = generateSequence(context.startupFailure) { it.cause }
                    .mapNotNull { it.message }
                    .joinToString("\n")
                messages shouldContain "region is required"
            }
    }

    @Test
    fun `shared defaults provide Kinesis region and endpoint override`() {
        ApplicationContextRunner()
            .withConfiguration(
                AutoConfigurations.of(
                    AwsAutoConfiguration::class.java,
                    KinesisAutoConfiguration::class.java,
                )
            )
            .withPropertyValues(
                "bluetape4k.aws.region=us-west-2",
                "bluetape4k.aws.kinesis.endpoint-override=http://localhost:4566",
            )
            .run { context ->
                context.startupFailure.shouldBeNull()
                context.getBeansOfType(KinesisAsyncClient::class.java).size shouldBeEqualTo 1
            }
    }

    @Test
    fun `global and Kinesis async customizers are applied in order`() {
        KinesisCustomizerConfig.calls.clear()

        contextRunner
            .withUserConfiguration(KinesisCustomizerConfig::class.java)
            .run { context ->
                context.getBean(KinesisAsyncClient::class.java).shouldNotBeNull()
                KinesisCustomizerConfig.calls shouldBeEqualTo listOf("global:kinesis", "kinesis")
            }
    }

    @Test
    fun `Kinesis auto configuration backs off when Kinesis SDK is absent`() {
        contextRunner
            .withClassLoader(FilteredClassLoader("software.amazon.awssdk.services.kinesis"))
            .run { context ->
                context.getBeansOfType(KinesisAsyncClient::class.java).size shouldBeEqualTo 0
                context.getBeansOfType(KinesisOperations::class.java).size shouldBeEqualTo 0
            }
    }

    @Test
    fun `configured stream and consumer properties bind`() {
        contextRunner
            .withPropertyValues(
                "bluetape4k.aws.kinesis.streams.orders.shard-count=3",
                "bluetape4k.aws.kinesis.consumer.batch-limit=250",
                "bluetape4k.aws.kinesis.consumer.poll-interval=500ms",
                "bluetape4k.aws.kinesis.consumer.empty-backoff=2s",
                "bluetape4k.aws.kinesis.consumer.max-iterator-retries=4",
                "bluetape4k.aws.kinesis.consumer.max-throttle-retries=6",
                "bluetape4k.aws.kinesis.consumer.initial-throttle-backoff=100ms",
                "bluetape4k.aws.kinesis.consumer.max-throttle-backoff=5s",
                "bluetape4k.aws.kinesis.consumer.jitter-ratio=0.5",
            )
            .run { context ->
                val properties = context.getBean(KinesisProperties::class.java)

                properties.streams["orders"]?.shardCount shouldBeEqualTo 3
                properties.consumer.batchLimit shouldBeEqualTo 250
                properties.consumer.pollInterval shouldBeEqualTo Duration.ofMillis(500)
                properties.consumer.emptyBackoff shouldBeEqualTo Duration.ofSeconds(2)
                properties.consumer.maxIteratorRetries shouldBeEqualTo 4
                properties.consumer.maxThrottleRetries shouldBeEqualTo 6
                properties.consumer.initialThrottleBackoff shouldBeEqualTo Duration.ofMillis(100)
                properties.consumer.maxThrottleBackoff shouldBeEqualTo Duration.ofSeconds(5)
                properties.consumer.jitterRatio shouldBeEqualTo 0.5
            }
    }

    @Test
    fun `invalid consumer properties fail binding`() {
        contextRunner
            .withPropertyValues("bluetape4k.aws.kinesis.consumer.batch-limit=0")
            .run { context ->
                context.startupFailure.shouldNotBeNull()
                val messages = generateSequence(context.startupFailure) { it.cause }
                    .mapNotNull { it.message }
                    .joinToString("\n")
                messages shouldContain "batchLimit"
            }
    }

    @Configuration(proxyBeanMethods = false)
    internal class KinesisCustomizerConfig {
        @Bean
        fun globalAsyncCustomizer(): AwsAsyncClientCustomizer =
            RecordingAsyncCustomizer("global")

        @Bean
        fun kinesisClientCustomizer(): AwsClientCustomizer<KinesisAsyncClientBuilder> =
            AwsClientCustomizer { calls += "kinesis" }

        private class RecordingAsyncCustomizer(
            private val name: String,
        ) : AwsAsyncClientCustomizer, Ordered {
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
