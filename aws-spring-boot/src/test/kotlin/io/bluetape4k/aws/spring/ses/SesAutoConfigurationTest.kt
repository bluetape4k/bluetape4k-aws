package io.bluetape4k.aws.spring.ses

import io.bluetape4k.aws.spring.AwsAutoConfiguration
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
import org.springframework.mail.javamail.JavaMailSender
import software.amazon.awssdk.services.sesv2.SesV2AsyncClient

class SesAutoConfigurationTest {

    private val contextRunner = ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                AwsAutoConfiguration::class.java,
                SesAutoConfiguration::class.java,
                SesJavaMailSenderAutoConfiguration::class.java,
            )
        )
        .withPropertyValues("bluetape4k.aws.ses.region=us-east-1")

    @Test
    fun `register SES client operations and JavaMailSender`() {
        contextRunner.run { context ->
            context.getBeansOfType(SesV2AsyncClient::class.java).size shouldBeEqualTo 1
            context.getBeansOfType(SesProperties::class.java).size shouldBeEqualTo 1
            context.getBeansOfType(SesOperations::class.java).size shouldBeEqualTo 1
            context.getBeansOfType(SesCoroutinesMailSender::class.java).size shouldBeEqualTo 1
            context.getBeansOfType(JavaMailSender::class.java).size shouldBeEqualTo 1
        }
    }

    @Test
    fun `back off when SES auto configuration disabled`() {
        contextRunner
            .withPropertyValues("bluetape4k.aws.ses.enabled=false")
            .run { context ->
                context.getBeansOfType(SesV2AsyncClient::class.java).size shouldBeEqualTo 0
                context.getBeansOfType(SesOperations::class.java).size shouldBeEqualTo 0
                context.getBeansOfType(JavaMailSender::class.java).size shouldBeEqualTo 0
            }
    }

    @Test
    fun `custom client bean backs off auto configured client`() {
        val customClient = mockk<SesV2AsyncClient>(relaxed = true)

        contextRunner
            .withBean(SesV2AsyncClient::class.java, { customClient })
            .run { context ->
                context.getBeansOfType(SesV2AsyncClient::class.java).size shouldBeEqualTo 1
                context.getBean(SesV2AsyncClient::class.java) shouldBeSameInstanceAs customClient
                context.getBeansOfType(SesOperations::class.java).size shouldBeEqualTo 1
            }
    }

    @Test
    fun `custom operations bean backs off sender`() {
        contextRunner
            .withBean(SesOperations::class.java, { NoopSesOperations })
            .run { context ->
                context.getBeansOfType(SesOperations::class.java).size shouldBeEqualTo 1
                context.getBeansOfType(SesCoroutinesMailSender::class.java).size shouldBeEqualTo 0
                context.getBean(SesOperations::class.java) shouldBeSameInstanceAs NoopSesOperations
            }
    }

    @Test
    fun `JavaMailSender adapter can be disabled`() {
        contextRunner
            .withPropertyValues("bluetape4k.aws.ses.java-mail-sender.enabled=false")
            .run { context ->
                context.getBeansOfType(SesOperations::class.java).size shouldBeEqualTo 1
                context.getBeansOfType(JavaMailSender::class.java).size shouldBeEqualTo 0
            }
    }

    @Test
    fun `endpoint override requires region`() {
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(SesAutoConfiguration::class.java))
            .withPropertyValues("bluetape4k.aws.ses.endpoint-override=http://localhost:4566")
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
            .withPropertyValues("bluetape4k.aws.ses.endpoint-override=http://localhost:4566")
            .run { context ->
                context.startupFailure.shouldBeNull()
                context.getBean(SesProperties::class.java).endpointOverride.toString() shouldBeEqualTo
                    "http://localhost:4566"
            }
    }

    @Test
    fun `SES auto configuration backs off when SES SDK is absent`() {
        contextRunner
            .withClassLoader(FilteredClassLoader("software.amazon.awssdk.services.sesv2"))
            .run { context ->
                context.getBeansOfType(SesV2AsyncClient::class.java).size shouldBeEqualTo 0
                context.getBeansOfType(SesOperations::class.java).size shouldBeEqualTo 0
            }
    }

    @Test
    fun `JavaMailSender adapter backs off when Jakarta Mail is absent`() {
        contextRunner
            .withClassLoader(FilteredClassLoader("jakarta.mail"))
            .run { context ->
                context.getBeansOfType(SesOperations::class.java).size shouldBeEqualTo 1
                context.getBeansOfType(JavaMailSender::class.java).size shouldBeEqualTo 0
            }
    }

    @Test
    fun `JavaMailSender adapter backs off when Angus Mail provider is absent`() {
        contextRunner
            .withClassLoader(FilteredClassLoader("org.eclipse.angus.mail"))
            .run { context ->
                context.getBeansOfType(SesOperations::class.java).size shouldBeEqualTo 1
                context.getBeansOfType(JavaMailSender::class.java).size shouldBeEqualTo 0
            }
    }
}
