package io.bluetape4k.aws.spring.sns

import io.bluetape4k.aws.spring.AwsAutoConfiguration
import io.bluetape4k.assertions.shouldBeSameInstanceAs
import io.bluetape4k.assertions.shouldHaveSize
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.FilteredClassLoader
import org.springframework.boot.test.context.runner.ApplicationContextRunner

class SnsHttpMessageVerificationAutoConfigurationTest {

    private val contextRunner = ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                AwsAutoConfiguration::class.java,
                SnsAutoConfiguration::class.java,
                SnsHttpMessageVerificationAutoConfiguration::class.java,
            ),
        )
        .withPropertyValues(
            "bluetape4k.aws.sns.region=us-east-1",
            "bluetape4k.aws.sns.verification.enabled=true",
        )

    @Test
    fun `registers verifier when conditions are enabled`() {
        contextRunner.run { context ->
            context.getBeansOfType(SnsHttpMessageVerifier::class.java) shouldHaveSize 1
        }
    }

    @Test
    fun `backs off when verification is disabled`() {
        contextRunner.withPropertyValues(
            "bluetape4k.aws.sns.verification.enabled=false",
        ).run { context ->
            context.getBeansOfType(SnsHttpMessageVerifier::class.java) shouldHaveSize 0
        }
    }

    @Test
    fun `backs off when SNS is disabled`() {
        contextRunner.withPropertyValues(
            "bluetape4k.aws.sns.enabled=false",
        ).run { context ->
            context.getBeansOfType(SnsHttpMessageVerifier::class.java) shouldHaveSize 0
        }
    }

    @Test
    fun `backs off when global AWS is disabled`() {
        contextRunner.withPropertyValues(
            "bluetape4k.aws.enabled=false",
        ).run { context ->
            context.getBeansOfType(SnsHttpMessageVerifier::class.java) shouldHaveSize 0
        }
    }

    @Test
    fun `backs off when a verifier bean already exists`() {
        val customVerifier = mockk<SnsHttpMessageVerifier>(relaxed = true)

        contextRunner.withBean(SnsHttpMessageVerifier::class.java, { customVerifier }).run { context ->
            context.getBeansOfType(SnsHttpMessageVerifier::class.java) shouldHaveSize 1
            context.getBean(SnsHttpMessageVerifier::class.java) shouldBeSameInstanceAs customVerifier
        }
    }

    @Test
    fun `backs off when manager class is absent`() {
        contextRunner.withClassLoader(
            FilteredClassLoader("software.amazon.awssdk.messagemanager.sns"),
        ).run { context ->
            context.getBeansOfType(SnsHttpMessageVerifier::class.java) shouldHaveSize 0
        }
    }
}
