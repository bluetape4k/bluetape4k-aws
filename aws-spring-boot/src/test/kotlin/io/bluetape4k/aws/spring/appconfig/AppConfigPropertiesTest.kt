package io.bluetape4k.aws.spring.appconfig

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldNotContain
import org.junit.jupiter.api.Test
import java.net.URI
import java.time.Duration

class AppConfigPropertiesTest {

    @Test
    fun `defaults keep refresh disabled and enforce the minimum poll interval`() {
        val properties = AppConfigProperties()

        properties.enabled shouldBeEqualTo true
        properties.failFast shouldBeEqualTo true
        properties.separator shouldBeEqualTo "#"
        properties.refreshInterval shouldBeEqualTo null
        properties.requiredMinimumPollInterval shouldBeEqualTo Duration.ofSeconds(15)
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
            Duration.ofSeconds(14),
            Duration.ofHours(24).plusSeconds(1),
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

        properties.toString() shouldNotContain "localhost"
        properties.toString() shouldNotContain "token"
    }
}
