package io.bluetape4k.aws.spring.cloudwatch

import io.bluetape4k.aws.spring.AwsAutoConfiguration
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeSameInstanceAs
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.assertions.shouldNotBeNull
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.clearMocks
import io.mockk.mockk
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.FilteredClassLoader
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import software.amazon.awssdk.services.cloudwatch.CloudWatchAsyncClient
import software.amazon.awssdk.services.cloudwatchlogs.CloudWatchLogsAsyncClient

class CloudWatchAutoConfigurationTest {

    private val customCloudWatchClient = mockk<CloudWatchAsyncClient>(relaxed = true)
    private val customCloudWatchLogsClient = mockk<CloudWatchLogsAsyncClient>(relaxed = true)

    private val contextRunner = ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                AwsAutoConfiguration::class.java,
                CloudWatchAutoConfiguration::class.java,
                CloudWatchLogsAutoConfiguration::class.java,
            )
        )
        .withPropertyValues(
            "bluetape4k.aws.cloudwatch.region=us-east-1",
            "bluetape4k.aws.cloudwatch.namespace=Test/App",
            "bluetape4k.aws.cloudwatch-logs.region=us-east-1",
            "bluetape4k.aws.cloudwatch-logs.log-group-name=/app/test",
            "bluetape4k.aws.cloudwatch-logs.log-stream-name=default",
        )

    @BeforeEach
    fun resetMocks() {
        clearMocks(customCloudWatchClient, customCloudWatchLogsClient)
    }

    @Test
    fun `register CloudWatch and CloudWatch Logs clients and operations`() {
        contextRunner.run { context ->
            context.getBeansOfType(CloudWatchAsyncClient::class.java).size shouldBeEqualTo 1
            context.getBeansOfType(CloudWatchOperations::class.java).size shouldBeEqualTo 1
            context.getBeansOfType(CloudWatchLogsAsyncClient::class.java).size shouldBeEqualTo 1
            context.getBeansOfType(CloudWatchLogsOperations::class.java).size shouldBeEqualTo 1
        }
    }

    @Test
    fun `register Micrometer publishing helper when registry exists`() {
        contextRunner
            .withBean(SimpleMeterRegistry::class.java, { SimpleMeterRegistry() })
            .run { context ->
                context.getBeansOfType(CloudWatchMeterPublishingOperations::class.java).size shouldBeEqualTo 1
            }
    }

    @Test
    fun `Micrometer publishing helper backs off without registry`() {
        contextRunner.run { context ->
            context.getBeansOfType(CloudWatchMeterPublishingOperations::class.java).size shouldBeEqualTo 0
        }
    }

    @Test
    fun `Micrometer publishing helper can be disabled`() {
        contextRunner
            .withBean(SimpleMeterRegistry::class.java, { SimpleMeterRegistry() })
            .withPropertyValues("bluetape4k.aws.cloudwatch.micrometer.enabled=false")
            .run { context ->
                context.getBeansOfType(CloudWatchMeterPublishingOperations::class.java).size shouldBeEqualTo 0
            }
    }

    @Test
    fun `custom CloudWatch client backs off auto configured client`() {
        contextRunner
            .withBean(CloudWatchAsyncClient::class.java, { customCloudWatchClient })
            .run { context ->
                context.getBeansOfType(CloudWatchAsyncClient::class.java).size shouldBeEqualTo 1
                context.getBean(CloudWatchAsyncClient::class.java) shouldBeSameInstanceAs customCloudWatchClient
                context.getBeansOfType(CloudWatchOperations::class.java).size shouldBeEqualTo 1
            }
    }

    @Test
    fun `custom CloudWatch Logs client backs off auto configured client`() {
        contextRunner
            .withBean(CloudWatchLogsAsyncClient::class.java, { customCloudWatchLogsClient })
            .run { context ->
                context.getBeansOfType(CloudWatchLogsAsyncClient::class.java).size shouldBeEqualTo 1
                context.getBean(CloudWatchLogsAsyncClient::class.java) shouldBeSameInstanceAs customCloudWatchLogsClient
                context.getBeansOfType(CloudWatchLogsOperations::class.java).size shouldBeEqualTo 1
            }
    }

    @Test
    fun `CloudWatch auto configuration backs off when disabled`() {
        contextRunner
            .withPropertyValues("bluetape4k.aws.cloudwatch.enabled=false")
            .run { context ->
                context.getBeansOfType(CloudWatchAsyncClient::class.java).size shouldBeEqualTo 0
                context.getBeansOfType(CloudWatchOperations::class.java).size shouldBeEqualTo 0
            }
    }

    @Test
    fun `CloudWatch Logs auto configuration backs off when disabled`() {
        contextRunner
            .withPropertyValues("bluetape4k.aws.cloudwatch-logs.enabled=false")
            .run { context ->
                context.getBeansOfType(CloudWatchLogsAsyncClient::class.java).size shouldBeEqualTo 0
                context.getBeansOfType(CloudWatchLogsOperations::class.java).size shouldBeEqualTo 0
            }
    }

    @Test
    fun `CloudWatch endpoint override requires region`() {
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(CloudWatchAutoConfiguration::class.java))
            .withPropertyValues("bluetape4k.aws.cloudwatch.endpoint-override=http://localhost:4566")
            .run { context ->
                context.startupFailure.shouldNotBeNull()
                val messages = generateSequence(context.startupFailure) { it.cause }
                    .mapNotNull { it.message }
                    .joinToString("\n")
                messages shouldContain "region is required"
            }
    }

    @Test
    fun `CloudWatch Logs endpoint override requires region`() {
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(CloudWatchLogsAutoConfiguration::class.java))
            .withPropertyValues("bluetape4k.aws.cloudwatch-logs.endpoint-override=http://localhost:4566")
            .run { context ->
                context.startupFailure.shouldNotBeNull()
                val messages = generateSequence(context.startupFailure) { it.cause }
                    .mapNotNull { it.message }
                    .joinToString("\n")
                messages shouldContain "region is required"
            }
    }

    @Test
    fun `CloudWatch properties bind`() {
        contextRunner
            .withPropertyValues(
                "bluetape4k.aws.cloudwatch.batch-size=250",
                "bluetape4k.aws.cloudwatch.micrometer.enabled=false",
                "bluetape4k.aws.cloudwatch.micrometer.registry.enabled=true",
                "bluetape4k.aws.cloudwatch.micrometer.registry.namespace=orders-native",
                "bluetape4k.aws.cloudwatch.micrometer.registry.step=59s",
                "bluetape4k.aws.cloudwatch.micrometer.registry.batch-size=20",
                "bluetape4k.aws.cloudwatch.micrometer.registry.read-timeout=15s",
                "bluetape4k.aws.cloudwatch.micrometer.registry.common-tags.application=orders",
                "bluetape4k.aws.cloudwatch.micrometer.registry.filters.includes=orders.,http.server.requests",
                "bluetape4k.aws.cloudwatch.micrometer.registry.filters.excludes=jvm.",
            )
            .run { context ->
                val properties = context.getBean(CloudWatchProperties::class.java)
                properties.namespace shouldBeEqualTo "Test/App"
                properties.batchSize shouldBeEqualTo 250
                properties.micrometer.enabled shouldBeEqualTo false
                properties.micrometer.registry.enabled shouldBeEqualTo true
                properties.micrometer.registry.namespace shouldBeEqualTo "orders-native"
                properties.micrometer.registry.step shouldBeEqualTo java.time.Duration.ofSeconds(59)
                properties.micrometer.registry.batchSize shouldBeEqualTo 20
                properties.micrometer.registry.readTimeout shouldBeEqualTo java.time.Duration.ofSeconds(15)
                properties.micrometer.registry.commonTags["application"] shouldBeEqualTo "orders"
                properties.micrometer.registry.filters.includes shouldContain "orders."
                properties.micrometer.registry.filters.excludes shouldContain "jvm."
            }
    }

    @Test
    fun `native registry property defaults to disabled`() {
        contextRunner.run { context ->
            val properties = context.getBean(CloudWatchProperties::class.java)
            properties.micrometer.registry.enabled shouldBeEqualTo false
            properties.micrometer.registry.batchSize shouldBeEqualTo 20
        }
    }

    @Test
    fun `native registry rejects invalid step and read timeout`() {
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(CloudWatchAutoConfiguration::class.java))
            .withPropertyValues(
                "bluetape4k.aws.cloudwatch.micrometer.registry.step=500ms",
                "bluetape4k.aws.cloudwatch.micrometer.registry.read-timeout=6m",
            )
            .run { context ->
                context.startupFailure.shouldNotBeNull()
                val messages = generateSequence(context.startupFailure) { it.cause }
                    .mapNotNull { it.message }
                    .joinToString("\n")
                messages shouldContain "micrometer.registry"
        }
    }

    @Test
    fun `native registry rejects an invalid batch size`() {
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(CloudWatchAutoConfiguration::class.java))
            .withPropertyValues("bluetape4k.aws.cloudwatch.micrometer.registry.batch-size=0")
            .run { context ->
                context.startupFailure.shouldNotBeNull()
                val messages = generateSequence(context.startupFailure) { it.cause }
                    .mapNotNull { it.message }
                    .joinToString("\n")
                messages shouldContain "micrometer.registry.batch-size"
            }
    }

    @Test
    fun `native registry property accepts top level namespace as fallback`() {
        contextRunner
            .withPropertyValues("bluetape4k.aws.cloudwatch.micrometer.registry.enabled=true")
            .run { context ->
                val properties = context.getBean(CloudWatchProperties::class.java)
                properties.micrometer.registry.namespace shouldBeEqualTo null
                properties.namespace shouldBeEqualTo "Test/App"
            }
    }

    @Test
    fun `CloudWatch Logs properties bind`() {
        contextRunner
            .withPropertyValues("bluetape4k.aws.cloudwatch-logs.batch-size=500")
            .run { context ->
                val properties = context.getBean(CloudWatchLogsProperties::class.java)
                properties.logGroupName shouldBeEqualTo "/app/test"
                properties.logStreamName shouldBeEqualTo "default"
                properties.batchSize shouldBeEqualTo 500
            }
    }

    @Test
    fun `CloudWatch auto configuration backs off when SDK is absent`() {
        contextRunner
            .withClassLoader(FilteredClassLoader("software.amazon.awssdk.services.cloudwatch"))
            .run { context ->
                context.getBeansOfType(CloudWatchAsyncClient::class.java).size shouldBeEqualTo 0
                context.getBeansOfType(CloudWatchOperations::class.java).size shouldBeEqualTo 0
            }
    }

    @Test
    fun `CloudWatch Logs auto configuration backs off when SDK is absent`() {
        contextRunner
            .withClassLoader(FilteredClassLoader("software.amazon.awssdk.services.cloudwatchlogs"))
            .run { context ->
                context.getBeansOfType(CloudWatchLogsAsyncClient::class.java).size shouldBeEqualTo 0
                context.getBeansOfType(CloudWatchLogsOperations::class.java).size shouldBeEqualTo 0
            }
    }
}
