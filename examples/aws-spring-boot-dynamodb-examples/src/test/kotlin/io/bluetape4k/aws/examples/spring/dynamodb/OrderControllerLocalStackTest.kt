@file:Suppress("DEPRECATION")

package io.bluetape4k.aws.examples.spring.dynamodb

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.aws.spring.AwsAutoConfiguration
import io.bluetape4k.aws.spring.dynamodb.DynamoDbAutoConfiguration
import io.bluetape4k.aws.spring.dynamodb.DynamoDbTableNameResolver
import io.bluetape4k.junit5.awaitility.untilSuspending
import io.bluetape4k.junit5.coroutines.SuspendedJobTester
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.testcontainers.aws.AwsEmulatorServer
import io.bluetape4k.testcontainers.aws.FlociServer
import io.bluetape4k.testcontainers.aws.LocalStackServer
import io.bluetape4k.testcontainers.aws.getCredentialProvider
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.future.await
import org.awaitility.core.ConditionTimeoutException
import org.awaitility.kotlin.await
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.http.MediaType
import org.springframework.test.web.reactive.server.WebTestClient
import org.springframework.test.web.reactive.server.expectBody
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedAsyncClient
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient
import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition
import software.amazon.awssdk.services.dynamodb.model.BillingMode
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement
import software.amazon.awssdk.services.dynamodb.model.KeyType
import software.amazon.awssdk.services.dynamodb.model.ResourceNotFoundException
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType
import software.amazon.awssdk.services.dynamodb.model.TableStatus
import java.time.Duration
import java.util.UUID

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OrderControllerLocalStackTest {

    companion object {
        private val TABLE_ACTIVE_TIMEOUT = Duration.ofSeconds(30)
        private val TABLE_ACTIVE_POLL_INTERVAL = Duration.ofMillis(100)

        val awsEmulator: AwsEmulatorServer by lazy { awsEmulator("dynamodb") }

        private fun awsEmulator(vararg services: String): AwsEmulatorServer =
            when (val emulator = System.getProperty("bluetape4k.aws.emulator", "floci").trim().lowercase()) {
                "floci" -> FlociServer.Launcher.floci
                "localstack" -> LocalStackServer.Launcher.getLocalStack(*services)
                else -> error("Unsupported AWS emulator: $emulator. Use floci or localstack.")
            }
    }

    private fun contextRunner(): ApplicationContextRunner =
        ApplicationContextRunner()
            .withConfiguration(
                AutoConfigurations.of(
                    AwsAutoConfiguration::class.java,
                    DynamoDbAutoConfiguration::class.java,
                )
            )
            .withBean(AwsCredentialsProvider::class.java, { awsEmulator.getCredentialProvider() })
            .withPropertyValues(
                "bluetape4k.aws.dynamodb.region=${awsEmulator.regionName}",
                "bluetape4k.aws.dynamodb.endpoint-override=${awsEmulator.awsEndpoint}",
            )

    @Test
    fun `repository supports CRUD and scan`() {
        contextRunner().run { context ->
            val asyncClient = context.getBean(DynamoDbAsyncClient::class.java)
            val enhancedClient = context.getBean(DynamoDbEnhancedAsyncClient::class.java)
            val tableNameResolver = context.getBean(DynamoDbTableNameResolver::class.java)
            val repository = OrderRepository(enhancedClient, tableNameResolver)

            runSuspendIO {
                createOrdersTable(asyncClient, tableNameResolver.resolve("orders"))

                val order = Order(
                    id = "order-${UUID.randomUUID()}",
                    status = "NEW",
                    description = "integration test order",
                )

                repository.save(order)

                val found = repository.findById(order.id)
                found?.id shouldBeEqualTo order.id
                found?.status shouldBeEqualTo order.status

                val all = repository.scan().toList()
                all.any { it.id == order.id }.shouldBeTrue()

                repository.deleteById(order.id)

                repository.findById(order.id).shouldBeNull()
            }
        }
    }

    @Test
    fun `concurrent saves and findById are consistent`() {
        contextRunner().run { context ->
            val asyncClient = context.getBean(DynamoDbAsyncClient::class.java)
            val enhancedClient = context.getBean(DynamoDbEnhancedAsyncClient::class.java)
            val tableNameResolver = context.getBean(DynamoDbTableNameResolver::class.java)
            val repository = OrderRepository(enhancedClient, tableNameResolver)

            runSuspendIO {
                createOrdersTable(asyncClient, tableNameResolver.resolve("orders"))

                SuspendedJobTester()
                    .workers(4)
                    .rounds(3)
                    .add {
                        val order = Order(
                            id = "order-${UUID.randomUUID()}",
                            status = "CONCURRENT",
                            description = "stress test",
                        )
                        repository.save(order)
                        repository.findById(order.id)?.id shouldBeEqualTo order.id
                    }
                    .run()
            }
        }
    }

    @Test
    fun `controller HTTP layer - POST findById and DELETE via WebTestClient`() {
        contextRunner().run { context ->
            val asyncClient = context.getBean(DynamoDbAsyncClient::class.java)
            val enhancedClient = context.getBean(DynamoDbEnhancedAsyncClient::class.java)
            val tableNameResolver = context.getBean(DynamoDbTableNameResolver::class.java)
            val repository = OrderRepository(enhancedClient, tableNameResolver)
            val controller = OrderController(repository)

            runSuspendIO {
                createOrdersTable(asyncClient, tableNameResolver.resolve("orders"))
            }

            val webClient = WebTestClient.bindToController(controller).build()

            val created = webClient.post().uri("/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(OrderRequest(status = "HTTP_TEST", description = "web layer test"))
                .exchange()
                .expectStatus().isCreated
                .expectBody<Order>()
                .returnResult()
                .responseBody
                ?: error("POST /orders returned empty body")

            created.status shouldBeEqualTo "HTTP_TEST"

            val found = requireNotNull(
                webClient.get().uri("/orders/${created.id}")
                    .exchange()
                    .expectStatus().isOk
                    .expectBody<Order>()
                    .returnResult()
                    .responseBody
            ) { "GET /orders/${created.id} returned empty body" }
            found.id shouldBeEqualTo created.id

            webClient.delete().uri("/orders/${created.id}")
                .exchange()
                .expectStatus().isNoContent

            webClient.get().uri("/orders/${created.id}")
                .exchange()
                .expectStatus().isNotFound
        }
    }

    private suspend fun createOrdersTable(client: DynamoDbAsyncClient, tableName: String) {
        val exists = try {
            client.describeTable { it.tableName(tableName) }.await()
            true
        } catch (_: ResourceNotFoundException) {
            false
        }

        if (!exists) {
            client.createTable {
                it.tableName(tableName)
                it.attributeDefinitions(
                    AttributeDefinition.builder()
                        .attributeName("id")
                        .attributeType(ScalarAttributeType.S)
                        .build()
                )
                it.keySchema(
                    KeySchemaElement.builder()
                        .attributeName("id")
                        .keyType(KeyType.HASH)
                        .build()
                )
                it.billingMode(BillingMode.PAY_PER_REQUEST)
            }.await()
        }

        var lastStatus: TableStatus? = null
        try {
            await
                .atMost(TABLE_ACTIVE_TIMEOUT)
                .pollInterval(TABLE_ACTIVE_POLL_INTERVAL)
                .untilSuspending {
                    lastStatus = client.describeTable { it.tableName(tableName) }.await().table().tableStatus()
                    lastStatus == TableStatus.ACTIVE
                }
        } catch (e: ConditionTimeoutException) {
            throw IllegalStateException(
                "Table $tableName did not become ACTIVE within $TABLE_ACTIVE_TIMEOUT " +
                    "(poll interval=$TABLE_ACTIVE_POLL_INTERVAL, last status=$lastStatus).",
                e,
            )
        }
    }
}
