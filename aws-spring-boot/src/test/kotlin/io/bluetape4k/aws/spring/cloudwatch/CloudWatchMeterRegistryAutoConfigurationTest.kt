package io.bluetape4k.aws.spring.cloudwatch

import io.bluetape4k.assertions.shouldBeEmpty
import io.bluetape4k.assertions.shouldBeSameInstanceAs
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.assertions.shouldHaveSize
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.aws.spring.AwsAutoConfiguration
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.micrometer.cloudwatch2.CloudWatchMeterRegistry
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.composite.CompositeMeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.clearMocks
import io.mockk.mockk
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.getBean
import org.springframework.beans.factory.getBeansOfType
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.micrometer.metrics.autoconfigure.CompositeMeterRegistryAutoConfiguration
import org.springframework.boot.micrometer.metrics.autoconfigure.MetricsAutoConfiguration
import org.springframework.boot.micrometer.metrics.autoconfigure.export.simple.SimpleMetricsExportAutoConfiguration
import org.springframework.boot.test.context.FilteredClassLoader
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import software.amazon.awssdk.services.cloudwatch.CloudWatchAsyncClient
import java.util.function.Supplier

class CloudWatchMeterRegistryAutoConfigurationTest {

    companion object: KLogging()

    private val customCloudWatchClient = mockk<CloudWatchAsyncClient>(relaxed = true)

    private val contextRunner = ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                AwsAutoConfiguration::class.java,
                CloudWatchAutoConfiguration::class.java,
                CloudWatchMeterRegistryAutoConfiguration::class.java,
            ),
        )
        .withBean(CloudWatchAsyncClient::class.java, { customCloudWatchClient })
        .withPropertyValues(
            "bluetape4k.aws.cloudwatch.region=us-east-1",
            "bluetape4k.aws.cloudwatch.namespace=Test/App",
            "bluetape4k.aws.cloudwatch.micrometer.registry.enabled=true",
        )

    @BeforeEach
    fun beforeEach() {
        clearMocks(customCloudWatchClient)
    }

    @Test
    fun `native registry is created only when opted in`() {
        contextRunner.run { context ->
            context.getBeansOfType<CloudWatchMeterRegistry>() shouldHaveSize 1
            context.getBean<CloudWatchAsyncClient>() shouldBeSameInstanceAs customCloudWatchClient
        }
    }

    @Test
    fun `native registry is disabled by default`() {
        contextRunner
            .withPropertyValues("bluetape4k.aws.cloudwatch.micrometer.registry.enabled=false")
            .run { context ->
                context.getBeansOfType<CloudWatchMeterRegistry>().shouldBeEmpty()
            }
    }

    @Test
    fun `native registry backs off when user meter registry exists`() {
        contextRunner
            .withBean(SimpleMeterRegistry::class.java, Supplier { SimpleMeterRegistry() })
            .run { context ->
                context.getBeansOfType<CloudWatchMeterRegistry>().shouldBeEmpty()
                context.getBeansOfType<MeterRegistry>() shouldHaveSize 1
            }
    }

    @Test
    fun `native registry backs off when user composite registry exists`() {
        contextRunner
            .withBean(CompositeMeterRegistry::class.java, { CompositeMeterRegistry() })
            .run { context ->
                context.getBeansOfType<CloudWatchMeterRegistry>().shouldBeEmpty()
            }
    }

    @Test
    fun `native registry backs off when exporter dependency is absent`() {
        contextRunner
            .withClassLoader(FilteredClassLoader("io.micrometer.cloudwatch2"))
            .run { context ->
                context.getBeansOfType<CloudWatchMeterRegistry>().shouldBeEmpty()
            }
    }

    @Test
    fun `native registry backs off when shared cloudwatch is disabled`() {
        contextRunner
            .withPropertyValues("bluetape4k.aws.cloudwatch.enabled=false")
            .run { context ->
                context.getBeansOfType<CloudWatchMeterRegistry>().shouldBeEmpty()
            }
    }

    @Test
    fun `native registry keeps its opt in independent from manual helper`() {
        contextRunner
            .withPropertyValues("bluetape4k.aws.cloudwatch.micrometer.enabled=false")
            .run { context ->
                context.getBeansOfType<CloudWatchMeterRegistry>() shouldHaveSize 1
            }
    }

    @Test
    fun `native registry wins over Boot simple metrics fallback when opted in`() {
        ApplicationContextRunner()
            .withConfiguration(
                AutoConfigurations.of(
                    AwsAutoConfiguration::class.java,
                    CloudWatchAutoConfiguration::class.java,
                    CloudWatchMeterRegistryAutoConfiguration::class.java,
                    MetricsAutoConfiguration::class.java,
                    CompositeMeterRegistryAutoConfiguration::class.java,
                    SimpleMetricsExportAutoConfiguration::class.java,
                ),
            )
            .withBean(CloudWatchAsyncClient::class.java, { customCloudWatchClient })
            .withPropertyValues(
                "bluetape4k.aws.cloudwatch.region=us-east-1",
                "bluetape4k.aws.cloudwatch.namespace=Test/App",
                "bluetape4k.aws.cloudwatch.micrometer.registry.enabled=true",
            )
            .run { context ->
                context.getBeansOfType<CloudWatchMeterRegistry>() shouldHaveSize 1
                context.getBeansOfType<SimpleMeterRegistry>().shouldBeEmpty()
            }
    }

    @Test
    fun `native registry fails before creation when namespace is missing`() {
        contextRunner
            .withPropertyValues("bluetape4k.aws.cloudwatch.namespace=")
            .run { context ->
                context.startupFailure.shouldNotBeNull()
                context.startupFailure?.message shouldContain "namespace"
            }
    }

    @Test
    fun `auto configuration declares Boot 4 ordering`() {
        val annotation = CloudWatchMeterRegistryAutoConfiguration::class.java
            .getAnnotation(AutoConfiguration::class.java)

        log.debug { "annotation=$annotation" }

        annotation.afterName.toList() shouldContain
                "org.springframework.boot.micrometer.metrics.autoconfigure.MetricsAutoConfiguration"

        annotation.beforeName.toList() shouldContain
                "org.springframework.boot.micrometer.metrics.autoconfigure.CompositeMeterRegistryAutoConfiguration"

    }
}
