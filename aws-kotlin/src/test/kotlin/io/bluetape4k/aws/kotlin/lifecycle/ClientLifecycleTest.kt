package io.bluetape4k.aws.kotlin.lifecycle

import io.bluetape4k.aws.kotlin.http.crtHttpEngineOf
import io.bluetape4k.aws.kotlin.s3.withS3Client
import io.bluetape4k.aws.kotlin.s3.s3ClientOf
import io.bluetape4k.aws.kotlin.ses.sesClientOf
import io.bluetape4k.aws.kotlin.ses.withSesClient
import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeSameInstanceAs
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.support.closeSafe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import aws.sdk.kotlin.services.s3.S3Client
import aws.sdk.kotlin.services.ses.SesClient
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.seconds

/**
 * Regression tests for `xxxClientOf()` and `withXxxClient()` lifecycle ownership.
 *
 * ## Verifies
 * - Omitting `httpClient` lets the SDK manage engine ownership.
 * - `withXxxClient { }` closes the SDK-owned client at block exit.
 * - Explicit external engines remain caller-owned after `client.close()`.
 *
 * The tests exercise lifecycle behavior without LocalStack or network calls.
 */
class ClientLifecycleTest {

    companion object: KLogging()

    @Test
    fun `s3ClientOf without httpClient closes sdk-managed client within timeout`() = runTest(timeout = 10.seconds) {
        val client = s3ClientOf(region = "us-east-1")
        client.shouldNotBeNull()
        log.debug { "S3Client created with SDK-managed HTTP engine: $client" }
        client.close()
        log.debug { "S3Client close completed with SDK-managed HTTP engine" }
    }

    @Test
    fun `sesClientOf without httpClient closes sdk-managed client within timeout`() = runTest(timeout = 10.seconds) {
        val client = sesClientOf(region = "us-east-1")
        client.shouldNotBeNull()
        client.close()
    }

    @Test
    fun `withS3Client closes sdk-managed client after block exit`() = runTest(timeout = 10.seconds) {
        withS3Client(region = "us-east-1") { client ->
            client.shouldNotBeNull()
            log.debug { "S3Client inside withS3Client block: $client" }
        }
        log.debug { "withS3Client block exited with SDK-managed client closed" }
    }

    @Test
    fun `withSesClient closes sdk-managed client after block exit`() = runTest(timeout = 10.seconds) {
        withSesClient(region = "us-east-1") { client ->
            client.shouldNotBeNull()
        }
    }

    @Test
    fun `withS3Client closes exactly once after normal return`() = runTest {
        val client = mockk<S3Client>(relaxed = true)
        every { client.close() } returns Unit

        withS3Client(clientFactory = { client }) { it shouldBeSameInstanceAs client }

        verify(exactly = 1) { client.close() }
    }

    @Test
    fun `withS3Client closes exactly once after block failure`() = runTest {
        val client = mockk<S3Client>(relaxed = true)
        every { client.close() } returns Unit
        val expected = IllegalStateException("boom")

        val actual = assertFailsWith<IllegalStateException> {
            withS3Client(clientFactory = { client }) { throw expected }
        }

        actual shouldBeSameInstanceAs expected
        verify(exactly = 1) { client.close() }
    }

    @Test
    fun `withS3Client closes exactly once after cancellation`() = runTest {
        val client = mockk<S3Client>(relaxed = true)
        every { client.close() } returns Unit
        val job = launch {
            withS3Client(clientFactory = { client }) { awaitCancellation() }
        }
        runCurrent()

        job.cancelAndJoin()

        verify(exactly = 1) { client.close() }
    }

    @Test
    fun `withSesClient closes exactly once after normal return`() = runTest {
        val client = mockk<SesClient>(relaxed = true)
        every { client.close() } returns Unit

        withSesClient(clientFactory = { client }) { it shouldBeSameInstanceAs client }

        verify(exactly = 1) { client.close() }
    }

    @Test
    fun `withSesClient closes exactly once after block failure`() = runTest {
        val client = mockk<SesClient>(relaxed = true)
        every { client.close() } returns Unit
        val expected = IllegalStateException("boom")

        val actual = assertFailsWith<IllegalStateException> {
            withSesClient(clientFactory = { client }) { throw expected }
        }

        actual shouldBeSameInstanceAs expected
        verify(exactly = 1) { client.close() }
    }

    @Test
    fun `withSesClient closes exactly once after cancellation`() = runTest {
        val client = mockk<SesClient>(relaxed = true)
        every { client.close() } returns Unit
        val job = launch {
            withSesClient(clientFactory = { client }) { awaitCancellation() }
        }
        runCurrent()

        job.cancelAndJoin()

        verify(exactly = 1) { client.close() }
    }

    @Test
    fun `s3ClientOf with external httpClient leaves engine caller-owned after client close`() =
        runTest(timeout = 10.seconds) {
            val sharedEngine = crtHttpEngineOf()
            try {
                val client1 = s3ClientOf(region = "us-east-1", httpClient = sharedEngine)
                client1.close()

                val client2 = s3ClientOf(region = "us-east-1", httpClient = sharedEngine)
                client2.shouldNotBeNull()
                log.debug { "Second client created with the caller-owned engine: $client2" }
                client2.close()
            } finally {
                sharedEngine.closeSafe()
                log.debug { "Caller-owned engine closed" }
            }
        }

    @Test
    fun `s3ClientOf use block closes sdk-managed client`() = runTest(timeout = 10.seconds) {
        s3ClientOf(region = "us-east-1").use { client ->
            client.shouldNotBeNull()
            log.debug { "S3Client inside use block: $client" }
        }
        log.debug { "use block exited with SDK-managed client closed" }
    }
}
