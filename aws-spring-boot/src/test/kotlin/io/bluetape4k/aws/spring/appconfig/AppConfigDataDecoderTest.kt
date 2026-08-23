package io.bluetape4k.aws.spring.appconfig

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import org.junit.jupiter.api.Test

class AppConfigDataDecoderTest {

    @Test
    fun `decodes properties yaml and json with a common prefix`() {
        AppConfigDataDecoder.decode(
            payload = "feature.enabled=true".toByteArray(),
            contentType = "text/plain",
            format = AppConfigFormat.PROPERTIES,
            prefix = "app",
        ) shouldBeEqualTo mapOf("app.feature.enabled" to "true")

        AppConfigDataDecoder.decode(
            payload = "feature:\n  enabled: true\n".toByteArray(),
            contentType = "application/yaml",
            format = AppConfigFormat.YAML,
            prefix = "app",
        ) shouldBeEqualTo mapOf("app.feature.enabled" to true)

        AppConfigDataDecoder.decode(
            payload = "{\"feature\": {\"enabled\": true}, \"items\": [\"a\"]}".toByteArray(),
            contentType = "application/json",
            format = AppConfigFormat.JSON,
            prefix = "app",
        ) shouldBeEqualTo mapOf("app.feature.enabled" to true, "app.items[0]" to "a")
    }

    @Test
    fun `auto format follows content type and rejects malformed or over-budget payloads`() {
        AppConfigDataDecoder.decode(
            payload = "feature.enabled=true".toByteArray(),
            contentType = "text/plain",
            format = AppConfigFormat.AUTO,
            prefix = null,
        ) shouldBeEqualTo mapOf("feature.enabled" to "true")

        assertFailsWith<IllegalArgumentException> {
            AppConfigDataDecoder.decode(
                payload = "{}".toByteArray(),
                contentType = "application/octet-stream",
                format = AppConfigFormat.JSON,
                prefix = null,
                maxPayloadBytes = 1,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            AppConfigDataDecoder.decode(
                payload = "not-json".toByteArray(),
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
                payload = "{\"outer\": {\"inner\": true}}".toByteArray(),
                contentType = "application/json",
                format = AppConfigFormat.JSON,
                prefix = null,
                maxDepth = 1,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            AppConfigDataDecoder.decode(
                payload = "{\"first\": true, \"second\": false}".toByteArray(),
                contentType = "application/json",
                format = AppConfigFormat.JSON,
                prefix = null,
                maxPropertyCount = 1,
            )
        }
    }
}
