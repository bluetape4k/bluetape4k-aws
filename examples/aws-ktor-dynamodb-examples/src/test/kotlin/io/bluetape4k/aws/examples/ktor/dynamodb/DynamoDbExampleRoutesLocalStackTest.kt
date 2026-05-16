package io.bluetape4k.aws.examples.ktor.dynamodb

import aws.sdk.kotlin.runtime.auth.credentials.StaticCredentialsProvider
import aws.smithy.kotlin.runtime.net.url.Url
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.testcontainers.aws.LocalStackServer
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
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.util.UUID

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DynamoDbExampleRoutesLocalStackTest {

    @Suppress("DEPRECATION")
    private val localStack: LocalStackServer by lazy {
        LocalStackServer.Launcher.getLocalStack("dynamodb")
    }

    private val endpointUrl: Url by lazy {
        Url.parse(localStack.endpoint.toString())
    }

    private val credentialsProvider: StaticCredentialsProvider by lazy {
        StaticCredentialsProvider {
            accessKeyId = localStack.accessKey
            secretAccessKey = localStack.secretKey
        }
    }

    @Test
    fun `CRUD operations - save findById scan delete`() = runSuspendIO {
        testApplication {
            application {
                dynamoDbExampleModule(
                    endpointUrl = endpointUrl,
                    region = localStack.regionName,
                    credentialsProvider = credentialsProvider,
                )
            }

            val jsonClient = createClient {
                install(ContentNegotiation) { jackson() }
            }

            val order = Order(
                id = "order-${UUID.randomUUID()}",
                status = "NEW",
                description = "test order",
            )

            val saveResponse = jsonClient.post("/dynamodb/orders") {
                contentType(ContentType.Application.Json)
                setBody(order)
            }
            saveResponse.status shouldBeEqualTo HttpStatusCode.Created

            val findResponse = jsonClient.get("/dynamodb/orders/${order.id}")
            findResponse.status shouldBeEqualTo HttpStatusCode.OK
            val found = findResponse.body<Order>()
            found.id shouldBeEqualTo order.id
            found.status shouldBeEqualTo order.status

            val scanResponse = jsonClient.get("/dynamodb/orders")
            scanResponse.status shouldBeEqualTo HttpStatusCode.OK
            val orders = scanResponse.body<List<Order>>()
            assert(orders.any { it.id == order.id }) {
                "Expected scan results to contain order ${order.id}"
            }

            val deleteResponse = jsonClient.delete("/dynamodb/orders/${order.id}")
            deleteResponse.status shouldBeEqualTo HttpStatusCode.NoContent

            val findAfterDeleteResponse = jsonClient.get("/dynamodb/orders/${order.id}")
            findAfterDeleteResponse.status shouldBeEqualTo HttpStatusCode.NotFound
        }
    }
}
