package io.bluetape4k.aws.ktor.ses

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldBeSameInstanceAs
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.aws.ktor.AwsKtorCore
import io.bluetape4k.aws.ktor.AwsKtorDefaults
import io.bluetape4k.aws.ktor.AwsKtorSesV2AsyncClientCustomizer
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.mockk.clearMocks
import io.mockk.mockk
import io.mockk.verify
import io.ktor.http.Url
import io.ktor.server.application.install
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import software.amazon.awssdk.services.sesv2.SesV2AsyncClient

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SesKtorPluginTest {

    private val client = mockk<SesV2AsyncClient>(relaxed = true)
    private val operations = mockk<SesKtorOperations>(relaxed = true)

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
            install(SesKtorPlugin) {
                sesOperations = operations
            }
        }

        startApplication()

        application.ses() shouldBeSameInstanceAs operations
        application.sesOrNull() shouldBeSameInstanceAs operations
        application.attributes[SesKtorRuntimeKey].operations shouldBeSameInstanceAs operations
    }

    @Test
    fun `plugin does not store operations when disabled`() = testApplication {
        application {
            install(SesKtorPlugin) {
                enabled = false
            }
        }

        startApplication()

        application.sesOrNull().shouldBeNull()
        val error = assertFailsWith<IllegalStateException> {
            application.ses()
        }
        error.message shouldContain "disabled"
    }

    @Test
    fun `injected client remains application owned`() = runSuspendIO {
        val runtime = SesKtorPluginConfig().apply {
            sesV2AsyncClient = client
        }.toRuntime()
        requireNotNull(runtime)

        runtime.stop()

        verify(exactly = 0) { client.close() }
    }

    @Test
    fun `runtime closes plugin owned client once`() = runSuspendIO {
        val runtime = SesKtorRuntime(
            operations = operations,
            ownedClient = client,
        )

        runtime.stop()
        runtime.stop()

        verify(exactly = 1) { client.close() }
    }

    @Test
    fun `service customizer runs after shared customizer`() = runSuspendIO {
        val order = mutableListOf<String>()
        val runtime = SesKtorPluginConfig().apply {
            sesV2AsyncClient { order += "service" }
        }.toRuntime(
            AwsKtorDefaults(
                region = "ap-northeast-2",
                endpointOverride = Url("http://localhost:4566"),
                sesV2AsyncClientCustomizers = listOf(
                    AwsKtorSesV2AsyncClientCustomizer { order += "shared" }
                ),
            )
        )
        requireNotNull(runtime)

        order shouldBeEqualTo listOf("shared", "service")

        runtime.stop()
    }
}
