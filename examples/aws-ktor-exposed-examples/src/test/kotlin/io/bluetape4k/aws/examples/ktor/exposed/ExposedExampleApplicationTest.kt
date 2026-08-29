package io.bluetape4k.aws.examples.ktor.exposed

import io.bluetape4k.aws.ktor.exposed.awsExposedTransaction
import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.exposed.core.ExposedCursorPage
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
import org.jetbrains.exposed.v1.core.SqlLogger
import org.jetbrains.exposed.v1.core.Transaction
import org.jetbrains.exposed.v1.core.statements.StatementContext
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
        val listed = listResponse.body<ExposedCursorPage<OrderRecord, Long>>()
        listed.content.map { it.id } shouldContain created.id
        listed.hasNext.shouldBeFalse()
        listed.nextCursor shouldBeEqualTo null

        val missingResponse = client.get("/exposed/orders/${Long.MAX_VALUE}")
        missingResponse shouldHaveStatus HttpStatusCode.NotFound
    }

    @Test
    fun `order list returns cursor pages without duplicates`() = testApplication {
        application {
            exposedExampleModule(ExampleDatabaseConfig.from(postgres))
        }
        startApplication()

        val client = createClient {
            install(ContentNegotiation) {
                jackson()
            }
        }
        val customerId = "cursor-customer-${System.nanoTime()}"
        val orderIds = buildList {
            repeat(4) { index ->
                val response = client.post("/exposed/orders") {
                    contentType(ContentType.Application.Json)
                    setBody(OrderRequest(customerId = customerId, notes = "page-$index"))
                }
                response shouldHaveStatus HttpStatusCode.Created
                add(response.body<OrderRecord>().id)
            }
        }
        val otherCustomerId = "other-customer-${System.nanoTime()}"
        client.post("/exposed/orders") {
            contentType(ContentType.Application.Json)
            setBody(OrderRequest(customerId = otherCustomerId, notes = "must-not-leak"))
        } shouldHaveStatus HttpStatusCode.Created

        application.awsExposedTransaction {
            OrderRepository.deleteById(orderIds[1])
        }

        val firstResponse = client.get("/exposed/orders?customerId=$customerId&limit=2")
        firstResponse shouldHaveStatus HttpStatusCode.OK
        val first = firstResponse.body<ExposedCursorPage<OrderRecord, Long>>()
        first.content.size shouldBeEqualTo 2
        first.content.map { it.customerId }.toSet() shouldBeEqualTo setOf(customerId)
        first.hasNext.shouldBeTrue()
        val cursor = first.nextCursor ?: error("first page must contain nextCursor")

        val secondResponse = client.get(
            "/exposed/orders?customerId=$customerId&limit=2&cursor=$cursor",
        )
        secondResponse shouldHaveStatus HttpStatusCode.OK
        val second = secondResponse.body<ExposedCursorPage<OrderRecord, Long>>()
        second.content.size shouldBeEqualTo 1
        second.content.map { it.customerId }.toSet() shouldBeEqualTo setOf(customerId)
        second.hasNext.shouldBeFalse()
        second.nextCursor shouldBeEqualTo null

        val pageIds = first.content.map { it.id } + second.content.map { it.id }
        pageIds.toSet().size shouldBeEqualTo 3
        pageIds.contains(orderIds[1]).shouldBeFalse()
    }

    @Test
    fun `order list returns an empty cursor page without a count query`() = testApplication {
        application {
            exposedExampleModule(ExampleDatabaseConfig.from(postgres))
        }
        startApplication()

        val statements = mutableListOf<String>()
        val page = application.awsExposedTransaction {
            addLogger(recordSql(statements))
            OrderRepository.findOrderPage(
                OrderPageRequest.parse(
                    rawCursor = null,
                    rawLimit = null,
                    customerId = "missing-customer-${System.nanoTime()}",
                ),
            )
        }

        page.content shouldBeEqualTo emptyList()
        page.hasNext.shouldBeFalse()
        page.nextCursor shouldBeEqualTo null
        statements.count { it.trimStart().startsWith("SELECT", ignoreCase = true) } shouldBeEqualTo 1
        statements.any { it.contains("COUNT(", ignoreCase = true) }.shouldBeFalse()
    }

    @Test
    fun `order list rejects invalid cursor and limit`() = testApplication {
        application {
            exposedExampleModule(ExampleDatabaseConfig.from(postgres))
        }
        startApplication()

        val client = createClient {
            install(ContentNegotiation) {
                jackson()
            }
        }

        client.get("/exposed/orders?limit=0") shouldHaveStatus HttpStatusCode.BadRequest
        client.get("/exposed/orders?limit=101") shouldHaveStatus HttpStatusCode.BadRequest
        client.get("/exposed/orders?limit=not-a-number") shouldHaveStatus HttpStatusCode.BadRequest
        client.get("/exposed/orders?cursor=not-a-number") shouldHaveStatus HttpStatusCode.BadRequest
        client.get("/exposed/orders?cursor=-1") shouldHaveStatus HttpStatusCode.BadRequest
    }

    @Test
    fun `order record rejects negative id with bluetape assertion`() {
        val error = assertFailsWith<IllegalArgumentException> {
            OrderRecord(
                id = -1L,
                customerId = "customer",
                status = OrderStatus.CREATED,
            )
        }

        error.message shouldContain "id"
    }

    private fun recordSql(statements: MutableList<String>) = object: SqlLogger {
        override fun log(context: StatementContext, transaction: Transaction) {
            statements += context.sql(transaction)
        }
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
