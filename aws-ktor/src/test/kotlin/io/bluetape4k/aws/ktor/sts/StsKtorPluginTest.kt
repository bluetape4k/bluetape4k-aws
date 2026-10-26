package io.bluetape4k.aws.ktor.sts

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldBeSameInstanceAs
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.assertions.shouldNotContain
import io.bluetape4k.aws.ktor.AwsKtorCore
import io.bluetape4k.aws.ktor.AwsKtorDefaults
import io.bluetape4k.aws.ktor.AwsKtorStsClientCustomizer
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.logging.KLogging
import io.ktor.http.Url
import io.ktor.server.application.install
import io.ktor.server.testing.testApplication
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import aws.sdk.kotlin.services.sts.StsClient
import aws.smithy.kotlin.runtime.auth.awscredentials.CredentialsProvider
import aws.smithy.kotlin.runtime.net.url.Url as SmithyUrl

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StsKtorPluginTest {

    companion object: KLogging()

    private val client = mockk<StsClient>(relaxed = true)
    private val operations = mockk<StsKtorOperations>(relaxed = true)

    @BeforeEach
    fun resetMocks() {
        clearMocks(client, operations)
    }

    @Test
    fun `plugin stores injected operations`() = testApplication {
        application {
            install(AwsKtorCore) {
                ktorCore()
            }
            install(StsKtorPlugin) {
                stsOperations = operations
            }
        }

        startApplication()

        application.sts() shouldBeSameInstanceAs operations
        application.stsOrNull() shouldBeSameInstanceAs operations
        application.attributes[StsKtorRuntimeKey].operations shouldBeSameInstanceAs operations
    }

    @Test
    fun `plugin does not store operations when disabled`() = testApplication {
        application {
            install(StsKtorPlugin) {
                enabled = false
            }
        }

        startApplication()

        application.stsOrNull().shouldBeNull()
        val error = assertFailsWith<IllegalStateException> {
            application.sts()
        }
        error.message shouldContain "disabled"
    }

    @Test
    fun `injected client remains application owned`() = runSuspendIO {
        val runtime = StsKtorPluginConfig()
            .apply { stsClient = client }
            .toRuntime()
            .shouldNotBeNull()

        runtime.stop()

        verify(exactly = 0) { client.close() }
    }

    @Test
    fun `runtime closes plugin owned client once`() = runSuspendIO {
        val runtime = StsKtorRuntime(
            operations = operations,
            ownedClient = client,
        )

        runtime.stop()
        runtime.stop()

        verify(exactly = 1) { client.close() }
    }

    @Test
    fun `config diagnostics redact endpoint credentials and injected dependencies`() {
        every { client.toString() } returns "sts-client-secret"
        every { operations.toString() } returns "sts-operations-secret"
        val provider = mockk<CredentialsProvider>(relaxed = true)
        every { provider.toString() } returns "credentials-provider-secret"

        val rendered = StsKtorPluginConfig().apply {
            stsClient = client
            stsOperations = operations
            endpointOverride = SmithyUrl.parse(
                "https://endpoint-user:endpoint-password@example.com/private?token=endpoint-secret",
            )
            credentialsProvider = provider
        }.toString()

        listOf(
            "sts-client-secret",
            "sts-operations-secret",
            "credentials-provider-secret",
            "secret-key",
            "endpoint-user",
            "endpoint-password",
            "private",
            "endpoint-secret",
        ).forEach { rendered shouldNotContain it }
        rendered shouldContain "endpointConfigured=true"
        rendered shouldContain "credentialsProviderConfigured=true"
    }

    @Test
    fun `service customizer runs after shared customizer`() = runSuspendIO {
        val order = mutableListOf<String>()
        val runtime = StsKtorPluginConfig()
            .apply {
                stsClient { order += "service" }
            }
            .toRuntime(
                AwsKtorDefaults(
                    region = "ap-northeast-2",
                    endpointOverride = Url("http://localhost:4566"),
                    stsClientCustomizers = listOf(
                        AwsKtorStsClientCustomizer { order += "shared" }
                    ),
                )
            )
            .shouldNotBeNull()

        order shouldBeEqualTo listOf("shared", "service")

        runtime.stop()
    }
}
