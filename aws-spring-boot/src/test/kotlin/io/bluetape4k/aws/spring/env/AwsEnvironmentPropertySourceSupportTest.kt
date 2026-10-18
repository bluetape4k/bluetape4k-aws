package io.bluetape4k.aws.spring.env

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.concurrent.await
import io.bluetape4k.concurrent.get
import io.bluetape4k.javatimes.millis
import io.bluetape4k.javatimes.seconds
import io.bluetape4k.logging.KLogging
import org.junit.jupiter.api.Test
import org.springframework.core.env.StandardEnvironment
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds

class AwsEnvironmentPropertySourceSupportTest {

    companion object: KLogging()

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
            refreshInterval = 1.seconds(),
            clock = clock,
        )

        environment.getProperty("app.secret") shouldBeEqualTo "initial"
        clock.advance(999.millis())
        environment.getProperty("app.secret") shouldBeEqualTo "initial"

        clock.advance(1.millis())
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
            refreshInterval = 1.seconds(),
            clock = clock,
        )

        clock.advance(1.millis())

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
            refreshInterval = 1.millis(),
            clock = clock,
        )

        clock.advance(1.millis())

        environment.getProperty("app.secret") shouldBeEqualTo "initial"
    }

    @Test
    fun `refreshing property source keeps stable snapshot while reload map is materialized`() {
        val clock = MutableClock()
        val environment = StandardEnvironment()
        val reloadStarted = CountDownLatch(1)
        val releaseReload = CountDownLatch(1)
        // This test pauses a single reload in the materialization window so the old snapshot can be inspected.
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
            refreshInterval = 1.millis(),
            clock = clock,
        )

        val propertySource = environment.propertySources
            .get("bluetape4k.aws.test") as RefreshingAwsMapPropertySource

        clock.advance(1.millis())
        val refresh = executor.submit<String> {
            environment.getProperty("app.secret")
        }

        try {
            reloadStarted.await(5.seconds).shouldBeTrue()

            propertySource.currentValues()["app.secret"] shouldBeEqualTo "initial"

            releaseReload.countDown()
            refresh.get(5.seconds) shouldBeEqualTo "refreshed"
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
                release.await(5.seconds)
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
