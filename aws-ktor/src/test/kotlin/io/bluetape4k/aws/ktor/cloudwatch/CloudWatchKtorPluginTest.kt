package io.bluetape4k.aws.ktor.cloudwatch

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldBeSameInstanceAs
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.aws.ktor.AwsKtorCore
import io.bluetape4k.junit5.coroutines.runSuspendIO
import aws.smithy.kotlin.runtime.net.url.Url as SmithyUrl
import io.ktor.http.Url as KtorUrl
import io.ktor.server.application.install
import io.ktor.server.testing.testApplication
import io.mockk.clearMocks
import io.mockk.mockk
import io.mockk.verify
import aws.sdk.kotlin.services.cloudwatch.CloudWatchClient
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CloudWatchKtorPluginTest {

    private val client = mockk<CloudWatchClient>(relaxed = true)
    private val operations = mockk<CloudWatchKtorOperations>(relaxed = true)

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
            install(CloudWatchKtorPlugin) {
                cloudWatchOperations = operations
            }
        }

        startApplication()

        application.cloudWatch() shouldBeSameInstanceAs operations
        application.cloudWatchOrNull() shouldBeSameInstanceAs operations
        application.attributes[CloudWatchKtorRuntimeKey].operations shouldBeSameInstanceAs operations
    }

    @Test
    fun `plugin does not store operations when disabled`() = testApplication {
        application {
            install(AwsKtorCore) {
                ktorCore()
            }
            install(CloudWatchKtorPlugin) {
                enabled = false
            }
        }

        startApplication()

        application.cloudWatchOrNull().shouldBeNull()
        val error = assertFailsWith<IllegalStateException> {
            application.cloudWatch()
        }
        error.message shouldContain "disabled"
    }

    @Test
    fun `injected operations bypass client only validation`() {
        val runtime = CloudWatchKtorPluginConfig().apply {
            cloudWatchOperations = operations
            endpointOverride = SmithyUrl.parse("http://localhost:4566")
            batchSize = 0
        }.toRuntime()
        requireNotNull(runtime)

        runtime.operations shouldBeSameInstanceAs operations
    }

    @Test
    fun `injected client remains application owned`() = runSuspendIO {
        val runtime = CloudWatchKtorPluginConfig().apply {
            cloudWatchClient = client
        }.toRuntime()
        requireNotNull(runtime)

        runtime.stop()

        verify(exactly = 0) { client.close() }
    }

    @Test
    fun `runtime closes plugin owned client once`() = runSuspendIO {
        val runtime = CloudWatchKtorRuntime(
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
        val config = CloudWatchKtorPluginConfig().apply {
            cloudWatchClient { order += "service" }
        }.toRuntime(
            io.bluetape4k.aws.ktor.AwsKtorDefaults(
                region = "ap-northeast-2",
                endpointOverride = KtorUrl("http://localhost:4566"),
                cloudWatchClientCustomizers = listOf(
                    io.bluetape4k.aws.ktor.AwsKtorCloudWatchClientCustomizer { order += "shared" }
                ),
            )
        )
        requireNotNull(config)

        order shouldBeEqualTo listOf("shared", "service")

        config.stop()
    }
}
