package io.bluetape4k.aws.ktor.imds

import aws.smithy.kotlin.runtime.http.engine.CloseableHttpClientEngine
import aws.smithy.kotlin.runtime.http.HttpException
import aws.sdk.kotlin.runtime.config.imds.EndpointMode
import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldBeSameInstanceAs
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.logging.KLogging
import io.ktor.server.application.install
import io.ktor.server.testing.testApplication
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.URI
import java.net.Proxy
import java.time.Duration

class ImdsKtorPluginTest {

    companion object: KLogging()

    private val operations = mockk<ImdsKtorOperations>(relaxed = true)

    @BeforeEach
    fun resetMocks() {
        clearMocks(operations)
    }

    @Test
    fun `plugin stores injected operations`() = testApplication {
        application {
            install(ImdsKtorPlugin) {
                imdsOperations = operations
            }
        }

        startApplication()

        application.imds() shouldBeSameInstanceAs operations
        application.imdsOrNull() shouldBeSameInstanceAs operations
        application.attributes[ImdsKtorRuntimeKey].operations shouldBeSameInstanceAs operations
    }

    @Test
    fun `injected operations bypass client only validation`() = testApplication {
        application {
            install(ImdsKtorPlugin) {
                imdsOperations = operations
                tokenTtl = Duration.ZERO
                requestTimeout = Duration.ZERO
                retries = -1
            }
        }

        startApplication()

        application.imds() shouldBeSameInstanceAs operations
    }

    @Test
    fun `plugin does not store operations when disabled`() = testApplication {
        application {
            install(ImdsKtorPlugin) {
                enabled = false
            }
        }

        startApplication()

        application.imdsOrNull().shouldBeNull()
        val error = assertFailsWith<IllegalStateException> { application.imds() }
        error.message shouldContain "disabled"
    }

    @Test
    fun `runtime closes plugin owned engine once`() {
        val engine = mockk<CloseableHttpClientEngine>(relaxed = true)
        val runtime = ImdsKtorRuntime(operations = operations, ownedEngine = engine)

        runtime.stop()
        runtime.stop()

        verify(exactly = 1) { engine.close() }
    }

    @Test
    fun `runtime installation failure closes owned engine and preserves both errors`() {
        val engine = mockk<CloseableHttpClientEngine>()
        val cleanupFailure = IllegalStateException("cleanup failed")
        every { engine.close() } throws cleanupFailure
        val runtime = ImdsKtorRuntime(operations = operations, ownedEngine = engine)
        val installFailure = IllegalArgumentException("installation failed")

        val error = assertFailsWith<IllegalArgumentException> {
            installImdsRuntimeWithRollback(runtime) { throw installFailure }
        }

        error shouldBeSameInstanceAs installFailure
        error.suppressed.single() shouldBeSameInstanceAs cleanupFailure
        verify(exactly = 1) { engine.close() }
    }

    @Test
    fun `config validation rejects non positive token ttl`() {
        val error = assertFailsWith<IllegalArgumentException> {
            ImdsKtorPluginConfig().apply { tokenTtl = Duration.ZERO }.toRuntime()
        }

        error.message shouldContain "tokenTtl"
    }

    @Test
    fun `config validation rejects non positive request timeout`() {
        val error = assertFailsWith<IllegalArgumentException> {
            ImdsKtorPluginConfig().apply { requestTimeout = Duration.ZERO }.toRuntime()
        }

        error.message shouldContain "requestTimeout"
    }

    @Test
    fun `config validation rejects negative retries`() {
        val error = assertFailsWith<IllegalArgumentException> {
            ImdsKtorPluginConfig().apply { retries = -1 }.toRuntime()
        }

        error.message shouldContain "retries"
    }

    @Test
    fun `remote insecure endpoint is rejected unless it is AWS default endpoint`() {
        val error = assertFailsWith<IllegalArgumentException> {
            ImdsEndpointPolicy.resolve(
                endpoint = URI("http://metadata.example/") ,
                endpointMode = EndpointMode.IPv4,
                allowInsecureLocalHttpEndpoint = false,
            )
        }

        error.message shouldContain "HTTPS"
        ImdsEndpointPolicy.resolve(null, EndpointMode.IPv4, false).toString() shouldBeEqualTo
            "http://169.254.169.254"
        ImdsEndpointPolicy.resolve(null, EndpointMode.IPv6, false).toString() shouldBeEqualTo
            "http://[fd00:ec2::254]"
    }

    @Test
    fun `loopback http endpoint requires explicit opt in`() {
        val endpoint = URI("http://127.0.0.1:12345")

        assertFailsWith<IllegalArgumentException> {
            ImdsEndpointPolicy.resolve(endpoint, EndpointMode.IPv4, false)
        }
        ImdsEndpointPolicy.resolve(endpoint, EndpointMode.IPv4, true) shouldBeEqualTo endpoint
    }

    @Test
    fun `plugin owned engine disables retries proxies and redirects`() {
        val engine = ImdsHttpClientFactory.createEngine(Duration.ofMillis(1500)) as ImdsOwnedHttpClientEngine
        try {
            engine.client.retryOnConnectionFailure.shouldBeFalse()
            engine.client.proxy shouldBeEqualTo Proxy.NO_PROXY
            engine.client.followRedirects.shouldBeFalse()
            engine.client.followSslRedirects.shouldBeFalse()
            engine.client.connectTimeoutMillis shouldBeEqualTo 1500
            engine.client.readTimeoutMillis shouldBeEqualTo 1500
            engine.client.writeTimeoutMillis shouldBeEqualTo 1500
        } finally {
            engine.close()
        }

        engine.client.dispatcher.executorService.isShutdown.shouldBeTrue()
    }

    @Test
    fun `public endpoint configuration uses AWS mode type and safe defaults`() {
        val config = ImdsKtorPluginConfig()

        config.endpointMode shouldBeEqualTo EndpointMode.IPv4
        config.allowInsecureLocalHttpEndpoint.shouldBeFalse()
    }

    @Test
    fun `retry matrix accepts only server errors and Smithy retryable transport failures`() {
        ImdsRetryPolicy.shouldRetry(ImdsHttpResponseException(503)).shouldBeTrue()
        ImdsRetryPolicy.shouldRetry(ImdsHttpResponseException(401)).shouldBeFalse()
        ImdsRetryPolicy.shouldRetry(ImdsHttpResponseException(302)).shouldBeFalse()
        ImdsRetryPolicy.shouldRetry(HttpException("temporary", retryable = true)).shouldBeTrue()
        ImdsRetryPolicy.shouldRetry(HttpException("terminal", retryable = false)).shouldBeFalse()
    }

    @Test
    fun `full jitter uses capped exponential bounds and never returns zero`() {
        (0..8).map(ImdsRetryPolicy::backoffCapMillis) shouldBeEqualTo
            listOf(200L, 400L, 800L, 1_600L, 3_200L, 6_400L, 12_800L, 20_000L, 20_000L)

        ImdsRetryPolicy.fullJitterMillis(0) { lower, _ -> lower } shouldBeEqualTo 1L
        ImdsRetryPolicy.fullJitterMillis(0) { _, exclusiveUpper -> exclusiveUpper - 1 } shouldBeEqualTo 200L
    }
}
