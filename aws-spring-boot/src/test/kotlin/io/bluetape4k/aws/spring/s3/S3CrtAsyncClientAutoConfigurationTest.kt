package io.bluetape4k.aws.spring.s3

import io.bluetape4k.aws.spring.AwsAutoConfiguration
import io.bluetape4k.aws.spring.AwsClientCustomizer
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldContain
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.boot.test.context.FilteredClassLoader
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.services.s3.S3AsyncClient
import software.amazon.awssdk.services.s3.S3CrtAsyncClientBuilder
import kotlin.test.assertFailsWith

class S3CrtAsyncClientAutoConfigurationTest {

    private val contextRunner = ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                AwsAutoConfiguration::class.java,
                S3CrtAsyncClientAutoConfiguration::class.java,
                S3AutoConfiguration::class.java,
            )
        )
        .withBean(AwsCredentialsProvider::class.java, {
            StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test"))
        })
        .withPropertyValues(
            "bluetape4k.aws.s3.region=us-east-1",
            "bluetape4k.aws.s3.endpoint-override=http://localhost:4566",
        )

    @Test
    fun `CRT is opt in and replaces the default async client`() {
        contextRunner
            .withPropertyValues(
                "bluetape4k.aws.s3.crt.enabled=true",
                "bluetape4k.aws.s3.crt.target-throughput-in-gbps=5.0",
                "bluetape4k.aws.s3.crt.max-concurrency=8",
                "bluetape4k.aws.s3.crt.minimum-part-size-in-bytes=5242880",
            )
            .run { context ->
                context.startupFailure.shouldBeNull()
                context.getBeansOfType(S3AsyncClient::class.java).size shouldBeEqualTo 1
                context.getBean(S3AsyncClient::class.java).javaClass.name shouldContain "Crt"
            }
    }

    @Test
    fun `default SDK client remains when CRT is disabled`() {
        contextRunner.run { context ->
            context.startupFailure.shouldBeNull()
            context.getBeansOfType(S3AsyncClient::class.java).size shouldBeEqualTo 1
            context.getBean(S3AsyncClient::class.java).javaClass.name shouldContain "DefaultS3AsyncClient"
        }
    }

    @Test
    fun `explicit async client backs CRT off`() {
        val explicit = mockk<S3AsyncClient>(relaxed = true)
        contextRunner
            .withPropertyValues("bluetape4k.aws.s3.crt.enabled=true")
            .withBean(S3AsyncClient::class.java, { explicit })
            .run { context ->
                context.startupFailure.shouldBeNull()
                context.getBean(S3AsyncClient::class.java) shouldBeEqualTo explicit
            }
    }

    @Test
    fun `CRT customizer is applied after common properties`() {
        CrtCustomizerConfig.invocations = 0
        contextRunner
            .withPropertyValues("bluetape4k.aws.s3.crt.enabled=true")
            .withUserConfiguration(CrtCustomizerConfig::class.java)
            .run { context ->
                context.startupFailure.shouldBeNull()
                CrtCustomizerConfig.invocations shouldBeEqualTo 1
            }
    }

    @Test
    fun `CRT classpath absence keeps the normal S3 path`() {
        contextRunner
            .withPropertyValues("bluetape4k.aws.s3.crt.enabled=true")
            .withClassLoader(FilteredClassLoader("software.amazon.awssdk.http.crt"))
            .run { context ->
                context.startupFailure.shouldBeNull()
                context.getBean(S3AsyncClient::class.java).javaClass.name shouldContain "DefaultS3AsyncClient"
            }
    }

    @Test
    fun `CRT property validation rejects non positive tuning`() {
        assertFailsWith<IllegalArgumentException> {
            S3CrtClientProperties(maxConcurrency = 0)
        }
        assertFailsWith<IllegalArgumentException> {
            S3CrtClientProperties(targetThroughputInGbps = -1.0)
        }
    }

    @Configuration(proxyBeanMethods = false)
    internal class CrtCustomizerConfig {
        @Bean
        fun crtCustomizer(): AwsClientCustomizer<S3CrtAsyncClientBuilder> =
            AwsClientCustomizer { builder ->
                invocations += 1
                builder.maxConcurrency(2)
            }

        companion object {
            var invocations: Int = 0
        }
    }
}
