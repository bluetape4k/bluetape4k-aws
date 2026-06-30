package io.bluetape4k.aws.ktor.sns

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldBeSameInstanceAs
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.aws.ktor.AwsKtorCore
import io.bluetape4k.aws.ktor.AwsKtorDefaults
import io.bluetape4k.aws.ktor.AwsKtorSnsAsyncClientCustomizer
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
import software.amazon.awssdk.services.sns.SnsAsyncClient

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SnsKtorPluginTest {

    private val client = mockk<SnsAsyncClient>(relaxed = true)
    private val operations = mockk<SnsKtorOperations>(relaxed = true)

    @BeforeEach
    fun resetMocks() {
        clearMocks(client, operations)
    }

    @Test
    fun `plugin stores injected operations and parser`() = testApplication {
        val parser = SnsHttpMessageParser.default()
        application {
            install(AwsKtorCore) {
                ktorCore()
            }
            install(SnsKtorPlugin) {
                snsOperations = operations
                snsHttpMessageParser = parser
            }
        }

        startApplication()

        application.sns() shouldBeSameInstanceAs operations
        application.snsOrNull() shouldBeSameInstanceAs operations
        application.snsHttpMessageParser() shouldBeSameInstanceAs parser
        application.attributes[SnsKtorRuntimeKey].operations shouldBeSameInstanceAs operations
    }

    @Test
    fun `plugin does not store operations when disabled`() = testApplication {
        application {
            install(SnsKtorPlugin) {
                enabled = false
            }
        }

        startApplication()

        application.snsOrNull().shouldBeNull()
        application.snsHttpMessageParserOrNull().shouldBeNull()
        val error = assertFailsWith<IllegalStateException> {
            application.sns()
        }
        error.message shouldContain "disabled"
    }

    @Test
    fun `injected client remains application owned`() = runSuspendIO {
        val runtime = SnsKtorPluginConfig().apply {
            snsAsyncClient = client
        }.toRuntime()
        requireNotNull(runtime)

        runtime.stop()

        verify(exactly = 0) { client.close() }
    }

    @Test
    fun `runtime closes plugin owned client once`() = runSuspendIO {
        val runtime = SnsKtorRuntime(
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
        val runtime = SnsKtorPluginConfig().apply {
            snsAsyncClient { order += "service" }
        }.toRuntime(
            AwsKtorDefaults(
                region = "ap-northeast-2",
                endpointOverride = Url("http://localhost:4566"),
                snsAsyncClientCustomizers = listOf(
                    AwsKtorSnsAsyncClientCustomizer { order += "shared" }
                ),
            )
        )
        requireNotNull(runtime)

        order shouldBeEqualTo listOf("shared", "service")

        runtime.stop()
    }
}
