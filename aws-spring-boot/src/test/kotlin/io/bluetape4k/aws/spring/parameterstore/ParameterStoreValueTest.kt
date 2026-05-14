package io.bluetape4k.aws.spring.parameterstore

import io.bluetape4k.assertions.shouldBeEqualTo
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

class ParameterStoreValueTest {

    @Test
    fun `ParameterStoreValue resolves Spring property placeholders`() {
        ApplicationContextRunner()
            .withUserConfiguration(ParameterStoreValueConfiguration::class.java)
            .withPropertyValues("app.parameter=from-parameter-store")
            .run { context ->
                context.getBean(ParameterHolder::class.java).value shouldBeEqualTo "from-parameter-store"
            }
    }

    data class ParameterHolder(val value: String)

    @Configuration(proxyBeanMethods = false)
    private class ParameterStoreValueConfiguration {

        @Bean
        fun parameterHolder(
            @ParameterStoreValue("\${app.parameter}") value: String,
        ): ParameterHolder =
            ParameterHolder(value)
    }
}
