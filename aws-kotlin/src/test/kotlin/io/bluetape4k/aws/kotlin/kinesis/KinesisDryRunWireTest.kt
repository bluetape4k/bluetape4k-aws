package io.bluetape4k.aws.kotlin.kinesis

import aws.sdk.kotlin.runtime.auth.credentials.StaticCredentialsProvider
import aws.sdk.kotlin.services.kinesis.KinesisClient
import aws.sdk.kotlin.services.kinesis.model.DryRunOperationException
import aws.sdk.kotlin.services.kinesis.model.PutRecordsRequestEntry
import aws.smithy.kotlin.runtime.net.url.Url
import com.sun.net.httpserver.HttpServer
import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeLessThan
import io.bluetape4k.assertions.shouldNotContain
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.support.closeSafe
import io.bluetape4k.support.requireEquals
import io.bluetape4k.support.requireNull
import io.bluetape4k.support.toUtf8Bytes
import io.bluetape4k.support.toUtf8String
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors

class KinesisDryRunWireTest {

    companion object: KLoggingChannel() {
        private const val LOOPBACK = "127.0.0.1"
        private const val TEST_ACCESS_KEY = "dry-run-wire-access"
        private const val TEST_SECRET_KEY = "dry-run-wire-secret"
        private const val MAX_CAPTURE_BYTES = 16 * 1024
        private const val ERROR_RESPONSE = "{\"__type\":\"DryRunOperationException\",\"message\":\"dry run accepted\"}"

        private val DRY_RUN_TRUE = Regex("\\\"DryRun\\\"\\s*:\\s*true")
        private val DRY_RUN_FALSE = Regex("\\\"DryRun\\\"\\s*:\\s*false")
        private val DRY_RUN_MEMBER = Regex("\\\"DryRun\\\"\\s*:")
    }

    @Test
    fun `PutRecord wire는 false true null을 구분한다`() = runSuspendIO {
        verifyDryRunWire("Kinesis_20131202.PutRecord") { client, dryRun ->
            client.putRecord(
                streamName = "wire-stream",
                partitionKey = "partition",
                data = "payload".toUtf8Bytes(),
                dryRun = dryRun ?: true,
            ) {
                if (dryRun == null) this.dryRun = null
            }
        }
    }

    @Test
    fun `PutRecords wire는 top-level false true null을 구분한다`() = runSuspendIO {
        val entries = listOf(
            PutRecordsRequestEntry {
                partitionKey = "partition"
                data = "payload".toUtf8Bytes()
            },
        )
        verifyDryRunWire("Kinesis_20131202.PutRecords") { client, dryRun ->
            client.putRecords(
                streamName = "wire-stream",
                entries = entries,
                dryRun = dryRun ?: true,
            ) {
                if (dryRun == null) this.dryRun = null
            }
        }
    }

    @Test
    fun `GetShardIterator wire는 false true null을 구분한다`() = runSuspendIO {
        verifyDryRunWire("Kinesis_20131202.GetShardIterator") { client, dryRun ->
            client.getShardIterator(
                streamName = "wire-stream",
                shardId = "shardId-000000000000",
                dryRun = dryRun ?: true,
            ) {
                if (dryRun == null) this.dryRun = null
            }
        }
    }

    @Test
    fun `GetRecords wire는 false true null을 구분한다`() = runSuspendIO {
        verifyDryRunWire("Kinesis_20131202.GetRecords") { client, dryRun ->
            client.getRecords(
                shardIterator = "iterator",
                dryRun = dryRun ?: true,
            ) {
                if (dryRun == null) this.dryRun = null
            }
        }
    }

    @Test
    fun `wire fixture는 loopback과 static fake credentials만 허용한다`() {
        val allowed = Url.parse("http://127.0.0.1:4566")
        requireSafeWireTarget(allowed, TEST_ACCESS_KEY, TEST_SECRET_KEY)

        listOf(
            Triple(Url.parse("https://kinesis.us-east-1.amazonaws.com"), TEST_ACCESS_KEY, TEST_SECRET_KEY),
            Triple(Url.parse("http://localhost:4566"), TEST_ACCESS_KEY, TEST_SECRET_KEY),
            Triple(Url.parse("http://user@127.0.0.1:4566"), TEST_ACCESS_KEY, TEST_SECRET_KEY),
            Triple(allowed, "unexpected-access", TEST_SECRET_KEY),
            Triple(allowed, TEST_ACCESS_KEY, "unexpected-secret"),
        ).forEach { (endpoint, accessKey, secretKey) ->
            assertFailsWith<IllegalArgumentException> {
                requireSafeWireTarget(endpoint, accessKey, secretKey)
            }
        }
    }

    private suspend fun verifyDryRunWire(
        expectedTarget: String,
        operation: suspend (KinesisClient, Boolean?) -> Unit,
    ) {
        val captures = ConcurrentLinkedQueue<CapturedRequest>()
        val executor = Executors.newSingleThreadExecutor()
        val server = HttpServer.create(InetSocketAddress(InetAddress.getByName(LOOPBACK), 0), 0)
        server.executor = executor
        server.createContext("/") { exchange ->
            val target = exchange.requestHeaders.getFirst("X-Amz-Target").orEmpty()
            val body = exchange.requestBody.use { it.readBytes() }.toUtf8String()
            captures += CapturedRequest(target, body)

            val response = ERROR_RESPONSE.encodeToByteArray()
            exchange.responseHeaders.add("Content-Type", "application/x-amz-json-1.1")
            exchange.responseHeaders.add("x-amzn-errortype", "DryRunOperationException")
            exchange.sendResponseHeaders(400, response.size.toLong())
            exchange.responseBody.use { it.write(response) }
        }
        server.start()

        val endpoint = Url.parse("http://$LOOPBACK:${server.address.port}")
        requireSafeWireTarget(endpoint, TEST_ACCESS_KEY, TEST_SECRET_KEY)
        val credentials = StaticCredentialsProvider {
            accessKeyId = TEST_ACCESS_KEY
            secretAccessKey = TEST_SECRET_KEY
        }
        val client = kinesisClientOf(endpoint, "us-east-1", credentials)
        try {
            listOf(false, true, null).forEach { dryRun ->
                assertFailsWith<DryRunOperationException> {
                    operation(client, dryRun)
                }
            }
        } finally {
            client.closeSafe()
            server.stop(0)
            executor.shutdownNow()
        }

        captures.size shouldBeEqualTo 3
        captures.zip(listOf(false, true, null))
            .forEach { (capture, expectedDryRun) ->
                capture.target shouldBeEqualTo expectedTarget
                assertDryRunShape(capture.body, expectedDryRun)
            }
    }

    private fun assertDryRunShape(body: String, expected: Boolean?) {
        val trueCount = DRY_RUN_TRUE.findAll(body).count()
        val falseCount = DRY_RUN_FALSE.findAll(body).count()
        val memberCount = DRY_RUN_MEMBER.findAll(body).count()

        when (expected) {
            true -> {
                assertEquals(1, trueCount)
                assertEquals(0, falseCount)
                assertEquals(1, memberCount)
            }

            false -> {
                assertEquals(0, trueCount)
                assertEquals(1, falseCount)
                assertEquals(1, memberCount)
            }

            null -> {
                assertEquals(0, trueCount)
                assertEquals(0, falseCount)
                assertEquals(0, memberCount)
            }
        }
        body shouldNotContain TEST_ACCESS_KEY
        body shouldNotContain TEST_SECRET_KEY
        body.length shouldBeLessThan MAX_CAPTURE_BYTES
    }

    private fun requireSafeWireTarget(
        endpoint: Url,
        accessKey: String,
        secretKey: String,
    ) {
        val uri = URI(endpoint.toString())
        uri.scheme.requireEquals("http", "uri.scheme")
        uri.host.requireEquals(LOOPBACK, "uri.host")
        uri.userInfo.requireNull("uri.userInfo")
        accessKey.requireEquals(TEST_ACCESS_KEY, "accessKey")
        secretKey.requireEquals(TEST_SECRET_KEY, "secretKey")
    }

    private data class CapturedRequest(
        val target: String,
        val body: String,
    )
}
