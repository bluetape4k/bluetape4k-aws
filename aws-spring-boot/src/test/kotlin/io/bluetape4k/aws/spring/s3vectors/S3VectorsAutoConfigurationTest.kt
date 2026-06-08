package io.bluetape4k.aws.spring.s3vectors

import io.bluetape4k.aws.s3vectors.S3VectorsCoroutinesTemplate
import io.bluetape4k.aws.s3vectors.S3VectorsOperations
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
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.awscore.client.builder.AwsAsyncClientBuilder
import software.amazon.awssdk.services.s3vectors.S3VectorsAsyncClient
import software.amazon.awssdk.services.s3vectors.S3VectorsAsyncClientBuilder

class S3VectorsAutoConfigurationTest {

    private val customS3VectorsAsyncClient = mockk<S3VectorsAsyncClient>(relaxed = true)

    private val contextRunner = ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                AwsAutoConfiguration::class.java,
                S3VectorsAutoConfiguration::class.java,
            )
        )
        .withBean(AwsCredentialsProvider::class.java, {
            StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test"))
        })
        .withPropertyValues(
            "bluetape4k.aws.s3-vectors.enabled=true",
            "bluetape4k.aws.s3-vectors.region=us-east-1",
        )

    @Test
    fun `register S3 Vectors client and operations when enabled`() {
        contextRunner.run { context ->
            context.startupFailure.shouldBeNull()
            context.getBeansOfType(S3VectorsAsyncClient::class.java).size shouldBeEqualTo 1
            context.getBeansOfType(S3VectorsProperties::class.java).size shouldBeEqualTo 1
            context.getBeansOfType(S3VectorsOperations::class.java).size shouldBeEqualTo 1
            context.getBeansOfType(S3VectorsCoroutinesTemplate::class.java).size shouldBeEqualTo 1
        }
    }

    @Test
    fun `back off by default`() {
        ApplicationContextRunner()
            .withConfiguration(
                AutoConfigurations.of(
                    AwsAutoConfiguration::class.java,
                    S3VectorsAutoConfiguration::class.java,
                )
            )
            .withPropertyValues("bluetape4k.aws.s3-vectors.region=us-east-1")
            .run { context ->
                context.startupFailure.shouldBeNull()
                context.getBeansOfType(S3VectorsAsyncClient::class.java).size shouldBeEqualTo 0
                context.getBeansOfType(S3VectorsOperations::class.java).size shouldBeEqualTo 0
            }
    }

    @Test
    fun `custom S3 Vectors client backs off auto configured client`() {
        contextRunner
            .withBean(S3VectorsAsyncClient::class.java, { customS3VectorsAsyncClient })
            .run { context ->
                context.getBeansOfType(S3VectorsAsyncClient::class.java).size shouldBeEqualTo 1
                context.getBean(S3VectorsAsyncClient::class.java) shouldBeSameInstanceAs customS3VectorsAsyncClient
                context.getBeansOfType(S3VectorsOperations::class.java).size shouldBeEqualTo 1
            }
    }

    @Test
    fun `custom S3 Vectors operations backs off template`() {
        contextRunner
            .withBean(S3VectorsOperations::class.java, { NoopS3VectorsOperations })
            .run { context ->
                context.getBeansOfType(S3VectorsOperations::class.java).size shouldBeEqualTo 1
                context.getBeansOfType(S3VectorsCoroutinesTemplate::class.java).size shouldBeEqualTo 0
                context.getBean(S3VectorsOperations::class.java) shouldBeSameInstanceAs NoopS3VectorsOperations
            }
    }

    @Test
    fun `endpoint override requires region`() {
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(S3VectorsAutoConfiguration::class.java))
            .withPropertyValues(
                "bluetape4k.aws.s3-vectors.enabled=true",
                "bluetape4k.aws.s3-vectors.endpoint-override=http://localhost:4566",
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
                    S3VectorsAutoConfiguration::class.java,
                )
            )
            .withBean(AwsCredentialsProvider::class.java, {
                StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test"))
            })
            .withPropertyValues(
                "bluetape4k.aws.region=us-west-2",
                "bluetape4k.aws.s3-vectors.enabled=true",
                "bluetape4k.aws.s3-vectors.endpoint-override=http://localhost:4566",
            )
            .run { context ->
                context.startupFailure.shouldBeNull()
                context.getBeansOfType(S3VectorsAsyncClient::class.java).size shouldBeEqualTo 1
            }
    }

    @Test
    fun `global and service customizers are applied in order`() {
        S3VectorsCustomizerConfig.calls.clear()

        contextRunner
            .withUserConfiguration(S3VectorsCustomizerConfig::class.java)
            .run { context ->
                context.getBean(S3VectorsAsyncClient::class.java).shouldNotBeNull()
                S3VectorsCustomizerConfig.calls shouldBeEqualTo listOf(
                    "global-async:s3vectors",
                    "s3vectors-async",
                )
            }
    }

    @Test
    fun `auto configuration backs off when S3 Vectors SDK is absent`() {
        contextRunner
            .withClassLoader(FilteredClassLoader("software.amazon.awssdk.services.s3vectors"))
            .run { context ->
                context.getBeansOfType(S3VectorsAsyncClient::class.java).size shouldBeEqualTo 0
                context.getBeansOfType(S3VectorsOperations::class.java).size shouldBeEqualTo 0
            }
    }

    @Configuration(proxyBeanMethods = false)
    internal class S3VectorsCustomizerConfig {
        @Bean
        fun globalAsyncCustomizer(): AwsAsyncClientCustomizer =
            RecordingAsyncCustomizer("global-async")

        @Bean
        fun s3VectorsAsyncClientCustomizer(): AwsClientCustomizer<S3VectorsAsyncClientBuilder> =
            AwsClientCustomizer { calls += "s3vectors-async" }

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
