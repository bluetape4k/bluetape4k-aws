package io.bluetape4k.aws.spring

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeInstanceOf
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.assertions.shouldNotBeNull
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.FilteredClassLoader
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider
import software.amazon.awssdk.auth.credentials.WebIdentityTokenFileCredentialsProvider

class AwsAutoConfigurationTest {

    private val contextRunner = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(AwsAutoConfiguration::class.java))

    @Test
    fun `DefaultCredentialsProvider bean registered`() {
        contextRunner.run { context ->
            context.getBean(AwsCredentialsProvider::class.java)
                .shouldBeInstanceOf(DefaultCredentialsProvider::class)
        }
    }

    @Test
    fun `shared AWS defaults bind`() {
        contextRunner
            .withPropertyValues(
                "bluetape4k.aws.enabled=true",
                "bluetape4k.aws.region=ap-northeast-2",
                "bluetape4k.aws.endpoint-override=http://localhost:4566",
            )
            .run { context ->
                val properties = context.getBean(AwsProperties::class.java)
                properties.enabled shouldBeEqualTo true
                properties.region shouldBeEqualTo "ap-northeast-2"
                properties.endpointOverride.toString() shouldBeEqualTo "http://localhost:4566"
            }
    }

    @Test
    fun `core AWS auto configuration backs off when disabled`() {
        contextRunner
            .withPropertyValues("bluetape4k.aws.enabled=false")
            .run { context ->
                context.getBeansOfType(AwsProperties::class.java).size shouldBeEqualTo 0
                context.getBeansOfType(AwsCredentialsProvider::class.java).size shouldBeEqualTo 0
            }
    }

    @Test
    fun `shared endpoint override requires shared region`() {
        contextRunner
            .withPropertyValues("bluetape4k.aws.endpoint-override=http://localhost:4566")
            .run { context ->
                context.startupFailure.shouldNotBeNull()
                val messages = generateSequence(context.startupFailure) { it.cause }
                    .mapNotNull { it.message }
                    .joinToString("\n")
                messages shouldContain "bluetape4k.aws.region is required"
            }
    }

    @Test
    fun `web identity provider is opt in when STS is present`() {
        contextRunner
            .withPropertyValues(
                "bluetape4k.aws.credentials.web-identity.enabled=true",
                "bluetape4k.aws.credentials.web-identity.role-arn=arn:aws:iam::123456789012:role/app",
                "bluetape4k.aws.credentials.web-identity.role-session-name=app",
                "bluetape4k.aws.credentials.web-identity.token-file=/tmp/aws-web-identity-token",
            )
            .run { context ->
                context.getBean(AwsCredentialsProvider::class.java)
                    .shouldBeInstanceOf(WebIdentityTokenFileCredentialsProvider::class)
            }
    }

    @Test
    fun `web identity provider backs off when STS is absent`() {
        contextRunner
            .withClassLoader(FilteredClassLoader("software.amazon.awssdk.services.sts"))
            .withPropertyValues(
                "bluetape4k.aws.credentials.web-identity.enabled=true",
                "bluetape4k.aws.credentials.web-identity.role-arn=arn:aws:iam::123456789012:role/app",
            )
            .run { context ->
                context.getBean(AwsCredentialsProvider::class.java)
                    .shouldBeInstanceOf(DefaultCredentialsProvider::class)
            }
    }
}
