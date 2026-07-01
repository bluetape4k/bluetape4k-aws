package io.bluetape4k.aws.ktor.eventbridge

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldBeSameInstanceAs
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.aws.ktor.AwsKtorCore
import io.bluetape4k.aws.ktor.AwsKtorDefaults
import io.bluetape4k.aws.ktor.AwsKtorEventBridgeAsyncClientCustomizer
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
import software.amazon.awssdk.services.eventbridge.EventBridgeAsyncClient

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EventBridgeKtorPluginTest {

    private val client = mockk<EventBridgeAsyncClient>(relaxed = true)
    private val operations = mockk<EventBridgeKtorOperations>(relaxed = true)

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
            install(EventBridgeKtorPlugin) {
                eventBridgeOperations = operations
            }
        }

        startApplication()

        application.eventBridge() shouldBeSameInstanceAs operations
        application.eventBridgeOrNull() shouldBeSameInstanceAs operations
        application.attributes[EventBridgeKtorRuntimeKey].operations shouldBeSameInstanceAs operations
    }

    @Test
    fun `plugin does not store operations when disabled`() = testApplication {
        application {
            install(EventBridgeKtorPlugin) {
                enabled = false
            }
        }

        startApplication()

        application.eventBridgeOrNull().shouldBeNull()
        val error = assertFailsWith<IllegalStateException> {
            application.eventBridge()
        }
        error.message shouldContain "disabled"
    }

    @Test
    fun `injected client remains application owned`() = runSuspendIO {
        val runtime = EventBridgeKtorPluginConfig().apply {
            eventBridgeAsyncClient = client
        }.toRuntime()
        requireNotNull(runtime)

        runtime.stop()

        verify(exactly = 0) { client.close() }
    }

    @Test
    fun `runtime closes plugin owned client once`() = runSuspendIO {
        val runtime = EventBridgeKtorRuntime(
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
        val runtime = EventBridgeKtorPluginConfig().apply {
            eventBridgeAsyncClient { order += "service" }
        }.toRuntime(
            AwsKtorDefaults(
                region = "ap-northeast-2",
                endpointOverride = Url("http://localhost:4566"),
                eventBridgeAsyncClientCustomizers = listOf(
                    AwsKtorEventBridgeAsyncClientCustomizer { order += "shared" }
                ),
            )
        )
        requireNotNull(runtime)

        order shouldBeEqualTo listOf("shared", "service")

        runtime.stop()
    }

    @Test
    fun `default event bus property is carried into template`() {
        val runtime = EventBridgeKtorPluginConfig().apply {
            eventBridgeAsyncClient = client
            defaultEventBusName = "orders"
        }.toRuntime()
        requireNotNull(runtime)

        runtime.operations::class shouldBeEqualTo EventBridgeKtorTemplate::class
    }
}
