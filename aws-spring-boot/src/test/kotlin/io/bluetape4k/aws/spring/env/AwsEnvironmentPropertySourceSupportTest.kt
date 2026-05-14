package io.bluetape4k.aws.spring.env

import io.bluetape4k.assertions.shouldBeEqualTo
import org.junit.jupiter.api.Test
import org.springframework.core.env.StandardEnvironment
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicInteger

class AwsEnvironmentPropertySourceSupportTest {

    @Test
    fun `refreshing property source reloads after refresh interval`() {
        val clock = MutableClock()
        val reloadCount = AtomicInteger()
        val environment = StandardEnvironment()

        environment.addAwsPropertySource(
            loaded = AwsLoadedPropertySource(
                name = "bluetape4k.aws.test",
                values = mapOf("app.secret" to "initial"),
                reload = {
                    mapOf("app.secret" to "refreshed-${reloadCount.incrementAndGet()}")
                },
            ),
            refreshInterval = Duration.ofSeconds(1),
            clock = clock,
        )

        environment.getProperty("app.secret") shouldBeEqualTo "initial"
        clock.advance(Duration.ofMillis(999))
        environment.getProperty("app.secret") shouldBeEqualTo "initial"

        clock.advance(Duration.ofMillis(1))
        environment.getProperty("app.secret") shouldBeEqualTo "refreshed-1"
        environment.getProperty("app.secret") shouldBeEqualTo "refreshed-1"
    }

    @Test
    fun `refreshing property source keeps previous values when reload is skipped`() {
        val clock = MutableClock()
        val environment = StandardEnvironment()

        environment.addAwsPropertySource(
            loaded = AwsLoadedPropertySource(
                name = "bluetape4k.aws.test",
                values = mapOf("app.secret" to "initial"),
                reload = { null },
            ),
            refreshInterval = Duration.ofMillis(1),
            clock = clock,
        )

        clock.advance(Duration.ofMillis(1))

        environment.getProperty("app.secret") shouldBeEqualTo "initial"
    }

    @Test
    fun `refreshing property source keeps previous values when reload fails`() {
        val clock = MutableClock()
        val environment = StandardEnvironment()

        environment.addAwsPropertySource(
            loaded = AwsLoadedPropertySource(
                name = "bluetape4k.aws.test",
                values = mapOf("app.secret" to "initial"),
                reload = { throw IllegalStateException("remote unavailable") },
            ),
            refreshInterval = Duration.ofMillis(1),
            clock = clock,
        )

        clock.advance(Duration.ofMillis(1))

        environment.getProperty("app.secret") shouldBeEqualTo "initial"
    }

    private class MutableClock(
        private var current: Instant = Instant.parse("2026-05-14T00:00:00Z"),
    ): Clock() {

        override fun getZone(): ZoneId = ZoneId.of("UTC")

        override fun withZone(zone: ZoneId): Clock = this

        override fun instant(): Instant = current

        fun advance(duration: Duration) {
            current = current.plus(duration)
        }
    }
}
