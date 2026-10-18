package io.bluetape4k.aws.spring.parameterstore

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.logging.KLogging
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.getBean
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.io.Serializable

class ParameterStoreValueTest {

    companion object: KLogging()

    @Test
    fun `ParameterStoreValue resolves Spring property placeholders`() {
        ApplicationContextRunner()
            .withUserConfiguration(ParameterStoreValueConfiguration::class.java)
            .withPropertyValues("app.parameter=from-parameter-store")
            .run { context ->
                context.getBean<ParameterHolder>().value shouldBeEqualTo "from-parameter-store"
            }
    }

    data class ParameterHolder(val value: String): Serializable {
        companion object {
            private const val serialVersionUID: Long = 1L
        }
    }

    @Configuration(proxyBeanMethods = false)
    private class ParameterStoreValueConfiguration {

        @Bean
        fun parameterHolder(
            @ParameterStoreValue($$"${app.parameter}") value: String,
        ): ParameterHolder =
            ParameterHolder(value)
    }
}
