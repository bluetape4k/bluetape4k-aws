package io.bluetape4k.aws.spring.connection

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeInstanceOf
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldBeSameInstanceAs
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.aws.spring.AwsAutoConfiguration
import io.bluetape4k.aws.spring.AwsProperties
import io.bluetape4k.aws.spring.dynamodb.DynamoDbAutoConfiguration
import io.bluetape4k.aws.spring.kinesis.KinesisAutoConfiguration
import io.bluetape4k.aws.spring.s3.S3AutoConfiguration
import io.bluetape4k.aws.spring.sns.SnsAutoConfiguration
import io.bluetape4k.aws.spring.sqs.SqsAutoConfiguration
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.FilteredClassLoader
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient
import software.amazon.awssdk.services.kinesis.KinesisAsyncClient
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.sns.SnsAsyncClient
import software.amazon.awssdk.services.sqs.SqsAsyncClient
import java.net.URI

class AwsServiceConnectionAutoConfigurationTest {

    @Test
    fun `details override service and shared properties for S3`() {
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AwsAutoConfiguration::class.java, S3AutoConfiguration::class.java))
            .withBean(S3ConnectionDetails::class.java, {
                TestS3Details(URI.create("http://details:4566"), "us-east-1", "details-access", "details-secret")
            })
            .withPropertyValues(
                "bluetape4k.aws.region=ap-northeast-2",
                "bluetape4k.aws.endpoint-override=http://shared:4566",
                "bluetape4k.aws.s3.region=eu-west-1",
                "bluetape4k.aws.s3.endpoint-override=http://service:4566",
            )
            .run { context ->
                context.startupFailure.shouldBeNull()
                val client = context.getBean(S3Client::class.java)
                client.serviceClientConfiguration().endpointOverride().orElse(null) shouldBeEqualTo
                    URI.create("http://details:4566")
                client.serviceClientConfiguration().region().id() shouldBeEqualTo "us-east-1"
                context.getBean(AwsCredentialsProvider::class.java)
                    .resolveCredentials().accessKeyId() shouldBeEqualTo "details-access"
            }
    }

    @Test
    fun `properties-only fallback remains available for SQS SNS DynamoDB and Kinesis`() {
        listOf(
            SqsAutoConfiguration::class.java to "sqs",
            SnsAutoConfiguration::class.java to "sns",
            DynamoDbAutoConfiguration::class.java to "dynamodb",
            KinesisAutoConfiguration::class.java to "kinesis",
        ).forEach { (autoConfiguration, serviceName) ->
            ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(AwsAutoConfiguration::class.java, autoConfiguration))
                .withPropertyValues(
                    "bluetape4k.aws.$serviceName.region=us-east-1",
                    "bluetape4k.aws.$serviceName.endpoint-override=http://properties:4566",
                )
                .run { context ->
                    context.startupFailure.shouldBeNull()
                    when (serviceName) {
                        "sqs" -> context.getBean(SqsAsyncClient::class.java)
                            .serviceClientConfiguration().endpointOverride().orElse(null)
                        "sns" -> context.getBean(SnsAsyncClient::class.java)
                            .serviceClientConfiguration().endpointOverride().orElse(null)
                        "dynamodb" -> context.getBean(DynamoDbAsyncClient::class.java)
                            .serviceClientConfiguration().endpointOverride().orElse(null)
                        "kinesis" -> context.getBean(KinesisAsyncClient::class.java)
                            .serviceClientConfiguration().endpointOverride().orElse(null)
                        else -> error("Unexpected service $serviceName")
                    } shouldBeEqualTo URI.create("http://properties:4566")
                }
        }
    }

    @Test
    fun `identical details share one static credential tuple`() {
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AwsAutoConfiguration::class.java))
            .withBean("s3Details", AwsServiceConnectionDetails::class.java, {
                TestAwsDetails("http://details:4566", "us-east-1", "same-access", "same-secret")
            })
            .withBean("sqsDetails", AwsServiceConnectionDetails::class.java, {
                TestAwsDetails("http://details:4567", "us-east-1", "same-access", "same-secret")
            })
            .run { context ->
                context.startupFailure.shouldBeNull()
                val credentials = context.getBean(AwsCredentialsProvider::class.java).resolveCredentials()
                credentials.accessKeyId() shouldBeEqualTo "same-access"
                credentials.secretAccessKey() shouldBeEqualTo "same-secret"
            }
    }

    @Test
    fun `conflicting detail credentials fail with stable secret-free exception`() {
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AwsAutoConfiguration::class.java))
            .withBean("first", AwsServiceConnectionDetails::class.java, {
                TestAwsDetails("http://details:4566", "us-east-1", "access-one", "secret-one")
            })
            .withBean("second", AwsServiceConnectionDetails::class.java, {
                TestAwsDetails("http://details:4567", "us-east-1", "access-two", "secret-two")
            })
            .run { context ->
                val failure = context.startupFailure.shouldNotBeNull()
                val messages = generateSequence(failure) { it.cause }.mapNotNull { it.message }.joinToString("\n")
                messages shouldContain "CREDENTIAL_CONFLICT"
                messages shouldContain "candidates=2"
                messages.contains("secret-one").shouldBeFalse()
                messages.contains("secret-two").shouldBeFalse()
            }
    }

    @Test
    fun `duplicate service details fail before a client is built`() {
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AwsAutoConfiguration::class.java, S3AutoConfiguration::class.java))
            .withBean("first", S3ConnectionDetails::class.java, {
                TestS3Details(URI.create("http://first:4566"), "us-east-1", "same", "same")
            })
            .withBean("second", S3ConnectionDetails::class.java, {
                TestS3Details(URI.create("http://second:4566"), "us-east-1", "same", "same")
            })
            .run { context ->
                val failure = context.startupFailure.shouldNotBeNull()
                generateSequence(failure) { it.cause }
                    .mapNotNull { it.message }
                    .joinToString("\n") shouldContain "DUPLICATE_DETAILS"
            }
    }

    @Test
    fun `custom provider and client retain precedence over connection details`() {
        val customProvider = mockk<AwsCredentialsProvider>(relaxed = true)
        val customClient = mockk<S3Client>(relaxed = true)
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AwsAutoConfiguration::class.java, S3AutoConfiguration::class.java))
            .withBean(S3ConnectionDetails::class.java, {
                TestS3Details(URI.create("http://details:4566"), "us-east-1", "details", "details")
            })
            .withBean(AwsCredentialsProvider::class.java, { customProvider })
            .withBean(S3Client::class.java, { customClient })
            .run { context ->
                context.startupFailure.shouldBeNull()
                context.getBean(AwsCredentialsProvider::class.java).shouldBeSameInstanceAs(customProvider)
                context.getBean(S3Client::class.java).shouldBeSameInstanceAs(customClient)
            }
    }

    @Test
    fun `properties-only auto configuration survives every optional classpath combination`() {
        listOf(
            emptyList(),
            listOf("org.springframework.boot.testcontainers"),
            listOf("io.github.bluetape4k.testcontainers"),
            listOf("org.springframework.boot.testcontainers", "io.github.bluetape4k.testcontainers"),
        ).forEach { filteredPackages ->
            val runner = ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(AwsAutoConfiguration::class.java))
                .withPropertyValues("bluetape4k.aws.region=us-east-1")
            val configuredRunner = if (filteredPackages.isEmpty()) {
                runner
            } else {
                runner.withClassLoader(FilteredClassLoader(*filteredPackages.toTypedArray()))
            }
            configuredRunner.run { context ->
                context.startupFailure.shouldBeNull()
                context.getBean(AwsCredentialsProvider::class.java)
                    .shouldBeInstanceOf(DefaultCredentialsProvider::class)
                context.getBean(AwsProperties::class.java).region shouldBeEqualTo "us-east-1"
            }
        }
    }

    private class TestAwsDetails(
        endpoint: String,
        override val region: String,
        override val accessKey: String,
        override val secretKey: String,
    ): AwsServiceConnectionDetails {
        override val endpoint: URI = URI.create(endpoint)
    }

    private class TestS3Details(
        override val endpoint: URI,
        override val region: String,
        override val accessKey: String,
        override val secretKey: String,
    ): S3ConnectionDetails
}
