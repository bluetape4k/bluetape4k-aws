package io.bluetape4k.aws.examples.ktor.exposed

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.ktor.testing.shouldHaveStatus
import io.bluetape4k.testcontainers.database.PostgreSQLServer
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
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

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ExposedExampleApplicationTest {

    @Test
    fun `order routes create read list and return not found`() = testApplication {
        application {
            exposedExampleModule(ExampleDatabaseConfig.from(postgres))
        }
        startApplication()

        val client = createClient {
            install(ContentNegotiation) {
                jackson()
            }
        }
        val request = OrderRequest(
            customerId = "ktor-customer-${System.nanoTime()}",
            status = OrderStatus.PAID,
            notes = "created through Ktor",
        )

        val createResponse = client.post("/exposed/orders") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }
        createResponse shouldHaveStatus HttpStatusCode.Created
        val created = createResponse.body<OrderRecord>()
        created.customerId shouldBeEqualTo request.customerId
        created.status shouldBeEqualTo request.status

        val readResponse = client.get("/exposed/orders/${created.id}")
        readResponse shouldHaveStatus HttpStatusCode.OK
        readResponse.body<OrderRecord>() shouldBeEqualTo created

        val listResponse = client.get("/exposed/orders?customerId=${request.customerId}")
        listResponse shouldHaveStatus HttpStatusCode.OK
        val listed = listResponse.body<List<OrderRecord>>()
        listed.map { it.id } shouldContain created.id

        val missingResponse = client.get("/exposed/orders/${Long.MAX_VALUE}")
        missingResponse shouldHaveStatus HttpStatusCode.NotFound
    }

    private companion object {
        val postgres = PostgreSQLServer.Launcher.postgres

        fun ExampleDatabaseConfig.Companion.from(postgres: PostgreSQLServer): ExampleDatabaseConfig =
            ExampleDatabaseConfig(
                url = postgres.getJdbcUrl(),
                driverClassName = postgres.getDriverClassName(),
                username = postgres.getUsername().orEmpty(),
                password = postgres.getPassword().orEmpty(),
            )
    }
}
