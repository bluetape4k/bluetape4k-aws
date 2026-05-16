package io.bluetape4k.aws.examples.spring.dynamodb

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.aws.spring.AwsAutoConfiguration
import io.bluetape4k.aws.spring.dynamodb.DynamoDbAutoConfiguration
import io.bluetape4k.aws.spring.dynamodb.DynamoDbTableNameResolver
import io.bluetape4k.junit5.coroutines.SuspendedJobTester
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.testcontainers.aws.LocalStackServer
import io.bluetape4k.testcontainers.aws.getCredentialProvider
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.future.await
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
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
        @Suppress("DEPRECATION")
        val localStack: LocalStackServer by lazy {
            LocalStackServer.Launcher.getLocalStack("dynamodb")
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
            .withBean(AwsCredentialsProvider::class.java, { localStack.getCredentialProvider() })
            .withPropertyValues(
                "bluetape4k.aws.dynamodb.region=${localStack.regionName}",
                "bluetape4k.aws.dynamodb.endpoint-override=${localStack.awsEndpoint}",
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

        val deadline = System.currentTimeMillis() + Duration.ofSeconds(30).toMillis()
        while (System.currentTimeMillis() < deadline) {
            val status = client.describeTable { it.tableName(tableName) }.await().table().tableStatus()
            if (status == TableStatus.ACTIVE) return
            delay(500)
        }
        error("Table $tableName did not become ACTIVE within 30s")
    }
}
