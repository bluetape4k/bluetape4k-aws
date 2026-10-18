package io.bluetape4k.aws.spring.appconfig

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.core.read.ListAppender
import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.assertions.shouldNotContain
import io.bluetape4k.logging.KLogging
import io.bluetape4k.support.toUtf8Bytes
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory

class AppConfigDataDecoderTest {

    companion object: KLogging()

    @Test
    fun `decodes properties yaml and json with a common prefix`() {
        AppConfigDataDecoder.decode(
            payload = "feature.enabled=true".toUtf8Bytes(),
            contentType = "text/plain",
            format = AppConfigFormat.PROPERTIES,
            prefix = "app",
        ) shouldBeEqualTo mapOf("app.feature.enabled" to "true")

        AppConfigDataDecoder.decode(
            payload = "feature:\n  enabled: true\n".toUtf8Bytes(),
            contentType = "application/yaml",
            format = AppConfigFormat.YAML,
            prefix = "app",
        ) shouldBeEqualTo mapOf("app.feature.enabled" to true)

        AppConfigDataDecoder.decode(
            payload = "{\"feature\": {\"enabled\": true}, \"items\": [\"a\"]}".toUtf8Bytes(),
            contentType = "application/json",
            format = AppConfigFormat.JSON,
            prefix = "app",
        ) shouldBeEqualTo mapOf("app.feature.enabled" to true, "app.items[0]" to "a")
    }

    @Test
    fun `auto format follows content type and rejects malformed or over-budget payloads`() {
        AppConfigDataDecoder.decode(
            payload = "feature.enabled=true".toUtf8Bytes(),
            contentType = "text/plain",
            format = AppConfigFormat.AUTO,
            prefix = null,
        ) shouldBeEqualTo mapOf("feature.enabled" to "true")

        assertFailsWith<IllegalArgumentException> {
            AppConfigDataDecoder.decode(
                payload = "{}".toUtf8Bytes(),
                contentType = "application/octet-stream",
                format = AppConfigFormat.JSON,
                prefix = null,
                maxPayloadBytes = 1,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            AppConfigDataDecoder.decode(
                payload = "not-json".toUtf8Bytes(),
                contentType = "application/json",
                format = AppConfigFormat.AUTO,
                prefix = null,
            )
        }
    }

    @Test
    fun `enforces flatten depth and property count budgets`() {
        assertFailsWith<IllegalArgumentException> {
            AppConfigDataDecoder.decode(
                payload = "{\"outer\": {\"inner\": true}}".toUtf8Bytes(),
                contentType = "application/json",
                format = AppConfigFormat.JSON,
                prefix = null,
                maxDepth = 1,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            AppConfigDataDecoder.decode(
                payload = "{\"first\": true, \"second\": false}".toUtf8Bytes(),
                contentType = "application/json",
                format = AppConfigFormat.JSON,
                prefix = null,
                maxPropertyCount = 1,
            )
        }
    }

    @Test
    fun `decoded property diagnostics omit configured values`() {
        val logger = LoggerFactory.getLogger(AppConfigDataDecoder::class.java) as Logger
        val appender = ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>().apply { start() }
        val previousLevel = logger.level
        logger.addAppender(appender)
        logger.level = Level.DEBUG

        try {
            AppConfigDataDecoder.decode(
                payload = "{\"database.password\":\"appconfig-secret-canary\"}".toUtf8Bytes(),
                contentType = "application/json",
                format = AppConfigFormat.JSON,
                prefix = null,
            )
        } finally {
            logger.detachAppender(appender)
            logger.level = previousLevel
        }

        val diagnostics = appender.list.joinToString("\n") { it.formattedMessage }
        diagnostics shouldNotContain "appconfig-secret-canary"
        diagnostics shouldContain "propertyCount=1"
    }
}
