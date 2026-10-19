package io.bluetape4k.aws.spring.secretsmanager

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.core.read.ListAppender
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.assertions.shouldNotContain
import io.bluetape4k.aws.spring.AwsProperties
import io.bluetape4k.aws.spring.config.AwsConfigDataSupport
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.boot.bootstrap.ConfigurableBootstrapContext
import java.net.URI

class SecretsManagerConfigDataSdkAdapterDiagnosticsTest {

    @Test
    fun `client creation diagnostics omit configuration values`() {
        val logger = LoggerFactory.getLogger(SecretsManagerConfigDataSdkAdapter::class.java) as Logger
        val appender = ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>().apply { start() }
        val previousLevel = logger.level
        logger.addAppender(appender)
        logger.level = Level.DEBUG

        try {
            val configuration = AwsConfigDataSupport.ResolverConfiguration(
                aws = AwsProperties(region = "us-east-1"),
                backend = SecretsManagerProperties(
                    region = "us-east-1",
                    endpointOverride = URI("http://endpoint-canary"),
                    sources = listOf(SecretsManagerProperties.Source(secretId = "secret-id-canary")),
                ),
                bootstrapContext = mockk<ConfigurableBootstrapContext>(relaxed = true),
            )

            val client = SecretsManagerConfigDataSdkAdapter.create(configuration)
            SecretsManagerConfigDataSdkAdapter.close(client)
        } finally {
            logger.detachAppender(appender)
            logger.level = previousLevel
        }

        val diagnostics = appender.list.joinToString("\n") { it.formattedMessage }
        diagnostics shouldContain "Create SecretsManagerClient"
        diagnostics shouldNotContain "endpoint-canary"
        diagnostics shouldNotContain "secret-id-canary"
    }
}
