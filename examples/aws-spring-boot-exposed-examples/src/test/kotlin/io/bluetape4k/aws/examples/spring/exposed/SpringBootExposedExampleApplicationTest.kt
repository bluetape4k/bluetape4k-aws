package io.bluetape4k.aws.examples.spring.exposed

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeSameInstanceAs
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.assertions.shouldHaveSize
import io.bluetape4k.aws.exposed.AwsExposedDatabaseRegistry
import io.bluetape4k.testcontainers.database.PostgreSQLServer
import io.bluetape4k.testcontainers.spring.registerDynamicProperties
import org.jetbrains.exposed.v1.core.SqlLogger
import org.jetbrains.exposed.v1.core.Transaction
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.statements.StatementContext
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.core.env.Environment
import org.springframework.data.domain.Example
import org.springframework.data.domain.ExampleMatcher
import org.springframework.data.domain.Sort
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import javax.sql.DataSource

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SpringBootExposedExampleApplicationTest {

    @LocalServerPort
    private var port: Int = 0

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @Autowired
    private lateinit var registry: AwsExposedDatabaseRegistry

    @Autowired
    private lateinit var dataSource: DataSource

    @Autowired
    private lateinit var database: Database

    @Autowired
    private lateinit var environment: Environment

    @Autowired
    private lateinit var orderSpringDataRepository: OrderSpringDataRepository

    private val httpClient: HttpClient = HttpClient.newHttpClient()

    @Test
    fun `auto configuration creates shared Exposed beans`() {
        registry.defaultHandle.dataSource shouldBeSameInstanceAs dataSource
        registry.defaultHandle.database shouldBeSameInstanceAs database
    }

    @Test
    fun `testcontainers bridge resolves AWS database placeholders`() {
        environment.getRequiredProperty("testcontainers.postgresql.jdbc-url") shouldBeEqualTo postgres.jdbcUrl
        environment.getRequiredProperty("testcontainers.postgresql.driver-class-name") shouldBeEqualTo
            postgres.getDriverClassName()
        environment.getRequiredProperty("testcontainers.postgresql.username") shouldBeEqualTo postgres.getUsername()
        environment.getRequiredProperty("testcontainers.postgresql.password") shouldBeEqualTo postgres.getPassword()
        environment.getRequiredProperty("bluetape4k.aws.exposed.default-database.url") shouldBeEqualTo postgres.jdbcUrl
        environment.getRequiredProperty("bluetape4k.aws.exposed.default-database.driver-class-name") shouldBeEqualTo
            postgres.getDriverClassName()
        environment.getRequiredProperty("bluetape4k.aws.exposed.default-database.username") shouldBeEqualTo
            postgres.getUsername()
        environment.getRequiredProperty("bluetape4k.aws.exposed.default-database.password") shouldBeEqualTo
            postgres.getPassword()
    }

    @Test
    fun `order api creates reads lists and returns not found`() {
        val request = OrderRequest(
            customerId = "spring-customer-${System.nanoTime()}",
            status = OrderStatus.CREATED,
            notes = "created through Spring MVC",
        )

        val createResponse = post("/orders", request)
        createResponse.statusCode() shouldBeEqualTo 201
        val created = objectMapper.readValue(createResponse.body(), OrderRecord::class.java)
        created.customerId shouldBeEqualTo request.customerId
        created.status shouldBeEqualTo request.status

        val readResponse = get("/orders/${created.id}")
        readResponse.statusCode() shouldBeEqualTo 200
        val found = objectMapper.readValue(readResponse.body(), OrderRecord::class.java)
        found shouldBeEqualTo created

        val listResponse = get("/orders?customerId=${request.customerId}")
        listResponse.statusCode() shouldBeEqualTo 200
        val listed = readPage(listResponse)
        listed.content.map { it.id } shouldContain created.id
        listed.hasNext.shouldBeFalse()
        listed.nextCursor shouldBeEqualTo null

        val missingResponse = get("/orders/${Long.MAX_VALUE}")
        missingResponse.statusCode() shouldBeEqualTo 404
    }

    @Test
    fun `order api returns cursor pages without duplicates`() {
        val customerId = "cursor-customer-${System.nanoTime()}"
        val orderIds = buildList {
            repeat(4) { index ->
                val response = post(
                    "/orders",
                    OrderRequest(customerId = customerId, notes = "page-$index"),
                )
                response.statusCode() shouldBeEqualTo 201
                add(objectMapper.readValue(response.body(), OrderRecord::class.java).id)
            }
        }
        val otherCustomerId = "other-customer-${System.nanoTime()}"
        post(
            "/orders",
            OrderRequest(customerId = otherCustomerId, notes = "must-not-leak"),
        ).statusCode() shouldBeEqualTo 201

        transaction(database) {
            OrderRepository.deleteById(orderIds[1])
        }

        val firstResponse = get("/orders?customerId=$customerId&limit=2")
        firstResponse.statusCode() shouldBeEqualTo 200
        val first = readPage(firstResponse)
        first.content.size shouldBeEqualTo 2
        first.content.map { it.customerId }.toSet() shouldBeEqualTo setOf(customerId)
        first.hasNext.shouldBeTrue()
        val cursor = first.nextCursor ?: error("first page must contain nextCursor")

        val secondResponse = get("/orders?customerId=$customerId&limit=2&cursor=$cursor")
        secondResponse.statusCode() shouldBeEqualTo 200
        val second = readPage(secondResponse)
        second.content.size shouldBeEqualTo 1
        second.content.map { it.customerId }.toSet() shouldBeEqualTo setOf(customerId)
        second.hasNext.shouldBeFalse()
        second.nextCursor shouldBeEqualTo null

        val pageIds = first.content.map { it.id } + second.content.map { it.id }
        pageIds.toSet().size shouldBeEqualTo 3
        pageIds.contains(orderIds[1]).shouldBeFalse()
    }

    @Test
    fun `order api returns an empty cursor page without a count query`() {
        val statements = mutableListOf<String>()
        val page = transaction(database) {
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
    fun `order api rejects invalid cursor and limit`() {
        get("/orders?limit=0").statusCode() shouldBeEqualTo 400
        get("/orders?limit=101").statusCode() shouldBeEqualTo 400
        get("/orders?limit=not-a-number").statusCode() shouldBeEqualTo 400
        get("/orders?cursor=not-a-number").statusCode() shouldBeEqualTo 400
        get("/orders?cursor=-1").statusCode() shouldBeEqualTo 400
    }

    @Test
    fun `order search combines qbe conditions and returns bounded projection`() {
        val customerId = "qbe-customer-${System.nanoTime()}"
        post("/orders", OrderRequest(customerId, OrderStatus.PAID, "first"))
        post("/orders", OrderRequest(customerId, OrderStatus.PAID, "second"))
        post("/orders", OrderRequest(customerId, OrderStatus.CANCELLED, "ignored"))

        val response = get(
            "/orders/search?customerId=$customerId&status=PAID&limit=1&sort=-customerId",
        )

        response.statusCode() shouldBeEqualTo 200
        val projected = objectMapper
            .readValue(response.body(), Array<OrderSummaryResponse>::class.java)
            .toList()
        projected shouldBeEqualTo listOf(OrderSummaryResponse(customerId, "PAID"))

        get("/orders/search?customerId=missing-$customerId&status=PAID").statusCode() shouldBeEqualTo 200
        get("/orders/search?customerId=$customerId&status=UNKNOWN").statusCode() shouldBeEqualTo 400
        get("/orders/search?customerId=$customerId&limit=101").statusCode() shouldBeEqualTo 400
        get("/orders/search?customerId=$customerId&sort=notes").statusCode() shouldBeEqualTo 400
    }

    @Test
    @Transactional(transactionManager = "springTransactionManager")
    fun `qbe projection pushes selected columns sort and limit into one sql query`() {
        val customerId = "qbe-sql-customer-${System.nanoTime()}"
        OrderRepository.save(OrderRecord(customerId = customerId, status = OrderStatus.PAID, notes = "one"))
        OrderRepository.save(OrderRecord(customerId = customerId, status = OrderStatus.PAID, notes = "two"))
        OrderRepository.save(OrderRecord(customerId = customerId, status = OrderStatus.CANCELLED, notes = "three"))

        val probe = OrderEntity.find {
            (OrdersTable.customerId eq customerId) and (OrdersTable.status eq OrderStatus.PAID.name)
        }.first()
        val example = Example.of(
            probe,
            ExampleMatcher.matchingAll()
                .withIgnorePaths("notes")
                .withMatcher("customerId", ExampleMatcher.GenericPropertyMatchers.exact())
                .withMatcher("status", ExampleMatcher.GenericPropertyMatchers.exact()),
        )
        val statements = mutableListOf<String>()
        TransactionManager.current().addLogger(object: SqlLogger {
            override fun log(context: StatementContext, transaction: Transaction) {
                statements += context.sql(transaction)
            }
        })

        val projected = orderSpringDataRepository.findBy(example) { query ->
            query.`as`(OrderSummaryProjection::class.java)
                .project(mutableListOf("customerId", "status"))
                .sortBy(Sort.by(Sort.Direction.DESC, "status"))
                .limit(1)
                .all()
        }

        projected shouldBeEqualTo listOf(OrderSummaryProjection(customerId, OrderStatus.PAID.name))
        val selectStatements = statements.filter { it.trimStart().startsWith("SELECT", ignoreCase = true) }
        selectStatements shouldHaveSize 1
        val select = selectStatements.single()
        select.contains("customer_id", ignoreCase = true).shouldBeTrue()
        select.contains("status", ignoreCase = true).shouldBeTrue()
        select.contains("notes", ignoreCase = true).shouldBeFalse()
        select.contains("ORDER BY", ignoreCase = true).shouldBeTrue()
        select.contains("LIMIT", ignoreCase = true).shouldBeTrue()
        select.contains("COUNT(", ignoreCase = true).shouldBeFalse()
    }

    private fun get(path: String): HttpResponse<String> =
        httpClient.send(
            HttpRequest.newBuilder(uri(path)).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )

    private fun post(path: String, body: Any): HttpResponse<String> {
        val json = objectMapper.writeValueAsString(body)
        return httpClient.send(
            HttpRequest.newBuilder(uri(path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )
    }

    private fun readPage(response: HttpResponse<String>): OrderPagePayload =
        objectMapper.readValue(
            response.body(),
            object : tools.jackson.core.type.TypeReference<OrderPagePayload>() {},
        )

    private fun recordSql(statements: MutableList<String>) = object: SqlLogger {
        override fun log(context: StatementContext, transaction: Transaction) {
            statements += context.sql(transaction)
        }
    }

    private fun uri(path: String): URI =
        URI.create("http://localhost:$port$path")

    companion object {
        private val postgres = PostgreSQLServer.Launcher.postgres

        @JvmStatic
        @DynamicPropertySource
        fun databaseProperties(registry: DynamicPropertyRegistry) {
            val runningBeforeRegistration = postgres.isRunning
            postgres.registerDynamicProperties(registry)
            postgres.isRunning shouldBeEqualTo runningBeforeRegistration
            registry.add("bluetape4k.aws.exposed.default-database.pool.maximum-pool-size") { "2" }
            registry.add("bluetape4k.aws.exposed.default-database.pool.minimum-idle") { "0" }
        }
    }
}

private data class OrderPagePayload(
    val content: List<OrderRecord> = emptyList(),
    val nextCursor: Long? = null,
    val hasNext: Boolean = false,
)

private data class OrderSummaryResponse(
    val customerId: String = "",
    val status: String = "",
)
