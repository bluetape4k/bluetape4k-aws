package io.bluetape4k.aws.spring.appconfig

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.aws.spring.config.AwsConfigDataBackend
import io.bluetape4k.aws.spring.config.AwsConfigDataLocationParser
import io.bluetape4k.aws.spring.config.AwsConfigDataSource
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import org.junit.jupiter.api.Test
import org.springframework.boot.context.config.ConfigDataLocation

class AppConfigDataLocationParserTest {

    companion object: KLogging()

    private val parser = AwsConfigDataLocationParser()

    @Test
    fun `parses optional app config source and decodes components once`() {
        val parsed = parser.parse(
            ConfigDataLocation.of(
                "optional:aws-app-config:application%20one#profile%2523blue#environment?format=json&prefix=service",
            ),
        )
        log.debug { "parsed=$parsed" }

        parsed.backend shouldBeEqualTo AwsConfigDataBackend.APP_CONFIG
        parsed.optional.shouldBeTrue()
        parsed.source shouldBeEqualTo AwsConfigDataSource.AppConfig(
            application = "application one",
            profile = "profile%23blue",
            environment = "environment",
            prefix = "service",
            format = AppConfigFormat.JSON,
        )
    }

    @Test
    fun `supports a configured separator and rejects malformed components`() {
        val parsed = parser.parse(
            ConfigDataLocation.of("aws-app-config:app;profile;env?format=properties"),
            separator = ";",
        )
        log.debug { "parsed=$parsed" }

        parsed.source shouldBeEqualTo AwsConfigDataSource.AppConfig(
            application = "app",
            profile = "profile",
            environment = "env",
            prefix = null,
            format = AppConfigFormat.PROPERTIES,
        )

        listOf(
            "aws-app-config:app#profile",
            "aws-app-config:app##env",
            "aws-app-config:app#profile#env#extra",
            "aws-app-config:app#profile#env?format=toml",
            "aws-app-config:app#profile#env?format=json&format=yaml",
            "aws-app-config:app#profile#env?unknown=value",
        ).forEach { location ->
            assertFailsWith<IllegalArgumentException> {
                parser.parse(ConfigDataLocation.of(location))
            }
        }
    }

    @Test
    fun `rejects invalid separator and control characters`() {
        assertFailsWith<IllegalArgumentException> {
            parser.parse(ConfigDataLocation.of("aws-app-config:app#profile#env"), separator = "")
        }
        assertFailsWith<IllegalArgumentException> {
            parser.parse(ConfigDataLocation.of("aws-app-config:app#profile#env"), separator = "##")
        }
        listOf("?", "%", "&", "=", "\u0001").forEach { separator ->
            assertFailsWith<IllegalArgumentException> {
                parser.parse(ConfigDataLocation.of("aws-app-config:app#profile#env"), separator = separator)
            }
        }
        assertFailsWith<IllegalArgumentException> {
            parser.parse(ConfigDataLocation.of("aws-app-config:app%00#profile#env"))
        }
    }
}
