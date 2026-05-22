package io.bluetape4k.aws.examples.spring.exposed

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeSameInstanceAs
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.aws.exposed.AwsExposedDatabaseRegistry
import io.bluetape4k.testcontainers.database.PostgreSQLServer
import org.jetbrains.exposed.v1.jdbc.Database
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
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

    private val httpClient: HttpClient = HttpClient.newHttpClient()

    @Test
    fun `auto configuration creates shared Exposed beans`() {
        registry.defaultHandle.dataSource shouldBeSameInstanceAs dataSource
        registry.defaultHandle.database shouldBeSameInstanceAs database
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
        val listed = objectMapper.readValue(listResponse.body(), Array<OrderRecord>::class.java).toList()
        listed.map { it.id } shouldContain created.id

        val missingResponse = get("/orders/${Long.MAX_VALUE}")
        missingResponse.statusCode() shouldBeEqualTo 404
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

    private fun uri(path: String): URI =
        URI.create("http://localhost:$port$path")

    companion object {
        private val postgres = PostgreSQLServer.Launcher.postgres

        @JvmStatic
        @DynamicPropertySource
        fun databaseProperties(registry: DynamicPropertyRegistry) {
            registry.add("bluetape4k.aws.exposed.default-database.url") { postgres.getJdbcUrl() }
            registry.add("bluetape4k.aws.exposed.default-database.driver-class-name") { postgres.getDriverClassName() }
            registry.add("bluetape4k.aws.exposed.default-database.username") { postgres.getUsername().orEmpty() }
            registry.add("bluetape4k.aws.exposed.default-database.password") { postgres.getPassword().orEmpty() }
            registry.add("bluetape4k.aws.exposed.default-database.pool.maximum-pool-size") { "2" }
            registry.add("bluetape4k.aws.exposed.default-database.pool.minimum-idle") { "0" }
        }
    }
}
