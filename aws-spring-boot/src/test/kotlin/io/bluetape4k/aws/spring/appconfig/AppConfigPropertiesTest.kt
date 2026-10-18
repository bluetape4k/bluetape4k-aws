package io.bluetape4k.aws.spring.appconfig

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldNotContain
import io.bluetape4k.javatimes.hours
import io.bluetape4k.javatimes.seconds
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import org.junit.jupiter.api.Test
import java.net.URI
import java.time.Duration

class AppConfigPropertiesTest {

    companion object: KLogging()

    @Test
    fun `defaults keep refresh disabled and enforce the minimum poll interval`() {
        val properties = AppConfigProperties()

        properties.enabled.shouldBeTrue()
        properties.failFast.shouldBeTrue()
        properties.separator shouldBeEqualTo "#"
        properties.refreshInterval.shouldBeNull()
        properties.requiredMinimumPollInterval shouldBeEqualTo 15.seconds()
    }

    @Test
    fun `validates interval bounds and endpoint region invariant`() {
        AppConfigProperties(
            region = "ap-northeast-2",
            endpointOverride = URI("http://localhost:2772"),
            refreshInterval = Duration.ofMinutes(1),
            requiredMinimumPollInterval = Duration.ofSeconds(30),
        )

        listOf(
            Duration.ZERO,
            14.seconds(),
            24.hours() + 1.seconds(),
        ).forEach { interval ->
            assertFailsWith<IllegalArgumentException> {
                AppConfigProperties(requiredMinimumPollInterval = interval)
            }
        }

        assertFailsWith<IllegalArgumentException> {
            AppConfigProperties(endpointOverride = URI("http://localhost:2772"))
        }
        assertFailsWith<IllegalArgumentException> {
            AppConfigProperties(separator = "")
        }
        listOf("?", "%", "&", "=", "\u0001").forEach { separator ->
            assertFailsWith<IllegalArgumentException> {
                AppConfigProperties(separator = separator)
            }
        }
    }

    @Test
    fun `does not expose endpoint or token-like values in string form`() {
        val properties = AppConfigProperties(
            region = "ap-northeast-2",
            endpointOverride = URI("http://localhost:2772"),
        )

        log.debug { "properties=$properties" }
        with(properties.toString()) {
            this shouldNotContain "localhost"
            this shouldNotContain "token"
        }
    }
}
