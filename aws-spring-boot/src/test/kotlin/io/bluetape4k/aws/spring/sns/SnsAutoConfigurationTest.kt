package io.bluetape4k.aws.spring.sns

import io.bluetape4k.aws.spring.AwsAutoConfiguration
import io.bluetape4k.aws.spring.AwsClientCustomizer
import io.bluetape4k.aws.spring.connection.SnsConnectionDetails
import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeInstanceOf
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldBeSameInstanceAs
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.assertions.shouldHaveSize
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.FilteredClassLoader
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import software.amazon.awssdk.services.sns.SnsAsyncClient
import software.amazon.awssdk.services.sns.SnsAsyncClientBuilder
import software.amazon.awssdk.regions.Region
import java.net.URI

class SnsAutoConfigurationTest {

    private val contextRunner = ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                AwsAutoConfiguration::class.java,
                SnsAutoConfiguration::class.java,
            )
        )
        .withPropertyValues("bluetape4k.aws.sns.region=us-east-1")

    @Test
    fun `register SNS client and operations`() {
        contextRunner.run { context ->
            context.getBeansOfType(SnsAsyncClient::class.java) shouldHaveSize 1
            context.getBeansOfType(SnsProperties::class.java) shouldHaveSize 1
            context.getBeansOfType(SnsOperations::class.java) shouldHaveSize 1
            context.getBeansOfType(SnsCoroutinesTemplate::class.java) shouldHaveSize 1
            context.getBeansOfType(SnsTopicArnCache::class.java) shouldHaveSize 1
            context.getBeansOfType(SnsTopicArnResolver::class.java) shouldHaveSize 1
        }
    }

    @Test
    fun `topic arn cache properties bind and disabled cache uses noop`() {
        contextRunner
            .withPropertyValues(
                "bluetape4k.aws.sns.account-id=123456789012",
                "bluetape4k.aws.sns.allow-cross-account-topic-arn=true",
                "bluetape4k.aws.sns.topic-arn-cache.enabled=false",
                "bluetape4k.aws.sns.topic-arn-cache.max-size=8",
                "bluetape4k.aws.sns.topic-arn-cache.ttl=10m",
            )
            .run { context ->
                val properties = context.getBean(SnsProperties::class.java)
                properties.accountId shouldBeEqualTo "123456789012"
                properties.allowCrossAccountTopicArn shouldBeEqualTo true
                properties.topicArnCache.enabled shouldBeEqualTo false
                properties.topicArnCache.maxSize shouldBeEqualTo 8
                properties.topicArnCache.ttl.toMinutes() shouldBeEqualTo 10
                context.getBean(SnsTopicArnCache::class.java)
                    .shouldBeInstanceOf<NoopSnsTopicArnCache>()
            }
    }

    @Test
    fun `custom cache and resolver beans take precedence`() {
        val customCache = mockk<SnsTopicArnCache>(relaxed = true)
        val customResolver = mockk<SnsTopicArnResolver>(relaxed = true)

        contextRunner
            .withBean(SnsTopicArnCache::class.java, { customCache })
            .withBean(SnsTopicArnResolver::class.java, { customResolver })
            .run { context ->
                context.getBean(SnsTopicArnCache::class.java) shouldBeSameInstanceAs customCache
                context.getBean(SnsTopicArnResolver::class.java) shouldBeSameInstanceAs customResolver
            }
    }

    @Test
    fun `connection details provide effective resolver endpoint and region`() {
        contextRunner
            .withPropertyValues(
                "bluetape4k.aws.sns.region=us-east-1",
                "bluetape4k.aws.sns.endpoint-override=http://properties:4566",
                "bluetape4k.aws.sns.account-id=123456789012",
            )
            .withBean(SnsConnectionDetails::class.java, {
                TestSnsDetails(URI.create("http://details:4566"), "eu-west-1", "access", "secret")
            })
            .run { context ->
                val resolver = context.getBean(SnsTopicArnResolver::class.java)
                resolver.scope.endpointOverride shouldBeEqualTo URI.create("http://details:4566")
                resolver.scope.region shouldBeEqualTo "eu-west-1"
                resolver.scope.accountId shouldBeEqualTo "123456789012"
            }
    }

    @Test
    fun `back off when SNS auto configuration disabled`() {
        contextRunner
            .withPropertyValues("bluetape4k.aws.sns.enabled=false")
            .run { context ->
                context.getBeansOfType(SnsAsyncClient::class.java) shouldHaveSize 0
                context.getBeansOfType(SnsOperations::class.java) shouldHaveSize 0
            }
    }

    @Test
    fun `custom client bean backs off auto configured client`() {
        val customClient = SnsAsyncClient.builder()
            .region(Region.US_EAST_1)
            .build()

        try {
            contextRunner
                .withBean(SnsAsyncClient::class.java, { customClient })
                .run { context ->
                    context.getBeansOfType(SnsAsyncClient::class.java) shouldHaveSize 1
                    context.getBean(SnsAsyncClient::class.java) shouldBeSameInstanceAs customClient
                    context.getBeansOfType(SnsOperations::class.java) shouldHaveSize 1
                }
        } finally {
            customClient.close()
        }
    }

    @Test
    fun `uninspectable custom client fails resolver creation fast`() {
        val customClient = mockk<SnsAsyncClient>(relaxed = true)
        every { customClient.serviceClientConfiguration() } throws
            IllegalStateException("configuration unavailable")

        contextRunner
            .withBean(SnsAsyncClient::class.java, { customClient })
            .run { context ->
                val messages = generateSequence(context.startupFailure) { it.cause }
                    .mapNotNull { it.message }
                    .joinToString("\n")
                context.startupFailure.shouldNotBeNull()
                messages shouldContain "SNS client identity is unavailable"
                messages shouldContain "custom SnsTopicArnResolver"
            }
    }

    @Test
    fun `custom resolver permits uninspectable custom client`() {
        val customClient = mockk<SnsAsyncClient>(relaxed = true)
        every { customClient.serviceClientConfiguration() } throws
            IllegalStateException("configuration unavailable")
        val customResolver = mockk<SnsTopicArnResolver>(relaxed = true)

        contextRunner
            .withBean(SnsAsyncClient::class.java, { customClient })
            .withBean(SnsTopicArnResolver::class.java, { customResolver })
            .run { context ->
                context.startupFailure.shouldBeNull()
                context.getBean(SnsTopicArnResolver::class.java) shouldBeSameInstanceAs customResolver
            }
    }

    @Test
    fun `custom operations bean backs off template`() {
        contextRunner
            .withBean(SnsOperations::class.java, { NoopSnsOperations })
            .run { context ->
                context.getBeansOfType(SnsOperations::class.java) shouldHaveSize 1
                context.getBeansOfType(SnsCoroutinesTemplate::class.java) shouldHaveSize 0
                context.getBean(SnsOperations::class.java) shouldBeSameInstanceAs NoopSnsOperations
            }
    }

    @Test
    fun `endpoint override requires region`() {
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(SnsAutoConfiguration::class.java))
            .withPropertyValues("bluetape4k.aws.sns.endpoint-override=http://localhost:4566")
            .run { context ->
                context.startupFailure.shouldNotBeNull()
                val messages = generateSequence(context.startupFailure) { it.cause }
                    .mapNotNull { it.message }
                    .joinToString("\n")
                messages shouldContain "region is required"
            }
    }

    @Test
    fun `endpoint override binds when region is present`() {
        contextRunner
            .withPropertyValues("bluetape4k.aws.sns.endpoint-override=http://localhost:4566")
            .run { context ->
                context.startupFailure.shouldBeNull()
                context.getBean(SnsProperties::class.java).endpointOverride.toString() shouldBeEqualTo
                    "http://localhost:4566"
            }
    }

    @Test
    fun `SNS auto configuration backs off when SNS SDK is absent`() {
        contextRunner
            .withClassLoader(FilteredClassLoader("software.amazon.awssdk.services.sns"))
            .run { context ->
                context.getBeansOfType(SnsAsyncClient::class.java) shouldHaveSize 0
                context.getBeansOfType(SnsOperations::class.java) shouldHaveSize 0
            }
    }

    @Test
    fun `FIFO throughput scope property binds`() {
        contextRunner
            .withPropertyValues(
                "bluetape4k.aws.sns.topics[orders.fifo].fifo=true",
                "bluetape4k.aws.sns.topics[orders.fifo].fifo-throughput-scope=message-group",
            )
            .run { context ->
                val properties = context.getBean(SnsProperties::class.java)

                properties.topics["orders.fifo"]?.fifoThroughputScope shouldBeEqualTo
                    SnsFifoThroughputScope.MESSAGE_GROUP
            }
    }

    @Test
    fun `configured FIFO topic name is validated by template`() {
        contextRunner
            .withPropertyValues("bluetape4k.aws.sns.topics.orders.fifo=true")
            .run { context ->
                val operations = context.getBean(SnsOperations::class.java)

                val error = assertFailsWith<IllegalArgumentException> {
                    runSuspendIO {
                        operations.createConfiguredTopic("orders")
                    }
                }
                error.message.orEmpty() shouldContain "FIFO topic name"
            }
    }

    @Test
    fun `missing configured topic fails fast`() {
        contextRunner.run { context ->
            val operations = context.getBean(SnsOperations::class.java)

            val error = assertFailsWith<IllegalArgumentException> {
                runSuspendIO {
                    operations.createConfiguredTopic("missing")
                }
            }
            error.message.orEmpty() shouldContain "not configured"
        }
    }

    @Test
    fun `global AWS defaults provide effective resolver endpoint and region`() {
        ApplicationContextRunner()
            .withConfiguration(
                AutoConfigurations.of(
                    AwsAutoConfiguration::class.java,
                    SnsAutoConfiguration::class.java,
                )
            )
            .withPropertyValues(
                "bluetape4k.aws.region=ap-northeast-2",
                "bluetape4k.aws.endpoint-override=http://global:4566",
                "bluetape4k.aws.sns.account-id=123456789012",
            )
            .run { context ->
                val resolver = context.getBean(SnsTopicArnResolver::class.java)
                resolver.scope.endpointOverride shouldBeEqualTo URI.create("http://global:4566")
                resolver.scope.region shouldBeEqualTo "ap-northeast-2"
            }
    }

    @Test
    fun `SDK default region becomes effective resolver scope`() {
        ApplicationContextRunner()
            .withConfiguration(
                AutoConfigurations.of(
                    AwsAutoConfiguration::class.java,
                    SnsAutoConfiguration::class.java,
                )
            )
            .withSystemProperties("aws.region=us-east-1")
            .run { context ->
                context.startupFailure.shouldBeNull()
                context.getBean(SnsTopicArnResolver::class.java).scope.region shouldBeEqualTo "us-east-1"
            }
    }

    @Test
    fun `identity changing client customizer fails resolver scope fast`() {
        contextRunner
            .withBean(
                AwsClientCustomizer::class.java,
                {
                    AwsClientCustomizer<SnsAsyncClientBuilder> { builder ->
                        builder.endpointOverride(URI.create("http://customizer:4566"))
                    }
                },
            )
            .run { context ->
                val messages = generateSequence(context.startupFailure) { it.cause }
                    .mapNotNull { it.message }
                    .joinToString("\n")
                context.startupFailure.shouldNotBeNull()
                messages shouldContain "must not change explicitly configured endpoint or region"
            }
    }

    private class TestSnsDetails(
        override val endpoint: URI,
        override val region: String,
        override val accessKey: String,
        override val secretKey: String,
    ): SnsConnectionDetails
}
