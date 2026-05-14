package io.bluetape4k.aws.spring.secretsmanager

import io.bluetape4k.assertions.shouldBeEqualTo
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

class SecretsValueTest {

    @Test
    fun `SecretsValue resolves Spring property placeholders`() {
        ApplicationContextRunner()
            .withUserConfiguration(SecretsValueConfiguration::class.java)
            .withPropertyValues("app.secret=from-secrets-manager")
            .run { context ->
                context.getBean(SecretHolder::class.java).value shouldBeEqualTo "from-secrets-manager"
            }
    }

    data class SecretHolder(val value: String)

    @Configuration(proxyBeanMethods = false)
    private class SecretsValueConfiguration {

        @Bean
        fun secretHolder(
            @SecretsValue("\${app.secret}") value: String,
        ): SecretHolder =
            SecretHolder(value)
    }
}
