package io.bluetape4k.aws.spring.s3

import io.bluetape4k.aws.spring.AwsAutoConfiguration
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeSameInstanceAs
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.assertions.shouldNotBeNull
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.services.s3.S3AsyncClient
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.presigner.S3Presigner

class S3AutoConfigurationTest {

    private val contextRunner = ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                AwsAutoConfiguration::class.java,
                S3AutoConfiguration::class.java,
            )
        )
        .withPropertyValues("bluetape4k.aws.s3.region=us-east-1")

    @Test
    fun `register S3 clients and operations`() {
        contextRunner.run { context ->
            context.getBeansOfType(S3Client::class.java).size shouldBeEqualTo 1
            context.getBeansOfType(S3AsyncClient::class.java).size shouldBeEqualTo 1
            context.getBeansOfType(S3Presigner::class.java).size shouldBeEqualTo 1
            context.getBeansOfType(S3Properties::class.java).size shouldBeEqualTo 1
            context.getBeansOfType(S3Operations::class.java).size shouldBeEqualTo 1
            context.getBeansOfType(S3CoroutinesTemplate::class.java).size shouldBeEqualTo 1
        }
    }

    @Test
    fun `back off when S3 auto configuration disabled`() {
        contextRunner
            .withPropertyValues("bluetape4k.aws.s3.enabled=false")
            .run { context ->
                context.getBeansOfType(S3Client::class.java).size shouldBeEqualTo 0
                context.getBeansOfType(S3AsyncClient::class.java).size shouldBeEqualTo 0
                context.getBeansOfType(S3Presigner::class.java).size shouldBeEqualTo 0
                context.getBeansOfType(S3Operations::class.java).size shouldBeEqualTo 0
            }
    }

    @Test
    fun `custom S3Operations bean backs off template`() {
        contextRunner
            .withBean(S3Operations::class.java, { NoopS3Operations })
            .run { context ->
                context.getBeansOfType(S3Operations::class.java).size shouldBeEqualTo 1
                context.getBeansOfType(S3CoroutinesTemplate::class.java).size shouldBeEqualTo 0
                context.getBean(S3Operations::class.java) shouldBeSameInstanceAs NoopS3Operations
            }
    }

    @Test
    fun `endpoint override requires region`() {
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(S3AutoConfiguration::class.java))
            .withBean(AwsCredentialsProvider::class.java, {
                StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test"))
            })
            .withPropertyValues("bluetape4k.aws.s3.endpoint-override=http://localhost:4566")
            .run { context ->
                context.startupFailure.shouldNotBeNull()
                val messages = generateSequence(context.startupFailure) { it.cause }
                    .mapNotNull { it.message }
                    .joinToString("\n")
                messages shouldContain "region is required"
            }
    }
}
