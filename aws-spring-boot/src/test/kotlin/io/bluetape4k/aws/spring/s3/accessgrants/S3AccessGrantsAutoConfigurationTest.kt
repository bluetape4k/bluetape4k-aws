package io.bluetape4k.aws.spring.s3.accessgrants

import io.bluetape4k.aws.spring.AwsAsyncClientCustomizer
import io.bluetape4k.aws.spring.AwsAutoConfiguration
import io.bluetape4k.aws.spring.AwsClientCustomizationContext
import io.bluetape4k.aws.spring.AwsClientCustomizer
import io.bluetape4k.aws.spring.AwsSyncClientCustomizer
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
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.awscore.client.builder.AwsAsyncClientBuilder
import software.amazon.awssdk.awscore.client.builder.AwsSyncClientBuilder
import software.amazon.awssdk.services.s3control.S3ControlAsyncClient
import software.amazon.awssdk.services.s3control.S3ControlAsyncClientBuilder
import software.amazon.awssdk.services.s3control.S3ControlClient
import software.amazon.awssdk.services.s3control.S3ControlClientBuilder

class S3AccessGrantsAutoConfigurationTest {

    private val customS3ControlClient = mockk<S3ControlClient>(relaxed = true)
    private val customS3ControlAsyncClient = mockk<S3ControlAsyncClient>(relaxed = true)

    private val contextRunner = ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                AwsAutoConfiguration::class.java,
                S3AccessGrantsAutoConfiguration::class.java,
            )
        )
        .withBean(AwsCredentialsProvider::class.java, {
            StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test"))
        })
        .withPropertyValues(
            "bluetape4k.aws.s3.access-grants.enabled=true",
            "bluetape4k.aws.s3.access-grants.region=us-east-1",
        )

    @Test
    fun `register S3 Control clients and Access Grants operations when enabled`() {
        contextRunner.run { context ->
            context.startupFailure.shouldBeNull()
            context.getBeansOfType(S3ControlClient::class.java).size shouldBeEqualTo 1
            context.getBeansOfType(S3ControlAsyncClient::class.java).size shouldBeEqualTo 1
            context.getBeansOfType(S3AccessGrantsProperties::class.java).size shouldBeEqualTo 1
            context.getBeansOfType(S3AccessGrantsOperations::class.java).size shouldBeEqualTo 1
            context.getBeansOfType(S3AccessGrantsCoroutinesTemplate::class.java).size shouldBeEqualTo 1
        }
    }

    @Test
    fun `back off by default`() {
        ApplicationContextRunner()
            .withConfiguration(
                AutoConfigurations.of(
                    AwsAutoConfiguration::class.java,
                    S3AccessGrantsAutoConfiguration::class.java,
                )
            )
            .withPropertyValues("bluetape4k.aws.s3.access-grants.region=us-east-1")
            .run { context ->
                context.startupFailure.shouldBeNull()
                context.getBeansOfType(S3ControlClient::class.java).size shouldBeEqualTo 0
                context.getBeansOfType(S3ControlAsyncClient::class.java).size shouldBeEqualTo 0
                context.getBeansOfType(S3AccessGrantsOperations::class.java).size shouldBeEqualTo 0
            }
    }

    @Test
    fun `back off when parent S3 integration is disabled`() {
        contextRunner
            .withPropertyValues("bluetape4k.aws.s3.enabled=false")
            .run { context ->
                context.startupFailure.shouldBeNull()
                context.getBeansOfType(S3ControlClient::class.java).size shouldBeEqualTo 0
                context.getBeansOfType(S3ControlAsyncClient::class.java).size shouldBeEqualTo 0
                context.getBeansOfType(S3AccessGrantsOperations::class.java).size shouldBeEqualTo 0
            }
    }

    @Test
    fun `custom S3 Control clients back off auto configured clients`() {
        contextRunner
            .withBean(S3ControlClient::class.java, { customS3ControlClient })
            .withBean(S3ControlAsyncClient::class.java, { customS3ControlAsyncClient })
            .run { context ->
                context.getBeansOfType(S3ControlClient::class.java).size shouldBeEqualTo 1
                context.getBeansOfType(S3ControlAsyncClient::class.java).size shouldBeEqualTo 1
                context.getBean(S3ControlClient::class.java) shouldBeSameInstanceAs customS3ControlClient
                context.getBean(S3ControlAsyncClient::class.java) shouldBeSameInstanceAs customS3ControlAsyncClient
                context.getBeansOfType(S3AccessGrantsOperations::class.java).size shouldBeEqualTo 1
            }
    }

    @Test
    fun `custom Access Grants operations backs off template`() {
        contextRunner
            .withBean(S3AccessGrantsOperations::class.java, { NoopS3AccessGrantsOperations })
            .run { context ->
                context.getBeansOfType(S3AccessGrantsOperations::class.java).size shouldBeEqualTo 1
                context.getBeansOfType(S3AccessGrantsCoroutinesTemplate::class.java).size shouldBeEqualTo 0
                context.getBean(S3AccessGrantsOperations::class.java) shouldBeSameInstanceAs
                    NoopS3AccessGrantsOperations
            }
    }

    @Test
    fun `endpoint override requires region`() {
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(S3AccessGrantsAutoConfiguration::class.java))
            .withPropertyValues(
                "bluetape4k.aws.s3.access-grants.enabled=true",
                "bluetape4k.aws.s3.access-grants.endpoint-override=http://localhost:4566",
            )
            .run { context ->
                context.startupFailure.shouldNotBeNull()
                val messages = generateSequence(context.startupFailure) { it.cause }
                    .mapNotNull { it.message }
                    .joinToString("\n")
                messages shouldContain "region is required"
            }
    }

    @Test
    fun `shared defaults provide region and endpoint override`() {
        ApplicationContextRunner()
            .withConfiguration(
                AutoConfigurations.of(
                    AwsAutoConfiguration::class.java,
                    S3AccessGrantsAutoConfiguration::class.java,
                )
            )
            .withBean(AwsCredentialsProvider::class.java, {
                StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test"))
            })
            .withPropertyValues(
                "bluetape4k.aws.region=us-west-2",
                "bluetape4k.aws.s3.access-grants.enabled=true",
                "bluetape4k.aws.s3.access-grants.endpoint-override=http://localhost:4566",
            )
            .run { context ->
                context.startupFailure.shouldBeNull()
                context.getBeansOfType(S3ControlClient::class.java).size shouldBeEqualTo 1
                context.getBeansOfType(S3ControlAsyncClient::class.java).size shouldBeEqualTo 1
            }
    }

    @Test
    fun `global and service customizers are applied in order`() {
        S3AccessGrantsCustomizerConfig.calls.clear()

        contextRunner
            .withUserConfiguration(S3AccessGrantsCustomizerConfig::class.java)
            .run { context ->
                context.getBean(S3ControlClient::class.java).shouldNotBeNull()
                context.getBean(S3ControlAsyncClient::class.java).shouldNotBeNull()
                S3AccessGrantsCustomizerConfig.calls shouldBeEqualTo listOf(
                    "global-sync:s3control",
                    "s3control-sync",
                    "global-async:s3control",
                    "s3control-async",
                )
            }
    }

    @Test
    fun `auto configuration backs off when S3 Control SDK is absent`() {
        contextRunner
            .withClassLoader(FilteredClassLoader("software.amazon.awssdk.services.s3control"))
            .run { context ->
                context.getBeansOfType(S3ControlClient::class.java).size shouldBeEqualTo 0
                context.getBeansOfType(S3ControlAsyncClient::class.java).size shouldBeEqualTo 0
                context.getBeansOfType(S3AccessGrantsOperations::class.java).size shouldBeEqualTo 0
            }
    }

    @Configuration(proxyBeanMethods = false)
    internal class S3AccessGrantsCustomizerConfig {
        @Bean
        fun globalSyncCustomizer(): AwsSyncClientCustomizer =
            RecordingSyncCustomizer("global-sync")

        @Bean
        fun globalAsyncCustomizer(): AwsAsyncClientCustomizer =
            RecordingAsyncCustomizer("global-async")

        @Bean
        fun s3ControlClientCustomizer(): AwsClientCustomizer<S3ControlClientBuilder> =
            AwsClientCustomizer { calls += "s3control-sync" }

        @Bean
        fun s3ControlAsyncClientCustomizer(): AwsClientCustomizer<S3ControlAsyncClientBuilder> =
            AwsClientCustomizer { calls += "s3control-async" }

        private class RecordingSyncCustomizer(
            private val name: String,
        ): AwsSyncClientCustomizer, Ordered {
            override fun customize(
                context: AwsClientCustomizationContext,
                builder: AwsSyncClientBuilder<*, *>,
            ) {
                calls += "$name:${context.serviceName}"
            }

            override fun getOrder(): Int = 0
        }

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
