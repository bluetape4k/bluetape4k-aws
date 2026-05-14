package io.bluetape4k.aws.spring.env

import io.bluetape4k.assertions.shouldBeEqualTo
import org.junit.jupiter.api.Test
import org.springframework.core.env.StandardEnvironment
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
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

    @Test
    fun `refreshing property source keeps stable snapshot while reload map is materialized`() {
        val clock = MutableClock()
        val environment = StandardEnvironment()
        val reloadStarted = CountDownLatch(1)
        val releaseReload = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()

        environment.addAwsPropertySource(
            loaded = AwsLoadedPropertySource(
                name = "bluetape4k.aws.test",
                values = mapOf("app.secret" to "initial"),
                reload = {
                    BlockingMap(
                        backing = mapOf("app.secret" to "refreshed"),
                        started = reloadStarted,
                        release = releaseReload,
                    )
                },
            ),
            refreshInterval = Duration.ofMillis(1),
            clock = clock,
        )

        val propertySource = environment.propertySources
            .get("bluetape4k.aws.test") as RefreshingAwsMapPropertySource

        clock.advance(Duration.ofMillis(1))
        val refresh = executor.submit<String> {
            environment.getProperty("app.secret")
        }

        try {
            reloadStarted.await(5, TimeUnit.SECONDS) shouldBeEqualTo true

            propertySource.currentValues()["app.secret"] shouldBeEqualTo "initial"

            releaseReload.countDown()
            refresh.get(5, TimeUnit.SECONDS) shouldBeEqualTo "refreshed"
            environment.getProperty("app.secret") shouldBeEqualTo "refreshed"
        } finally {
            releaseReload.countDown()
            executor.shutdownNow()
        }
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

    private class BlockingMap(
        private val backing: Map<String, Any>,
        private val started: CountDownLatch,
        private val release: CountDownLatch,
    ): Map<String, Any> {

        override val entries: Set<Map.Entry<String, Any>>
            get() {
                started.countDown()
                release.await(5, TimeUnit.SECONDS)
                return backing.entries
            }

        override val keys: Set<String>
            get() = backing.keys

        override val size: Int
            get() = backing.size

        override val values: Collection<Any>
            get() = backing.values

        override fun containsKey(key: String): Boolean =
            backing.containsKey(key)

        override fun containsValue(value: Any): Boolean =
            backing.containsValue(value)

        override fun get(key: String): Any? =
            backing[key]

        override fun isEmpty(): Boolean =
            backing.isEmpty()
    }

    @Suppress("UNCHECKED_CAST")
    private fun RefreshingAwsMapPropertySource.currentValues(): Map<String, Any> {
        val field = javaClass.getDeclaredField("currentValues")
        field.isAccessible = true
        return field.get(this) as Map<String, Any>
    }
}
