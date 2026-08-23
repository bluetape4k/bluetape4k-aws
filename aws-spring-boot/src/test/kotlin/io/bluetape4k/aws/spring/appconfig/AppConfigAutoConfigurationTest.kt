package io.bluetape4k.aws.spring.appconfig

import io.bluetape4k.aws.spring.AwsAutoConfiguration
import io.bluetape4k.aws.spring.AwsClientCustomizationContext
import io.bluetape4k.aws.spring.AwsClientCustomizer
import io.bluetape4k.aws.spring.AwsSyncClientCustomizer
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldBeSameInstanceAs
import io.bluetape4k.assertions.shouldNotBeNull
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.FilteredClassLoader
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.Ordered
import org.springframework.context.SmartLifecycle
import software.amazon.awssdk.awscore.client.builder.AwsSyncClientBuilder
import software.amazon.awssdk.services.appconfigdata.AppConfigDataClient
import software.amazon.awssdk.services.appconfigdata.AppConfigDataClientBuilder

class AppConfigAutoConfigurationTest {

    private val contextRunner = ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                AwsAutoConfiguration::class.java,
                AppConfigAutoConfiguration::class.java,
            ),
        )
        .withPropertyValues("bluetape4k.aws.app-config.region=us-east-1")

    @Test
    fun `registers AppConfig client properties and lifecycle`() {
        contextRunner.run { context ->
            context.getBeansOfType(AppConfigDataClient::class.java).size shouldBeEqualTo 1
            context.getBeansOfType(AppConfigProperties::class.java).size shouldBeEqualTo 1
            context.getBeansOfType(SmartLifecycle::class.java).values
                .count { it is AppConfigReloadLifecycle } shouldBeEqualTo 1
            context.startupFailure.shouldBeNull()
        }
    }

    @Test
    fun `backs off when disabled SDK absent or custom client is supplied`() {
        contextRunner
            .withPropertyValues("bluetape4k.aws.app-config.enabled=false")
            .run { context ->
                context.getBeansOfType(AppConfigDataClient::class.java).size shouldBeEqualTo 0
            }

        contextRunner
            .withClassLoader(FilteredClassLoader("software.amazon.awssdk.services.appconfigdata"))
            .run { context ->
                context.getBeansOfType(AppConfigDataClient::class.java).size shouldBeEqualTo 0
            }

        val custom = software.amazon.awssdk.services.appconfigdata.AppConfigDataClient.builder()
            .region(software.amazon.awssdk.regions.Region.US_EAST_1)
            .build()
        try {
            contextRunner.withBean(AppConfigDataClient::class.java, { custom }).run { context ->
                context.getBeansOfType(AppConfigDataClient::class.java).size shouldBeEqualTo 1
                context.getBean(AppConfigDataClient::class.java) shouldBeSameInstanceAs custom
            }
        } finally {
            custom.close()
        }
    }

    @Test
    fun `endpoint override requires a region`() {
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AppConfigAutoConfiguration::class.java))
            .withPropertyValues("bluetape4k.aws.app-config.endpoint-override=http://localhost:2772")
            .run { context ->
                context.startupFailure.shouldNotBeNull()
            }
    }

    @Test
    fun `global and service customizers run in order`() {
        CustomizerConfig.calls.clear()
        contextRunner
            .withUserConfiguration(CustomizerConfig::class.java)
            .run { context ->
                context.getBean(AppConfigDataClient::class.java).shouldNotBeNull()
                CustomizerConfig.calls shouldBeEqualTo listOf("global:appconfigdata", "appconfigdata")
            }
    }

    @Configuration(proxyBeanMethods = false)
    internal class CustomizerConfig {
        @Bean
        fun global(): AwsSyncClientCustomizer = object : AwsSyncClientCustomizer, Ordered {
            override fun customize(context: AwsClientCustomizationContext, builder: AwsSyncClientBuilder<*, *>) {
                calls += "global:${context.serviceName}"
            }

            override fun getOrder(): Int = 0
        }

        @Bean
        fun service(): AwsClientCustomizer<AppConfigDataClientBuilder> = AwsClientCustomizer {
            calls += "appconfigdata"
        }

        companion object {
            val calls: MutableList<String> = mutableListOf()
        }
    }
}
