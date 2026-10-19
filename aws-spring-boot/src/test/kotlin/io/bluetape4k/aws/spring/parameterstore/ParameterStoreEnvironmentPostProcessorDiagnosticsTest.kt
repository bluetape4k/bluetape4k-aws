package io.bluetape4k.aws.spring.parameterstore

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.core.read.ListAppender
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.assertions.shouldNotContain
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.boot.SpringApplication
import org.springframework.core.env.MapPropertySource
import org.springframework.core.env.StandardEnvironment

class ParameterStoreEnvironmentPostProcessorDiagnosticsTest {

    @Test
    fun `configuration diagnostics omit remote identifiers and endpoint`() {
        val logger = LoggerFactory.getLogger(ParameterStoreEnvironmentPostProcessor::class.java) as Logger
        val appender = ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>().apply { start() }
        val previousLevel = logger.level
        logger.addAppender(appender)
        logger.level = Level.DEBUG

        try {
            val environment = StandardEnvironment().apply {
                propertySources.addFirst(
                    MapPropertySource(
                        "test",
                        mapOf(
                            "bluetape4k.aws.enabled" to true,
                            "bluetape4k.aws.parameter-store.enabled" to false,
                            "bluetape4k.aws.parameter-store.endpoint-override" to "http://endpoint-canary",
                            "bluetape4k.aws.parameter-store.region" to "us-east-1",
                            "bluetape4k.aws.parameter-store.sources[0].path" to "/parameter-path-canary",
                        ),
                    ),
                )
            }

            ParameterStoreEnvironmentPostProcessor().postProcessEnvironment(environment, SpringApplication())
        } finally {
            logger.detachAppender(appender)
            logger.level = previousLevel
        }

        val diagnostics = appender.list.joinToString("\n") { it.formattedMessage }
        diagnostics shouldContain "sourceCount=1"
        diagnostics shouldNotContain "endpoint-canary"
        diagnostics shouldNotContain "parameter-path-canary"
    }
}
