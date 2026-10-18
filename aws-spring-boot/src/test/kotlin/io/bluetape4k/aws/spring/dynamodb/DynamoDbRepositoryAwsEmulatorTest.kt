package io.bluetape4k.aws.spring.dynamodb

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldContainAll
import io.bluetape4k.aws.dynamodb.model.attributeDefinitionOf
import io.bluetape4k.aws.dynamodb.model.keySchemaElementOf
import io.bluetape4k.aws.spring.AwsAutoConfiguration
import io.bluetape4k.aws.spring.test.AwsSpringBootTestEmulator
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.logging.KLogging
import io.bluetape4k.support.hashOf
import io.bluetape4k.testcontainers.aws.getCredentialProvider
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.future.await
import org.junit.jupiter.api.Test
import org.junit.platform.commons.util.ToStringBuilder
import org.springframework.beans.factory.getBean
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedAsyncClient
import software.amazon.awssdk.enhanced.dynamodb.Key
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSecondaryPartitionKey
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSecondarySortKey
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSortKey
import software.amazon.awssdk.enhanced.dynamodb.model.QueryConditional
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient
import software.amazon.awssdk.services.dynamodb.model.GlobalSecondaryIndex
import software.amazon.awssdk.services.dynamodb.model.KeyType
import software.amazon.awssdk.services.dynamodb.model.Projection
import software.amazon.awssdk.services.dynamodb.model.ProjectionType
import software.amazon.awssdk.services.dynamodb.model.ProvisionedThroughput
import software.amazon.awssdk.services.dynamodb.model.ResourceNotFoundException
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType
import java.io.Serializable
import java.util.*

class DynamoDbRepositoryAwsEmulatorTest {

    companion object: KLogging() {
        private const val TABLE_NAME = "orders"
        private const val TABLE_PREFIX = "spring-dynamodb-"
        private const val CUSTOMER_INDEX = "customer-createdAt-index"
        private val awsEmulator by lazy {
            AwsSpringBootTestEmulator.get("dynamodb")
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
                "bluetape4k.aws.dynamodb.table-prefix=$TABLE_PREFIX",
            )

    @Test
    fun `repository supports CRUD scan query and index query`() {
        contextRunner().run { context ->
            val asyncClient = context.getBean<DynamoDbAsyncClient>()
            val enhancedClient = context.getBean<DynamoDbEnhancedAsyncClient>()
            val tableNameResolver = context.getBean<DynamoDbTableNameResolver>()
            val repository = OrderRepository(enhancedClient, tableNameResolver)
            val actualTableName = tableNameResolver.resolve(TABLE_NAME)

            runSuspendIO {
                createOrdersTable(asyncClient, actualTableName)

                val order1 = OrderDocument(
                    orderId = "order-${UUID.randomUUID()}",
                    createdAt = "2026-05-10T19:30:00Z",
                    customerId = "customer-a",
                    status = "NEW",
                )
                val order2 = OrderDocument(
                    orderId = order1.orderId,
                    createdAt = "2026-05-10T19:31:00Z",
                    customerId = "customer-a",
                    status = "PAID",
                )
                val order3 = OrderDocument(
                    orderId = "order-${UUID.randomUUID()}",
                    createdAt = "2026-05-10T19:32:00Z",
                    customerId = "customer-b",
                    status = "NEW",
                )

                repository.save(order1)
                repository.save(order2)
                repository.save(order3)

                repository.findById(OrderId(order1.orderId, order1.createdAt)) shouldBeEqualTo order1
                repository.existsById(OrderId(order1.orderId, order1.createdAt)).shouldBeTrue()

                order1.status = "SHIPPED"
                repository.update(order1)?.status shouldBeEqualTo "SHIPPED"

                repository.findByOrder(order1.orderId).toList().map { it.createdAt } shouldBeEqualTo
                        listOf(order1.createdAt, order2.createdAt)
                repository.findByCustomer("customer-a").toList().map { it.orderId } shouldContainAll
                        listOf(order1.orderId, order2.orderId)
                repository.scan().toList().map { it.orderId } shouldContainAll
                        listOf(order1.orderId, order2.orderId, order3.orderId)

                repository.deleteById(OrderId(order1.orderId, order1.createdAt))?.status shouldBeEqualTo "SHIPPED"
                repository.findById(OrderId(order1.orderId, order1.createdAt)).shouldBeNull()

                repository.delete(order2)?.status shouldBeEqualTo "PAID"
                repository.findById(OrderId(order2.orderId, order2.createdAt)).shouldBeNull()
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
                    attributeDefinitionOf("orderId", ScalarAttributeType.S),
                    attributeDefinitionOf("createdAt", ScalarAttributeType.S),
                    attributeDefinitionOf("customerId", ScalarAttributeType.S),
                )
                it.keySchema(
                    keySchemaElementOf("orderId", KeyType.HASH),
                    keySchemaElementOf("createdAt", KeyType.RANGE),
                )
                it.globalSecondaryIndexes(
                    GlobalSecondaryIndex.builder()
                        .indexName(CUSTOMER_INDEX)
                        .keySchema(
                            keySchemaElementOf("customerId", KeyType.HASH),
                            keySchemaElementOf("createdAt", KeyType.RANGE),
                        )
                        .projection(Projection.builder().projectionType(ProjectionType.ALL).build())
                        .provisionedThroughput(provisionedThroughput())
                        .build()
                )
                it.provisionedThroughput(provisionedThroughput())
            }.await()
        }

        client.waiter().waitUntilTableExists { it.tableName(tableName) }
    }

    private fun provisionedThroughput(): ProvisionedThroughput =
        ProvisionedThroughput.builder()
            .readCapacityUnits(5)
            .writeCapacityUnits(5)
            .build()

    data class OrderId(
        val orderId: String,
        val createdAt: String,
    ): Serializable {
        companion object {
            private const val serialVersionUID: Long = 1L
        }
    }

    private class OrderRepository(
        enhancedClient: DynamoDbEnhancedAsyncClient,
        tableNameResolver: DynamoDbTableNameResolver,
    ): AbstractCoroutinesDynamoDbRepository<OrderDocument, OrderId>(
        enhancedClient = enhancedClient,
        tableNameResolver = tableNameResolver,
        entityClass = OrderDocument::class.java,
    ) {
        override val tableName: String = TABLE_NAME

        override fun keyFromId(id: OrderId): Key =
            Key.builder()
                .partitionValue(id.orderId)
                .sortValue(id.createdAt)
                .build()

        override fun keyFromItem(item: OrderDocument): Key =
            keyFromId(OrderId(item.orderId, item.createdAt))

        fun findByOrder(orderId: String) =
            query(QueryConditional.keyEqualTo { it.partitionValue(orderId) })

        fun findByCustomer(customerId: String) =
            queryIndex(CUSTOMER_INDEX, QueryConditional.keyEqualTo { it.partitionValue(customerId) })
    }

    @DynamoDbBean
    class OrderDocument {
        @get:DynamoDbPartitionKey
        var orderId: String = ""

        @get:DynamoDbSortKey
        @get:DynamoDbSecondarySortKey(indexNames = [CUSTOMER_INDEX])
        var createdAt: String = ""

        @get:DynamoDbSecondaryPartitionKey(indexNames = [CUSTOMER_INDEX])
        var customerId: String = ""

        var status: String = ""

        constructor()

        constructor(
            orderId: String,
            createdAt: String,
            customerId: String,
            status: String,
        ) {
            this.orderId = orderId
            this.createdAt = createdAt
            this.customerId = customerId
            this.status = status
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            return other is OrderDocument &&
                    orderId == other.orderId &&
                    createdAt == other.createdAt &&
                    customerId == other.customerId &&
                    status == other.status
        }

        override fun hashCode(): Int =
            hashOf(orderId, createdAt, customerId, status)

        override fun toString(): String =
            ToStringBuilder(this)
                .append("orderId", orderId)
                .append("createdAt", createdAt)
                .append("customerId", customerId)
                .append("status", status)
                .toString()
    }
}
