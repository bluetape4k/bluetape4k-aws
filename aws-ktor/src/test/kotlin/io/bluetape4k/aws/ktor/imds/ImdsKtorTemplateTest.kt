package io.bluetape4k.aws.ktor.imds

import aws.sdk.kotlin.runtime.config.imds.EndpointMode
import aws.smithy.kotlin.runtime.http.HttpBody
import aws.smithy.kotlin.runtime.http.HttpCall
import aws.smithy.kotlin.runtime.http.HttpMethod
import aws.smithy.kotlin.runtime.http.HttpStatusCode
import aws.smithy.kotlin.runtime.http.SdkHttpClient
import aws.smithy.kotlin.runtime.http.engine.CloseableHttpClientEngine
import aws.smithy.kotlin.runtime.http.engine.HttpClientEngineConfig
import aws.smithy.kotlin.runtime.http.request.HttpRequest
import aws.smithy.kotlin.runtime.http.response.HttpResponse
import aws.smithy.kotlin.runtime.io.SdkBuffer
import aws.smithy.kotlin.runtime.io.SdkByteReadChannel
import aws.smithy.kotlin.runtime.io.SdkSource
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.concurrent.await
import io.bluetape4k.javatimes.hours
import io.bluetape4k.javatimes.inNanos
import io.bluetape4k.javatimes.millis
import io.bluetape4k.javatimes.seconds
import io.bluetape4k.junit5.coroutines.runSuspendIO
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.Test
import java.io.IOException
import java.net.InetSocketAddress
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class ImdsKtorTemplateTest {

    @Test
    fun `http call cleanup completes after caller cancellation`() = runSuspendIO {
        val callerJob = Job()
        val callJob = SupervisorJob()
        val readStarted = CompletableDeferred<Unit>()
        val cancellationRequested = CompletableDeferred<Unit>()
        val allowReadToFinish = CompletableDeferred<Unit>()
        val responseChannel = object : SdkByteReadChannel {
            override val availableForRead: Int = 0
            override val isClosedForRead: Boolean
                get() = allowReadToFinish.isCompleted
            override val isClosedForWrite: Boolean
                get() = cancellationRequested.isCompleted
            override val closedCause: Throwable? = null

            override suspend fun read(sink: SdkBuffer, limit: Long): Long {
                readStarted.complete(Unit)
                cancellationRequested.await()
                allowReadToFinish.await()
                return -1L
            }

            override fun cancel(cause: Throwable?): Boolean = cancellationRequested.complete(Unit)
        }
        val responseBody = object : HttpBody.ChannelContent() {
            override fun readFrom(): SdkByteReadChannel = responseChannel
        }
        val activeChild = CoroutineScope(Dispatchers.Unconfined + callJob).launch {
            responseChannel.read(SdkBuffer(), Long.MAX_VALUE)
        }
        readStarted.await()
        val call = HttpCall(
            request = HttpRequest { method = HttpMethod.GET },
            response = HttpResponse(HttpStatusCode(200, "OK"), body = responseBody),
            coroutineContext = Dispatchers.Unconfined + callJob,
        )
        val cleanupScope = CoroutineScope(coroutineContext + callerJob)

        val cleanup = cleanupScope.launch { completeHttpCall(call) }

        cancellationRequested.await()
        callerJob.cancel()
        (withTimeoutOrNull(50) { cleanup.join() } == null).shouldBeTrue()

        allowReadToFinish.complete(Unit)
        cleanup.join()
        activeChild.isCompleted.shouldBeTrue()
        callJob.isCompleted.shouldBeTrue()
    }

    @Test
    fun `get sends token ttl caches token and attaches it to metadata request`() = runSuspendIO {
        val server = ImdsWireServer { request ->
            if (request.method == "PUT") {
                WireResponse(body = "token-123")
            } else {
                WireResponse(body = "i-1234567890abcdef0")
            }
        }
        val runtime = server.runtime()
        try {
            val template = runtime.operations as ImdsKtorTemplate

            template.instanceId() shouldBeEqualTo "i-1234567890abcdef0"
            template.instanceId() shouldBeEqualTo "i-1234567890abcdef0"

            val tokenRequests = server.requests.filter { it.method == "PUT" }
            val metadataRequests = server.requests.filter { it.method == "GET" }
            tokenRequests.size shouldBeEqualTo 1
            tokenRequests.single().ttl shouldBeEqualTo "21600"
            metadataRequests.size shouldBeEqualTo 2
            metadataRequests.map { it.token }.distinct() shouldBeEqualTo listOf("token-123")
        } finally {
            runtime.stop()
            server.close()
        }
    }

    @Test
    fun `expired token is fetched again after configured TTL`() = runSuspendIO {
        val tokenNumber = AtomicInteger()
        val nowNanos = AtomicLong(0L)
        val server = ImdsWireServer { request ->
            if (request.method == "PUT") {
                WireResponse(body = "token-${tokenNumber.incrementAndGet()}")
            } else {
                WireResponse(body = "metadata")
            }
        }
        val engine = ImdsHttpClientFactory.createEngine(1.seconds())
        val adapter = ImdsSmithyAdapter(
            client = SdkHttpClient(engine),
            endpoint = server.endpointUri,
            tokenTtl = 1.seconds(),
            retries = 0,
            nowNanos = nowNanos::get,
        )
        val template = ImdsKtorTemplate(adapter, 2.seconds())

        try {
            template.get("latest/meta-data/status") shouldBeEqualTo "metadata"
            template.get("latest/meta-data/status") shouldBeEqualTo "metadata"
            server.requests.count { it.method == "PUT" } shouldBeEqualTo 1

            nowNanos.set(1.seconds().inNanos() + 1)
            template.get("latest/meta-data/status") shouldBeEqualTo "metadata"

            server.requests.count { it.method == "PUT" } shouldBeEqualTo 2
            server.requests.filter { it.method == "GET" }.map { it.token } shouldBeEqualTo
                listOf("token-1", "token-1", "token-2")
        } finally {
            engine.close()
            server.close()
        }
    }

    @Test
    fun `fractional token TTL cache uses the integer seconds sent to IMDS`() = runSuspendIO {
        val tokenNumber = AtomicInteger()
        val nowNanos = AtomicLong(0L)
        val server = ImdsWireServer { request ->
            if (request.method == "PUT") {
                WireResponse(body = "token-${tokenNumber.incrementAndGet()}")
            } else {
                WireResponse(body = "metadata")
            }
        }
        val engine = ImdsHttpClientFactory.createEngine(1.seconds())
        val adapter = ImdsSmithyAdapter(
            client = SdkHttpClient(engine),
            endpoint = server.endpointUri,
            tokenTtl = 1500.millis(),
            retries = 0,
            nowNanos = nowNanos::get,
        )
        val template = ImdsKtorTemplate(adapter, 2.seconds())

        try {
            template.get("latest/meta-data/status") shouldBeEqualTo "metadata"
            server.requests.single { it.method == "PUT" }.ttl shouldBeEqualTo "1"

            nowNanos.set(1100.millis().inNanos())
            template.get("latest/meta-data/status") shouldBeEqualTo "metadata"

            server.requests.count { it.method == "PUT" } shouldBeEqualTo 2
            server.requests.filter { it.method == "PUT" }.map { it.ttl } shouldBeEqualTo listOf("1", "1")
        } finally {
            engine.close()
            server.close()
        }
    }

    @Test
    fun `token cache TTL starts after the response body is received`() = runSuspendIO {
        val tokenNumber = AtomicInteger()
        val nowNanos = AtomicLong(0L)
        val server = ImdsWireServer { request ->
            if (request.method == "PUT") {
                nowNanos.set(900.millis().inNanos())
                WireResponse(body = "token-${tokenNumber.incrementAndGet()}")
            } else {
                WireResponse(body = "metadata")
            }
        }
        val engine = ImdsHttpClientFactory.createEngine(1.seconds())
        val adapter = ImdsSmithyAdapter(
            client = SdkHttpClient(engine),
            endpoint = server.endpointUri,
            tokenTtl = 1.seconds(),
            retries = 0,
            nowNanos = nowNanos::get,
        )
        val template = ImdsKtorTemplate(adapter, 2.seconds())

        try {
            template.get("latest/meta-data/status") shouldBeEqualTo "metadata"
            nowNanos.set(1100.millis().inNanos())
            template.get("latest/meta-data/status") shouldBeEqualTo "metadata"
            server.requests.count { it.method == "PUT" } shouldBeEqualTo 1

            nowNanos.set(1900.millis().inNanos() + 1)
            template.get("latest/meta-data/status") shouldBeEqualTo "metadata"
            server.requests.count { it.method == "PUT" } shouldBeEqualTo 2
        } finally {
            engine.close()
            server.close()
        }
    }

    @Test
    fun `profile endpoint wins over explicit mode and shadowed invalid mode`() = runSuspendIO {
        val server = ImdsWireServer { request ->
            if (request.method == "PUT") WireResponse(body = "token") else WireResponse(body = "profile-endpoint")
        }
        val configFile = Files.createTempFile("imds-profile", ".conf")
        Files.writeString(
            configFile,
            """
            [default]
            ec2_metadata_service_endpoint = ${server.endpointUri}
            ec2_metadata_service_endpoint_mode = invalid
            """.trimIndent(),
        )
        val runtime = ImdsKtorPluginConfig().apply {
            endpointMode = EndpointMode.IPv6
            allowInsecureLocalHttpEndpoint = true
            profileResolver = ImdsProfileResolver(
                systemProperties = mapOf("aws.configFile" to configFile.toString()),
                environment = emptyMap(),
                userHome = Path.of("."),
            )
        }.toRuntime()!!

        try {
            (runtime.operations as ImdsKtorTemplate).get("latest/meta-data/status") shouldBeEqualTo "profile-endpoint"
            server.requests.count { it.method == "GET" } shouldBeEqualTo 1
        } finally {
            runtime.stop()
            server.close()
            Files.deleteIfExists(configFile)
        }
    }

    @Test
    fun `explicit plugin endpoint shadows invalid shared endpoint and mode`() = runSuspendIO {
        val server = ImdsWireServer { request ->
            if (request.method == "PUT") WireResponse(body = "token") else WireResponse(body = "plugin-endpoint")
        }
        val configFile = Files.createTempFile("imds-invalid-profile", ".conf")
        Files.writeString(
            configFile,
            """
            [default]
            ec2_metadata_service_endpoint = not a URI
            ec2_metadata_service_endpoint_mode = invalid
            """.trimIndent(),
        )
        val runtime = ImdsKtorPluginConfig().apply {
            endpoint = server.endpointUri
            endpointMode = null
            allowInsecureLocalHttpEndpoint = true
            profileResolver = ImdsProfileResolver(
                systemProperties = mapOf("aws.configFile" to configFile.toString()),
                environment = emptyMap(),
                userHome = Path.of("."),
            )
        }.toRuntime()!!

        try {
            (runtime.operations as ImdsKtorTemplate).get("latest/meta-data/status") shouldBeEqualTo "plugin-endpoint"
            server.requests.count { it.method == "GET" } shouldBeEqualTo 1
        } finally {
            runtime.stop()
            server.close()
            Files.deleteIfExists(configFile)
        }
    }

    @Test
    fun `get normalizes metadata paths and rejects blank paths before wire access`() = runSuspendIO {
        val server = ImdsWireServer { request ->
            if (request.method == "PUT") WireResponse(body = "token") else WireResponse(body = "value")
        }
        val runtime = server.runtime()
        try {
            val template = runtime.operations as ImdsKtorTemplate
            template.get("latest/meta-data/value") shouldBeEqualTo "value"
            server.requests.single { it.method == "GET" }.path shouldBeEqualTo "/latest/meta-data/value"

            val requestCount = server.requests.size
            assertFailsWith<IllegalArgumentException> { template.get(" ") }
            assertFailsWith<IllegalArgumentException> { template.get("https://169.254.169.254/latest/meta-data") }
            server.requests.size shouldBeEqualTo requestCount
        } finally {
            runtime.stop()
            server.close()
        }
    }

    @Test
    fun `getList splits non empty metadata lines`() = runSuspendIO {
        val server = ImdsWireServer { request ->
            if (request.method == "PUT") WireResponse(body = "token") else WireResponse(body = "app-role\nbatch-role\n")
        }
        val runtime = server.runtime()
        try {
            (runtime.operations as ImdsKtorTemplate).iamRoleNames() shouldBeEqualTo listOf("app-role", "batch-role")
        } finally {
            runtime.stop()
            server.close()
        }
    }

    @Test
    fun `401 does not retry or invalidate the cached token`() = runSuspendIO {
        var metadataAttempt = 0
        val server = ImdsWireServer { request ->
            if (request.method == "PUT") {
                WireResponse(body = "cached-token")
            } else if (++metadataAttempt == 1) {
                WireResponse(status = 401, body = "unauthorized")
            } else {
                WireResponse(body = "metadata")
            }
        }
        val runtime = server.runtime(retries = 4)
        try {
            val template = runtime.operations as ImdsKtorTemplate
            val error = assertFailsWith<IllegalStateException> { template.get("latest/meta-data/instance-id") }
            error.message shouldContain "401"

            template.get("latest/meta-data/instance-id") shouldBeEqualTo "metadata"

            server.requests.count { it.method == "PUT" } shouldBeEqualTo 1
            server.requests.count { it.method == "GET" } shouldBeEqualTo 2
            server.requests.filter { it.method == "GET" }.map { it.token }.distinct() shouldBeEqualTo
                listOf("cached-token")
        } finally {
            runtime.stop()
            server.close()
        }
    }

    @Test
    fun `redirect is not followed or retried`() = runSuspendIO {
        val server = ImdsWireServer { request ->
            if (request.method == "PUT") {
                WireResponse(body = "token")
            } else {
                WireResponse(status = 302, headers = mapOf("Location" to "/redirect-target"), body = "redirect")
            }
        }
        val runtime = server.runtime(retries = 4)
        try {
            val template = runtime.operations as ImdsKtorTemplate
            assertFailsWith<IllegalStateException> { template.get("latest/meta-data/instance-id") }

            server.requests.count { it.method == "GET" } shouldBeEqualTo 1
            server.requests.any { it.path == "/redirect-target" }.shouldBeFalse()
        } finally {
            runtime.stop()
            server.close()
        }
    }

    @Test
    fun `retry count means additional metadata attempts for server errors`() = runSuspendIO {
        var metadataAttempt = 0
        val server = ImdsWireServer { request ->
            if (request.method == "PUT") {
                WireResponse(body = "token")
            } else if (++metadataAttempt < 3) {
                WireResponse(status = 503, body = "unavailable")
            } else {
                WireResponse(body = "ready")
            }
        }
        val runtime = server.runtime(retries = 2)
        try {
            (runtime.operations as ImdsKtorTemplate).get("latest/meta-data/status") shouldBeEqualTo "ready"
            server.requests.count { it.method == "GET" } shouldBeEqualTo 3
            server.requests.count { it.method == "PUT" } shouldBeEqualTo 1
        } finally {
            runtime.stop()
            server.close()
        }
    }

    @Test
    fun `response body IO failure is mapped to retryable transport error`() = runSuspendIO {
        var metadataAttempt = 0
        val server = ImdsWireServer { request ->
            if (request.method == "PUT") {
                WireResponse(body = "token")
            } else if (++metadataAttempt == 1) {
                WireResponse(body = "partial", declaredContentLength = 64)
            } else {
                WireResponse(body = "ready")
            }
        }
        val runtime = server.runtime(retries = 1)
        try {
            (runtime.operations as ImdsKtorTemplate).get("latest/meta-data/status") shouldBeEqualTo "ready"
            server.requests.count { it.method == "PUT" } shouldBeEqualTo 1
            server.requests.count { it.method == "GET" } shouldBeEqualTo 2
        } finally {
            runtime.stop()
            server.close()
        }
    }

    @Test
    fun `response body IO failure does not retry terminal HTTP statuses`() = runSuspendIO {
        listOf(302, 401).forEach { status ->
            val engine = TerminalStatusHttpEngine(status)
            val adapter = ImdsSmithyAdapter(
                client = SdkHttpClient(engine),
                endpoint = URI("http://127.0.0.1"),
                tokenTtl = 6.hours(),
                retries = 3,
            )

            try {
                val error = assertFailsWith<IllegalStateException> {
                    ImdsKtorTemplate(adapter, 2.seconds()).get("latest/meta-data/status")
                }

                error.message shouldContain status.toString()
                engine.requests.count { it == HttpMethod.PUT } shouldBeEqualTo 1
                engine.requests.count { it == HttpMethod.GET } shouldBeEqualTo 1
                engine.bodyReads.get() shouldBeEqualTo 0
                engine.bodyCloses.get() shouldBeEqualTo 1
            } finally {
                engine.close()
            }
        }
    }

    @Test
    fun `zero retries permits exactly one failed metadata attempt`() = runSuspendIO {
        val server = ImdsWireServer { request ->
            if (request.method == "PUT") WireResponse(body = "token") else WireResponse(status = 503)
        }
        val runtime = server.runtime(retries = 0)
        try {
            assertFailsWith<IllegalStateException> {
                (runtime.operations as ImdsKtorTemplate).get("latest/meta-data/status")
            }
            server.requests.count { it.method == "GET" } shouldBeEqualTo 1
        } finally {
            runtime.stop()
            server.close()
        }
    }

    @Test
    fun `request timeout covers token acquisition and metadata exchange`() = runSuspendIO {
        val server = ImdsWireServer { request ->
            if (request.method == "PUT") {
                WireResponse(body = "token", delayMillis = 250)
            } else {
                WireResponse(body = "data", delayMillis = 250)
            }
        }
        val runtime = server.runtime(requestTimeout = 400.millis())
        try {
            val error = assertFailsWith<TimeoutCancellationException> {
                (runtime.operations as ImdsKtorTemplate).get("latest/meta-data/status")
            }
            error.message shouldContain "Timed out"
            server.requests.count { it.method == "PUT" } shouldBeEqualTo 1
            server.requests.count { it.method == "GET" } shouldBeEqualTo 1
        } finally {
            runtime.stop()
            server.close()
        }
    }

    @Test
    fun `cancellation is propagated without retry`() = runSuspendIO {
        val metadataStarted = CountDownLatch(1)
        val server = ImdsWireServer { request ->
            if (request.method == "PUT") {
                WireResponse(body = "token")
            } else {
                metadataStarted.countDown()
                WireResponse(body = "late", delayMillis = 500)
            }
        }
        val runtime = server.runtime(retries = 5, requestTimeout = 5.seconds())
        try {
            val template = runtime.operations as ImdsKtorTemplate
            val call = async(Dispatchers.IO) { template.get("latest/meta-data/status") }
            metadataStarted.await(2.seconds).shouldBeTrue()
            call.cancelAndJoin()
            call.isCancelled.shouldBeTrue()
            delay(50.milliseconds)
            server.requests.count { it.method == "GET" } shouldBeEqualTo 1
        } finally {
            runtime.stop()
            server.close()
        }
    }

    @Test
    fun `disallowed loopback endpoint is rejected before token request`() = runSuspendIO {
        val server = ImdsWireServer { WireResponse(body = "unexpected") }
        val error = assertFailsWith<IllegalArgumentException> {
            server.runtime(allowInsecureLocalHttpEndpoint = false)
        }

        error.message shouldContain "loopback"
        server.requests.size shouldBeEqualTo 0
        server.close()
    }

    private fun ImdsWireServer.runtime(
        retries: Int = 0,
        requestTimeout: Duration = 2.seconds(),
        tokenTtl: Duration = 6.hours(),
        allowInsecureLocalHttpEndpoint: Boolean = true,
    ): ImdsKtorRuntime = ImdsKtorPluginConfig().apply {
        endpoint = endpointUri
        this.retries = retries
        this.requestTimeout = requestTimeout
        this.tokenTtl = tokenTtl
        this.allowInsecureLocalHttpEndpoint = allowInsecureLocalHttpEndpoint
        profileResolver = ImdsProfileResolver(
            systemProperties = emptyMap(),
            environment = emptyMap(),
            userHome = Path.of("."),
        )
    }.toRuntime()!!
}

private class TerminalStatusHttpEngine(
    private val status: Int,
) : CloseableHttpClientEngine {
    private val engineJob = SupervisorJob()
    override val coroutineContext: CoroutineContext = Dispatchers.Unconfined + engineJob
    override val config = HttpClientEngineConfig.Default
    val requests = CopyOnWriteArrayList<HttpMethod>()
    val bodyReads = AtomicInteger()
    val bodyCloses = AtomicInteger()

    override suspend fun roundTrip(
        context: aws.smithy.kotlin.runtime.operation.ExecutionContext,
        request: aws.smithy.kotlin.runtime.http.request.HttpRequest,
    ): HttpCall {
        requests += request.method
        val response = if (request.method == HttpMethod.PUT) {
            HttpResponse(HttpStatusCode.OK, body = HttpBody.fromBytes("token".encodeToByteArray()))
        } else {
            HttpResponse(HttpStatusCode(status, "test"), body = object : HttpBody.SourceContent() {
                override val contentLength: Long = 64

                override fun readFrom(): SdkSource = object : SdkSource {
                    override fun read(sink: SdkBuffer, limit: Long): Long {
                        bodyReads.incrementAndGet()
                        throw IOException("synthetic terminal response body failure")
                    }

                    override fun close() {
                        bodyCloses.incrementAndGet()
                    }
                }
            })
        }
        val callContext = Dispatchers.Unconfined + SupervisorJob(engineJob)
        val responseBody = response.body as? HttpBody.SourceContent
        callContext[Job]?.invokeOnCompletion { cause ->
            if (cause != null) responseBody?.readFrom()?.close()
        }
        return HttpCall(request, response, coroutineContext = callContext)
    }

    override fun close() {
        engineJob.cancel()
    }
}

private data class WireRequest(
    val method: String,
    val path: String,
    val ttl: String?,
    val token: String?,
)

private data class WireResponse(
    val status: Int = 200,
    val headers: Map<String, String> = emptyMap(),
    val body: String = "",
    val delayMillis: Long = 0,
    val declaredContentLength: Long? = null,
)

private class ImdsWireServer(
    private val respond: (WireRequest) -> WireResponse,
) : AutoCloseable {

    val requests = CopyOnWriteArrayList<WireRequest>()
    private val executor = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "imds-test-server").apply { isDaemon = true }
    }
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        this.executor = this@ImdsWireServer.executor
        createContext("/") { exchange -> handle(exchange) }
        start()
    }

    val endpointUri: URI = URI("http", null, "127.0.0.1", server.address.port, null, null, null)

    private fun handle(exchange: HttpExchange) {
        val request = WireRequest(
            method = exchange.requestMethod,
            path = exchange.requestURI.path,
            ttl = exchange.requestHeaders.getFirst("x-aws-ec2-metadata-token-ttl-seconds"),
            token = exchange.requestHeaders.getFirst("x-aws-ec2-metadata-token"),
        )
        requests += request

        try {
            val response = respond(request)
            if (response.delayMillis > 0) Thread.sleep(response.delayMillis)
            response.headers.forEach { (name, value) -> exchange.responseHeaders.add(name, value) }
            val bytes = response.body.toByteArray()
            exchange.sendResponseHeaders(response.status, response.declaredContentLength ?: bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (_: Exception) {
            runCatching { exchange.close() }
        }
    }

    override fun close() {
        server.stop(0)
        executor.shutdownNow()
    }
}
