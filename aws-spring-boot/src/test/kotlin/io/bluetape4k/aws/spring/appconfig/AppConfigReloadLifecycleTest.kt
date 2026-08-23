package io.bluetape4k.aws.spring.appconfig

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import org.awaitility.kotlin.await
import org.junit.jupiter.api.Test
import org.springframework.boot.context.config.ConfigDataLocation
import io.bluetape4k.aws.spring.config.AwsConfigDataLocationParser
import io.bluetape4k.aws.spring.config.AwsConfigDataResource
import java.time.Duration
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.atomic.AtomicInteger

class AppConfigReloadLifecycleTest {

    @Test
    fun `one scheduler and one task per refreshable source update the latest values`() {
        val client = RefreshingClient()
        val source = source(client, refreshInterval = Duration.ofSeconds(15))
        var createdPoolSize = 0
        val lifecycle = AppConfigReloadLifecycle(
            sourcesSupplier = { listOf(source, source) },
            executorFactory = { size ->
                createdPoolSize = size
                ScheduledThreadPoolExecutor(size)
            },
            initialDelay = { Duration.ZERO },
            randomDelay = { Duration.ofMillis(1) },
        )

        lifecycle.start()
        await.atMost(Duration.ofSeconds(2)).untilAsserted {
            source.getProperty("feature.value") shouldBeEqualTo "new"
        }
        lifecycle.scheduledResourceCount() shouldBeEqualTo 1
        lifecycle.schedulerPoolSize() shouldBeEqualTo 1
        createdPoolSize shouldBeEqualTo 1
        client.requestedTokens shouldBeEqualTo listOf("token-1")

        lifecycle.stop()
        lifecycle.isRunning shouldBeEqualTo false
        lifecycle.scheduledResourceCount() shouldBeEqualTo 0
    }

    @Test
    fun `empty response retains values while advancing the token`() {
        val client = EmptyResponseClient()
        val source = source(client, refreshInterval = Duration.ofSeconds(15))
        val lifecycle = AppConfigReloadLifecycle(
            sourcesSupplier = { listOf(source) },
            executorFactory = ::ScheduledThreadPoolExecutor,
            initialDelay = { Duration.ZERO },
        )

        lifecycle.start()
        await.atMost(Duration.ofSeconds(2)).untilAsserted {
            client.polls.get() shouldBeEqualTo 1
        }
        source.getProperty("feature.value") shouldBeEqualTo "old"
        source.configurationToken shouldBeEqualTo "token-2"
        lifecycle.stop()
    }

    @Test
    fun `runtime client replaces bootstrap client before polling`() {
        val bootstrapClient = RecordingClient("bootstrap")
        val runtimeClient = RecordingClient("runtime")
        val source = source(bootstrapClient, refreshInterval = Duration.ofSeconds(15))
        val lifecycle = AppConfigReloadLifecycle(
            sourcesSupplier = { listOf(source) },
            executorFactory = ::ScheduledThreadPoolExecutor,
            initialDelay = { Duration.ZERO },
            runtimeClientSupplier = { runtimeClient },
        )

        lifecycle.start()
        await.atMost(Duration.ofSeconds(2)).untilAsserted {
            source.getProperty("feature.value") shouldBeEqualTo "runtime"
        }

        bootstrapClient.requestedTokens shouldBeEqualTo emptyList()
        runtimeClient.requestedTokens shouldBeEqualTo listOf("token-1")
        lifecycle.stop()
    }

    @Test
    fun `decode failure retains map while advancing response token`() {
        val client = InvalidJsonClient()
        val source = source(
            client = client,
            refreshInterval = Duration.ofSeconds(15),
            format = AppConfigFormat.JSON,
            initialResponse = AppConfigDataResponse("token-1", 0, "application/json", byteArrayOf()),
        )
        val lifecycle = AppConfigReloadLifecycle(
            sourcesSupplier = { listOf(source) },
            executorFactory = ::ScheduledThreadPoolExecutor,
            initialDelay = { Duration.ZERO },
        )

        lifecycle.start()
        await.atMost(Duration.ofSeconds(2)).untilAsserted {
            client.polls.get() shouldBeEqualTo 1
        }
        source.getProperty("feature.value") shouldBeEqualTo "old"
        source.configurationToken shouldBeEqualTo "token-2"
        lifecycle.stop()
    }

    @Test
    fun `transport failure discards session and retries with a new session`() {
        val client = RetryingClient()
        val source = source(client, refreshInterval = Duration.ofSeconds(15))
        val lifecycle = AppConfigReloadLifecycle(
            sourcesSupplier = { listOf(source) },
            executorFactory = ::ScheduledThreadPoolExecutor,
            initialDelay = { Duration.ZERO },
            randomDelay = { Duration.ofMillis(1) },
        )

        lifecycle.start()
        await.atMost(Duration.ofSeconds(2)).untilAsserted {
            source.getProperty("feature.value") shouldBeEqualTo "new"
        }
        client.startCount shouldBeEqualTo 1
        client.requestedTokens shouldBeEqualTo listOf("token-1", "token-new")
        lifecycle.stop()
    }

    @Test
    fun `cancellation does not log or reschedule a failed poll`() {
        val client = CancellationClient()
        val source = source(client, refreshInterval = Duration.ofSeconds(15))
        val lifecycle = AppConfigReloadLifecycle(
            sourcesSupplier = { listOf(source) },
            executorFactory = ::ScheduledThreadPoolExecutor,
            initialDelay = { Duration.ZERO },
        )

        lifecycle.start()
        await.atMost(Duration.ofSeconds(2)).untilAsserted {
            client.polls.get() shouldBeEqualTo 1
        }
        lifecycle.stop()
        lifecycle.scheduledResourceCount() shouldBeEqualTo 0
    }

    @Test
    fun `startup failure rolls back scheduled resources and runtime client`() {
        val client = ClosableClient()
        val source = source(client, refreshInterval = Duration.ofSeconds(15))
        val lifecycle = AppConfigReloadLifecycle(
            sourcesSupplier = { listOf(source) },
            executorFactory = { error("scheduler unavailable") },
            runtimeClientSupplier = { client },
        )

        assertFailsWith<IllegalStateException> { lifecycle.start() }
        client.closed.shouldBeTrue()
        lifecycle.scheduledResourceCount() shouldBeEqualTo 0
        lifecycle.isRunning shouldBeEqualTo false
    }

    @Test
    fun `server poll interval outside bounds falls back to configured minimum`() {
        val client = EmptyResponseClient()
        val source = source(
            client = client,
            refreshInterval = Duration.ofSeconds(15),
            initialResponse = AppConfigDataResponse("token-1", 86_401, "text/plain", byteArrayOf()),
        )
        val lifecycle = AppConfigReloadLifecycle(sourcesSupplier = { listOf(source) })

        lifecycle.effectivePollDelay(source) shouldBeEqualTo Duration.ofSeconds(15)
        source.advance(AppConfigDataResponse("token-2", 30, "text/plain", byteArrayOf()))
        lifecycle.effectivePollDelay(source) shouldBeEqualTo Duration.ofSeconds(30)
    }

    private fun source(
        client: AppConfigDataSessionClient,
        refreshInterval: Duration,
        format: AppConfigFormat = AppConfigFormat.PROPERTIES,
        initialResponse: AppConfigDataResponse = AppConfigDataResponse("token-1", 15, "text/plain", byteArrayOf()),
    ): AppConfigDataPropertySource {
        val resource = AwsConfigDataResource.from(
            AwsConfigDataLocationParser().parse(ConfigDataLocation.of("aws-app-config:app#profile#env")),
        )
        return AppConfigDataPropertySource(
            name = "opaque-source",
            initialValues = mapOf("feature.value" to "old"),
            resource = resource,
            client = client,
            request = AppConfigDataStartRequest("app", "profile", "env", 15),
            initialResponse = initialResponse,
            format = format,
            prefix = null,
            refreshInterval = refreshInterval,
            requiredMinimumPollInterval = Duration.ofSeconds(15),
        )
    }

    private class RefreshingClient : AppConfigDataSessionClient {
        val requestedTokens = mutableListOf<String>()

        override fun startConfigurationSession(request: AppConfigDataStartRequest): AppConfigDataSession =
            AppConfigDataSession("token-1")

        override fun getLatestConfiguration(configurationToken: String): AppConfigDataResponse {
            requestedTokens += configurationToken
            return AppConfigDataResponse("token-2", 15, "text/plain", "feature.value=new".toByteArray())
        }

        override fun close() = Unit
    }

    private class EmptyResponseClient : AppConfigDataSessionClient {
        val polls = AtomicInteger()

        override fun startConfigurationSession(request: AppConfigDataStartRequest): AppConfigDataSession =
            AppConfigDataSession("token-1")

        override fun getLatestConfiguration(configurationToken: String): AppConfigDataResponse {
            polls.incrementAndGet()
            return AppConfigDataResponse("token-2", 15, "text/plain", byteArrayOf())
        }

        override fun close() = Unit
    }

    private class RecordingClient(private val value: String) : AppConfigDataSessionClient {
        val requestedTokens = mutableListOf<String>()

        override fun startConfigurationSession(request: AppConfigDataStartRequest): AppConfigDataSession =
            AppConfigDataSession("token-1")

        override fun getLatestConfiguration(configurationToken: String): AppConfigDataResponse {
            requestedTokens += configurationToken
            return AppConfigDataResponse("token-2", 15, "text/plain", "feature.value=$value".toByteArray())
        }

        override fun close() = Unit
    }

    private class InvalidJsonClient : AppConfigDataSessionClient {
        val polls = AtomicInteger()

        override fun startConfigurationSession(request: AppConfigDataStartRequest): AppConfigDataSession =
            AppConfigDataSession("token-1")

        override fun getLatestConfiguration(configurationToken: String): AppConfigDataResponse {
            polls.incrementAndGet()
            return AppConfigDataResponse("token-2", 15, "application/json", "not-json".toByteArray())
        }

        override fun close() = Unit
    }

    private class RetryingClient : AppConfigDataSessionClient {
        val requestedTokens = mutableListOf<String>()
        var startCount = 0
            private set

        override fun startConfigurationSession(request: AppConfigDataStartRequest): AppConfigDataSession {
            startCount += 1
            return AppConfigDataSession("token-new")
        }

        override fun getLatestConfiguration(configurationToken: String): AppConfigDataResponse {
            requestedTokens += configurationToken
            if (requestedTokens.size == 1) error("transport failure")
            return AppConfigDataResponse("token-final", 15, "text/plain", "feature.value=new".toByteArray())
        }

        override fun close() = Unit
    }

    private class CancellationClient : AppConfigDataSessionClient {
        val polls = AtomicInteger()

        override fun startConfigurationSession(request: AppConfigDataStartRequest): AppConfigDataSession =
            AppConfigDataSession("token-1")

        override fun getLatestConfiguration(configurationToken: String): AppConfigDataResponse {
            polls.incrementAndGet()
            throw java.util.concurrent.CancellationException("cancelled")
        }

        override fun close() = Unit
    }

    private class ClosableClient : AppConfigDataSessionClient {
        var closed = false
            private set

        override fun startConfigurationSession(request: AppConfigDataStartRequest): AppConfigDataSession =
            AppConfigDataSession("token-1")

        override fun getLatestConfiguration(configurationToken: String): AppConfigDataResponse =
            AppConfigDataResponse("token-2", 15, "text/plain", byteArrayOf())

        override fun close() {
            closed = true
        }
    }
}
