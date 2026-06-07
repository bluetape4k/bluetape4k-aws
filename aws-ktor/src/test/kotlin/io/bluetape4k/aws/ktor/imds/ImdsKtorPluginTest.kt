package io.bluetape4k.aws.ktor.imds

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldBeSameInstanceAs
import io.bluetape4k.assertions.shouldContain
import io.mockk.clearMocks
import io.mockk.mockk
import io.mockk.verify
import io.ktor.server.application.install
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import software.amazon.awssdk.imds.Ec2MetadataAsyncClient
import software.amazon.awssdk.imds.EndpointMode
import java.time.Duration

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ImdsKtorPluginTest {

    private val client = mockk<Ec2MetadataAsyncClient>(relaxed = true)
    private val operations = mockk<ImdsKtorOperations>(relaxed = true)

    @BeforeEach
    fun resetMocks() {
        clearMocks(client, operations)
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
    fun `plugin does not store operations when disabled`() = testApplication {
        application {
            install(ImdsKtorPlugin) {
                enabled = false
            }
        }

        startApplication()

        application.imdsOrNull().shouldBeNull()
        val error = assertFailsWith<IllegalStateException> {
            application.imds()
        }
        error.message shouldContain "disabled"
    }

    @Test
    fun `injected client creates operations without startup calls and remains application owned`() {
        val runtime = ImdsKtorPluginConfig().apply {
            ec2MetadataAsyncClient = client
            requestTimeout = Duration.ofMillis(750)
        }.toRuntime()
        requireNotNull(runtime)

        runtime.operations.javaClass shouldBeEqualTo ImdsKtorTemplate::class.java
        runtime.stop()

        verify(exactly = 0) { client.get(any()) }
        verify(exactly = 0) { client.close() }
    }

    @Test
    fun `runtime closes plugin owned client once`() {
        val runtime = ImdsKtorRuntime(
            operations = operations,
            ownedClient = client,
        )

        runtime.stop()
        runtime.stop()

        verify(exactly = 1) { client.close() }
    }

    @Test
    fun `config validation rejects non positive token ttl`() {
        val error = assertFailsWith<IllegalArgumentException> {
            ImdsKtorPluginConfig().apply {
                tokenTtl = Duration.ZERO
            }.toRuntime()
        }

        error.message shouldContain "tokenTtl"
    }

    @Test
    fun `config validation rejects non positive request timeout`() {
        val error = assertFailsWith<IllegalArgumentException> {
            ImdsKtorPluginConfig().apply {
                requestTimeout = Duration.ZERO
            }.toRuntime()
        }

        error.message shouldContain "requestTimeout"
    }

    @Test
    fun `config validation rejects negative retries`() {
        val error = assertFailsWith<IllegalArgumentException> {
            ImdsKtorPluginConfig().apply {
                retries = -1
            }.toRuntime()
        }

        error.message shouldContain "retries"
    }

    @Test
    fun `client customizer runs for plugin created client`() {
        val order = mutableListOf<String>()
        val runtime = ImdsKtorPluginConfig().apply {
            httpClient = mockk(relaxed = true)
            endpointMode = EndpointMode.IPV6
            client { order += "custom" }
        }.toRuntime()

        order shouldBeEqualTo listOf("custom")

        runtime?.stop()
    }
}
