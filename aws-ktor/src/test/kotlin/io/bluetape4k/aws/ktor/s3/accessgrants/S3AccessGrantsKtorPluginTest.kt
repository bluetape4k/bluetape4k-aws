package io.bluetape4k.aws.ktor.s3.accessgrants

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldBeSameInstanceAs
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.aws.ktor.AwsKtorCore
import io.bluetape4k.aws.ktor.AwsKtorDefaults
import io.bluetape4k.aws.ktor.AwsKtorS3ControlAsyncClientCustomizer
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.ktor.testing.shouldHaveStatus
import io.mockk.clearMocks
import io.mockk.mockk
import io.mockk.verify
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.server.response.respond
import io.ktor.server.application.install
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.services.s3control.S3ControlAsyncClient
import java.net.URI

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class S3AccessGrantsKtorPluginTest {

    private val client = mockk<S3ControlAsyncClient>(relaxed = true)
    private val operations = mockk<S3AccessGrantsKtorOperations>(relaxed = true)

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
            install(S3AccessGrantsKtorPlugin) {
                s3AccessGrantsOperations = operations
            }
        }

        startApplication()

        application.s3AccessGrants() shouldBeSameInstanceAs operations
        application.s3AccessGrantsOrNull() shouldBeSameInstanceAs operations
        application.attributes[S3AccessGrantsKtorRuntimeKey].operations shouldBeSameInstanceAs operations
    }

    @Test
    fun `plugin operations are available from routes using bluetape4k Ktor core baseline`() = testApplication {
        application {
            install(AwsKtorCore) {
                ktorCore()
            }
            install(S3AccessGrantsKtorPlugin) {
                s3AccessGrantsOperations = operations
            }
            routing {
                get("/access-grants/runtime") {
                    call.application.s3AccessGrants() shouldBeSameInstanceAs operations
                    call.respond(HttpStatusCode.NoContent)
                }
            }
        }

        startApplication()

        client.get("/access-grants/runtime") shouldHaveStatus HttpStatusCode.NoContent
    }

    @Test
    fun `plugin does not store operations when disabled`() = testApplication {
        application {
            install(S3AccessGrantsKtorPlugin) {
                enabled = false
            }
        }

        startApplication()

        application.s3AccessGrantsOrNull().shouldBeNull()
        val error = assertFailsWith<IllegalStateException> {
            application.s3AccessGrants()
        }
        error.message shouldContain "disabled"
    }

    @Test
    fun `injected operations bypass client only validation`() {
        val runtime = S3AccessGrantsKtorPluginConfig().apply {
            s3AccessGrantsOperations = operations
            endpointOverride = URI.create("http://localhost:4566")
        }.toRuntime()
        requireNotNull(runtime)

        runtime.operations shouldBeSameInstanceAs operations
    }

    @Test
    fun `injected client remains application owned`() = runSuspendIO {
        val runtime = S3AccessGrantsKtorPluginConfig().apply {
            s3ControlAsyncClient = client
        }.toRuntime()
        requireNotNull(runtime)

        runtime.stop()

        verify(exactly = 0) { client.close() }
    }

    @Test
    fun `runtime closes plugin owned client once`() = runSuspendIO {
        val runtime = S3AccessGrantsKtorRuntime(
            operations = operations,
            ownedClient = client,
        )

        runtime.stop()
        runtime.stop()

        verify(exactly = 1) { client.close() }
    }

    @Test
    fun `endpoint override requires region for plugin created client`() {
        val error = assertFailsWith<IllegalArgumentException> {
            S3AccessGrantsKtorPluginConfig().apply {
                endpointOverride = URI.create("http://localhost:4566")
            }.toRuntime()
        }

        error.message shouldContain "region"
    }

    @Test
    fun `service customizer runs after shared customizer`() = runSuspendIO {
        val order = mutableListOf<String>()
        val credentials = StaticCredentialsProvider.create(AwsBasicCredentials.create("ak", "sk"))
        val runtime = S3AccessGrantsKtorPluginConfig().apply {
            s3ControlAsyncClient { order += "service" }
        }.toRuntime(
            AwsKtorDefaults(
                region = "ap-northeast-2",
                endpointOverride = Url("http://localhost:4566"),
                javaCredentialsProvider = credentials,
                s3ControlAsyncClientCustomizers = listOf(
                    AwsKtorS3ControlAsyncClientCustomizer { order += "shared" }
                ),
            )
        )
        requireNotNull(runtime)

        order shouldBeEqualTo listOf("shared", "service")

        runtime.stop()
    }
}
