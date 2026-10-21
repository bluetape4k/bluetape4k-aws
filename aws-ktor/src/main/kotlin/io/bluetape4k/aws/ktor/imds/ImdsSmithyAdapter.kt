package io.bluetape4k.aws.ktor.imds

import aws.smithy.kotlin.runtime.InternalApi
import aws.smithy.kotlin.runtime.http.HttpBody
import aws.smithy.kotlin.runtime.http.HttpException
import aws.smithy.kotlin.runtime.http.HttpMethod
import aws.smithy.kotlin.runtime.http.SdkHttpClient
import aws.smithy.kotlin.runtime.http.complete
import aws.smithy.kotlin.runtime.http.readAll
import aws.smithy.kotlin.runtime.http.engine.CloseableHttpClientEngine
import aws.smithy.kotlin.runtime.http.engine.okhttp.OkHttpEngine
import aws.smithy.kotlin.runtime.http.request.HttpRequest
import aws.smithy.kotlin.runtime.http.request.headers
import aws.smithy.kotlin.runtime.http.request.url
import aws.smithy.kotlin.runtime.http.response.HttpResponse
import aws.smithy.kotlin.runtime.net.url.Url
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.net.URI
import java.net.Proxy
import java.time.Duration
import kotlin.random.Random
import okhttp3.OkHttpClient

/** IMDSv2 token and metadata protocol over the public Smithy HTTP client API. */
internal class ImdsSmithyAdapter(
    private val client: SdkHttpClient,
    private val endpoint: URI,
    private val tokenTtl: Duration,
    private val retries: Int,
    private val nowNanos: () -> Long = System::nanoTime,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
    private val randomLong: (Long, Long) -> Long = { from, until -> Random.nextLong(from, until) },
) {

    private val tokenLock = Mutex()
    private var cachedToken: CachedToken? = null

    suspend fun get(path: String): String = retry {
        val token = token()
        val response = exchange(
            request(HttpMethod.GET, path) {
                append(TOKEN_HEADER, token)
            },
        )
        response.requireSuccess().second.decodeToString()
    }

    suspend fun getList(path: String): List<String> =
        get(path).lineSequence().map(String::trim).filter(String::isNotEmpty).toList()

    private suspend fun token(): String = tokenLock.withLock {
        val now = nowNanos()
        cachedToken?.takeIf { now < it.expiresAtNanos }?.let { return@withLock it.value }

        val ttlSeconds = tokenTtl.seconds.coerceIn(1, MAX_TOKEN_TTL_SECONDS)
        val (_, bytes) = exchange(
            request(HttpMethod.PUT, TOKEN_PATH) {
                append(TOKEN_TTL_HEADER, ttlSeconds.toString())
            },
        ).requireSuccess()
        val value = bytes.decodeToString().trim()
        check(value.isNotEmpty()) { "IMDS token response was empty." }

        val tokenReceivedAtNanos = nowNanos()
        val expiresAtNanos = tokenReceivedAtNanos + Duration.ofSeconds(ttlSeconds).toNanos()
        cachedToken = CachedToken(value, expiresAtNanos)
        value
    }

    private suspend fun retry(block: suspend () -> String): String {
        var retriesUsed = 0
        while (true) {
            try {
                return block()
            } catch (cause: Throwable) {
                if (cause is CancellationException || retriesUsed >= retries || !ImdsRetryPolicy.shouldRetry(cause)) {
                    throw cause
                }
                sleep(ImdsRetryPolicy.fullJitterMillis(retriesUsed, randomLong))
                retriesUsed++
            }
        }
    }

    private fun request(
        method: HttpMethod,
        path: String,
        extraHeaders: aws.smithy.kotlin.runtime.http.HeadersBuilder.() -> Unit,
    ): HttpRequest {
        val requestUrl = endpoint.toString().trimEnd('/') + path
        return HttpRequest {
            this.method = method
            url(Url.parse(requestUrl))
            headers {
                append("Accept", "*/*")
                extraHeaders()
            }
            body = HttpBody.Empty
        }
    }

    private suspend fun exchange(request: HttpRequest): Pair<HttpResponse, ByteArray> {
        val call = client.call(request)
        var unconsumedErrorBody = false
        return try {
            val response = call.response
            if (response.status.value !in 200..299) {
                unconsumedErrorBody = true
                return response to ByteArray(0)
            }

            val responseBody = try {
                response.body.readAll()
            } catch (cause: IOException) {
                // Smithy maps IO failures from executeAsync(), but response-body reads happen later.
                // Keep successful-response transport failures in the same retryable error category.
                throw HttpException(cause, retryable = true)
            }
            response to (responseBody ?: ByteArray(0))
        } finally {
            if (unconsumedErrorBody) call.coroutineContext[Job]?.cancel()
            completeHttpCall(call)
        }
    }

    private fun Pair<HttpResponse, ByteArray>.requireSuccess(): Pair<HttpResponse, ByteArray> {
        val status = first.status.value
        if (status !in 200..299) throw ImdsHttpResponseException(status)
        return this
    }

    private data class CachedToken(val value: String, val expiresAtNanos: Long)

    private companion object {
        const val TOKEN_PATH = "/latest/api/token"
        const val TOKEN_TTL_HEADER = "x-aws-ec2-metadata-token-ttl-seconds"
        const val TOKEN_HEADER = "x-aws-ec2-metadata-token"
        const val MAX_TOKEN_TTL_SECONDS = 21_600L
    }
}

/** A status failure kept separate from transport errors for the retry matrix. */
internal class ImdsHttpResponseException(val statusCode: Int) :
    IllegalStateException("IMDS returned HTTP $statusCode.")

/** The fixed retry matrix and full-jitter schedule used by IMDS operations. */
internal object ImdsRetryPolicy {
    private const val INITIAL_BACKOFF_MILLIS = 200L
    private const val MAX_BACKOFF_MILLIS = 20_000L

    fun shouldRetry(cause: Throwable): Boolean = when (cause) {
        is ImdsHttpResponseException -> cause.statusCode in 500..599
        is HttpException -> cause.sdkErrorMetadata.isRetryable
        else -> false
    }

    fun backoffCapMillis(retryIndex: Int): Long {
        require(retryIndex >= 0) { "retryIndex must be non-negative" }
        val multiplier = 1L shl retryIndex.coerceAtMost(20)
        return (INITIAL_BACKOFF_MILLIS * multiplier).coerceAtMost(MAX_BACKOFF_MILLIS)
    }

    fun fullJitterMillis(retryIndex: Int, randomLong: (Long, Long) -> Long): Long {
        val upperBound = backoffCapMillis(retryIndex)
        return randomLong(1L, upperBound + 1L).also {
            check(it in 1L..upperBound) { "Jitter source returned a value outside its bounds." }
        }
    }
}

/** Creates the IMDS-specific engine with service timeouts and an explicit no-proxy selector. */
internal object ImdsHttpClientFactory {
    fun createEngine(metadataServiceTimeout: Duration): CloseableHttpClientEngine {
        require(!metadataServiceTimeout.isZero && !metadataServiceTimeout.isNegative) {
            "metadataServiceTimeout must be greater than zero."
        }
        val client = createClient(metadataServiceTimeout)
        return ImdsOwnedHttpClientEngine(OkHttpEngine(client), client)
    }

    fun createClient(metadataServiceTimeout: Duration): OkHttpClient {
        require(!metadataServiceTimeout.isZero && !metadataServiceTimeout.isNegative) {
            "metadataServiceTimeout must be greater than zero."
        }
        return OkHttpClient.Builder()
            .retryOnConnectionFailure(false)
            .proxy(Proxy.NO_PROXY)
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(metadataServiceTimeout)
            .readTimeout(metadataServiceTimeout)
            .writeTimeout(metadataServiceTimeout)
            .build()
    }
}

/** Closes both Smithy metrics state and the OkHttp client supplied to its engine. */
internal class ImdsOwnedHttpClientEngine(
    private val delegate: CloseableHttpClientEngine,
    val client: OkHttpClient,
) : CloseableHttpClientEngine by delegate {

    override fun close() {
        var failure: Throwable? = null
        try {
            delegate.close()
        } catch (cause: Throwable) {
            failure = cause
        }
        try {
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
        } catch (cause: Throwable) {
            if (failure == null) failure = cause else if (failure !== cause) failure.addSuppressed(cause)
        }
        failure?.let { throw it }
    }
}

@OptIn(InternalApi::class)
internal suspend fun completeHttpCall(call: aws.smithy.kotlin.runtime.http.HttpCall) {
    withContext(NonCancellable) {
        call.complete()
    }
}
