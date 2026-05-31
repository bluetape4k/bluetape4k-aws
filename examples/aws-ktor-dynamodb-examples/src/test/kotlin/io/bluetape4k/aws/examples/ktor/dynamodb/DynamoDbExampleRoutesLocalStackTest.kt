@file:Suppress("DEPRECATION")

package io.bluetape4k.aws.examples.ktor.dynamodb

import aws.sdk.kotlin.runtime.auth.credentials.StaticCredentialsProvider
import aws.smithy.kotlin.runtime.net.url.Url
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.junit5.coroutines.SuspendedJobTester
import io.bluetape4k.testcontainers.aws.AwsEmulatorServer
import io.bluetape4k.testcontainers.aws.FlociServer
import io.bluetape4k.testcontainers.aws.LocalStackServer
import io.bluetape4k.ktor.testing.shouldHaveStatus
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.jackson.jackson
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.util.UUID

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DynamoDbExampleRoutesLocalStackTest {

    private val awsEmulator: AwsEmulatorServer by lazy { awsEmulator("dynamodb") }

    private val endpointUrl: Url by lazy {
        Url.parse(awsEmulator.awsEndpoint.toString())
    }

    private val credentialsProvider: StaticCredentialsProvider by lazy {
        StaticCredentialsProvider {
            accessKeyId = awsEmulator.awsAccessKey
            secretAccessKey = awsEmulator.awsSecretKey
        }
    }

    private fun testModule(block: suspend ApplicationTestBuilder.() -> Unit) =
        testApplication {
            application {
                dynamoDbExampleModule(
                    endpointUrl = endpointUrl,
                    region = awsEmulator.regionName,
                    credentialsProvider = credentialsProvider,
                )
            }
            block()
        }

    @Test
    fun `CRUD - save findById scan delete`() = testModule {
        val jsonClient = createClient { install(ContentNegotiation) { jackson() } }

        val order = Order(id = "order-${UUID.randomUUID()}", status = "NEW", description = "test order")

        jsonClient.post("/dynamodb/orders") {
            contentType(ContentType.Application.Json)
            setBody(order)
        } shouldHaveStatus HttpStatusCode.Created

        val found = jsonClient.get("/dynamodb/orders/${order.id}").body<Order>()
        found.id shouldBeEqualTo order.id
        found.status shouldBeEqualTo order.status

        val orders = jsonClient.get("/dynamodb/orders").body<List<Order>>()
        orders.any { it.id == order.id }.shouldBeTrue()

        jsonClient.delete("/dynamodb/orders/${order.id}") shouldHaveStatus HttpStatusCode.NoContent

        jsonClient.get("/dynamodb/orders/${order.id}") shouldHaveStatus HttpStatusCode.NotFound
    }

    @Test
    fun `concurrent saves and findById retrieve correct results`() = testModule {
        val jsonClient = createClient { install(ContentNegotiation) { jackson() } }

        SuspendedJobTester()
            .workers(4)
            .rounds(3)
            .add {
                val order = Order(
                    id = "order-${UUID.randomUUID()}",
                    status = "CONCURRENT",
                    description = "stress test",
                )
                jsonClient.post("/dynamodb/orders") {
                    contentType(ContentType.Application.Json)
                    setBody(order)
                } shouldHaveStatus HttpStatusCode.Created

                jsonClient.get("/dynamodb/orders/${order.id}").body<Order>().id shouldBeEqualTo order.id
            }
            .run()
    }

    private fun awsEmulator(vararg services: String): AwsEmulatorServer =
        when (val emulator = System.getProperty("bluetape4k.aws.emulator", "floci").trim().lowercase()) {
            "floci" -> FlociServer.Launcher.floci
            "localstack" -> LocalStackServer.Launcher.getLocalStack(*services)
            else -> error("Unsupported AWS emulator: $emulator. Use floci or localstack.")
        }
}
