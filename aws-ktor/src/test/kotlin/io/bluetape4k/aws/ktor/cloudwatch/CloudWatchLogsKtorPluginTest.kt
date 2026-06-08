package io.bluetape4k.aws.ktor.cloudwatch

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldBeSameInstanceAs
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.aws.ktor.AwsKtorCore
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
import software.amazon.awssdk.services.cloudwatchlogs.CloudWatchLogsAsyncClient

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CloudWatchLogsKtorPluginTest {

    private val client = mockk<CloudWatchLogsAsyncClient>(relaxed = true)
    private val operations = mockk<CloudWatchLogsKtorOperations>(relaxed = true)

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
            install(CloudWatchLogsKtorPlugin) {
                cloudWatchLogsOperations = operations
            }
        }

        startApplication()

        application.cloudWatchLogs() shouldBeSameInstanceAs operations
        application.cloudWatchLogsOrNull() shouldBeSameInstanceAs operations
        application.attributes[CloudWatchLogsKtorRuntimeKey].operations shouldBeSameInstanceAs operations
    }

    @Test
    fun `plugin does not store operations when disabled`() = testApplication {
        application {
            install(AwsKtorCore) {
                ktorCore()
            }
            install(CloudWatchLogsKtorPlugin) {
                enabled = false
            }
        }

        startApplication()

        application.cloudWatchLogsOrNull().shouldBeNull()
        val error = assertFailsWith<IllegalStateException> {
            application.cloudWatchLogs()
        }
        error.message shouldContain "disabled"
    }

    @Test
    fun `injected operations bypass client only validation`() {
        val runtime = CloudWatchLogsKtorPluginConfig().apply {
            cloudWatchLogsOperations = operations
            endpointOverride = java.net.URI.create("http://localhost:4566")
        }.toRuntime()
        requireNotNull(runtime)

        runtime.operations shouldBeSameInstanceAs operations
    }

    @Test
    fun `partial default log stream configuration is rejected`() {
        val error = assertFailsWith<IllegalArgumentException> {
            CloudWatchLogsKtorPluginConfig().apply {
                cloudWatchLogsOperations = operations
                logGroupName = "/app/test"
            }.toRuntime()
        }

        error.message shouldContain "together"
    }

    @Test
    fun `injected client remains application owned`() = runSuspendIO {
        val runtime = CloudWatchLogsKtorPluginConfig().apply {
            cloudWatchLogsAsyncClient = client
        }.toRuntime()
        requireNotNull(runtime)

        runtime.stop()

        verify(exactly = 0) { client.close() }
    }

    @Test
    fun `service customizer runs after shared customizer`() = runSuspendIO {
        val order = mutableListOf<String>()
        val runtime = CloudWatchLogsKtorPluginConfig().apply {
            cloudWatchLogsAsyncClient { order += "service" }
        }.toRuntime(
            io.bluetape4k.aws.ktor.AwsKtorDefaults(
                region = "ap-northeast-2",
                endpointOverride = Url("http://localhost:4566"),
                cloudWatchLogsAsyncClientCustomizers = listOf(
                    io.bluetape4k.aws.ktor.AwsKtorCloudWatchLogsAsyncClientCustomizer { order += "shared" }
                ),
            )
        )
        requireNotNull(runtime)

        order shouldBeEqualTo listOf("shared", "service")

        runtime.stop()
    }
}
