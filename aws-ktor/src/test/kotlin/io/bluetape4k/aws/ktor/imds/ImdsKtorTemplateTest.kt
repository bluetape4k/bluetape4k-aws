package io.bluetape4k.aws.ktor.imds

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.concurrent.completableFutureOf
import io.bluetape4k.javatimes.millis
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.debug
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import software.amazon.awssdk.imds.Ec2MetadataAsyncClient
import software.amazon.awssdk.imds.Ec2MetadataResponse
import java.util.concurrent.CompletableFuture

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ImdsKtorTemplateTest {

    companion object: KLoggingChannel()

    private val client = mockk<Ec2MetadataAsyncClient>()

    @BeforeEach
    fun resetMocks() {
        clearMocks(client)
    }

    @Test
    fun `get returns string metadata`() = runTest {
        every {
            client.get("/latest/meta-data/instance-id")
        } returns completableFutureOf(Ec2MetadataResponse.create("i-1234567890abcdef0"))

        val template = ImdsKtorTemplate(client)

        log.debug { "template=$template" }
        template.instanceId() shouldBeEqualTo "i-1234567890abcdef0"
        verify(exactly = 1) { client.get("/latest/meta-data/instance-id") }
    }

    @Test
    fun `getList returns line separated metadata`() = runTest {
        every {
            client.get("/latest/meta-data/iam/security-credentials/")
        } returns completableFutureOf(Ec2MetadataResponse.create("app-role\nbatch-role"))

        val template = ImdsKtorTemplate(client)

        log.debug { "template=$template" }
        template.iamRoleNames() shouldBeEqualTo listOf("app-role", "batch-role")
    }

    @Test
    fun `get normalizes path with leading slash`() = runTest {
        every {
            client.get("/latest/meta-data/instance-type")
        } returns completableFutureOf(Ec2MetadataResponse.create("m7g.large"))

        val template = ImdsKtorTemplate(client)

        log.debug { "template=$template" }
        template.get("latest/meta-data/instance-type") shouldBeEqualTo "m7g.large"

        verify(exactly = 1) { client.get("/latest/meta-data/instance-type") }
    }

    @Test
    fun `get rejects blank path`() = runTest {
        val template = ImdsKtorTemplate(client)

        val error = assertFailsWith<IllegalArgumentException> {
            template.get(" ")
        }

        log.debug { "error=${error.message}" }
        error.message shouldContain "path"
    }

    @Test
    fun `get is bounded by request timeout`() = runTest {
        val future = CompletableFuture<Ec2MetadataResponse>()
        every { client.get("/latest/meta-data/instance-id") } returns future

        val template = ImdsKtorTemplate(
            client,
            requestTimeout = 10.millis(),
        )

        assertFailsWith<TimeoutCancellationException> {
            template.instanceId()
        }

        future.isCancelled.shouldBeTrue()
    }
}
