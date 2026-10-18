package io.bluetape4k.aws.spring.dynamodb

import io.bluetape4k.assertions.shouldBeEmpty
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.aws.dynamodb.model.attributeDefinitionOf
import io.bluetape4k.aws.dynamodb.model.keyOf
import io.bluetape4k.aws.dynamodb.model.keySchemaElementOf
import io.bluetape4k.aws.dynamodb.model.provisionedThroughputOf
import io.bluetape4k.aws.spring.AwsAutoConfiguration
import io.bluetape4k.aws.spring.test.AwsSpringBootTestEmulator
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.logging.KLogging
import io.bluetape4k.testcontainers.aws.getCredentialProvider
import kotlinx.coroutines.future.await
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.getBean
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider
import software.amazon.awssdk.enhanced.dynamodb.Key
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient
import software.amazon.awssdk.services.dynamodb.model.KeyType
import software.amazon.awssdk.services.dynamodb.model.ResourceNotFoundException
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType

class DynamoDbTemplateAwsEmulatorTest {

    companion object: KLogging() {
        private const val TABLE_NAME = "template-items"
        private const val TABLE_PREFIX = "spring-dynamodb-"

        private val awsEmulator by lazy {
            AwsSpringBootTestEmulator.get("dynamodb")
        }
    }

    @Test
    fun `template supports typed crud batch and transaction operations`() {
        contextRunner().run { context ->
            val asyncClient = context.getBean<DynamoDbAsyncClient>()
            val template = context.getBean<DynamoDbTemplate>()
            val tableNameResolver = context.getBean<DynamoDbTableNameResolver>()
            val actualTableName = tableNameResolver.resolve(TABLE_NAME)

            runSuspendIO {
                createTable(asyncClient, actualTableName)

                val first = Item("one", "NEW")
                template.putItem(TABLE_NAME, first) shouldBeEqualTo first
                template.getItem<Item>(
                    TABLE_NAME,
                    keyOf(first.id),
                )?.state shouldBeEqualTo "NEW"

                first.state = "READY"
                template.updateItem(TABLE_NAME, first)?.state shouldBeEqualTo "READY"

                val batchResult = template.batchWrite(
                    TABLE_NAME,
                    listOf(Item("two", "NEW"), Item("three", "NEW")),
                )
                batchResult.responses.size shouldBeEqualTo 1
                batchResult.unprocessedItems.shouldBeEmpty()

                val batchGetResult = template.batchGet<Item>(
                    TABLE_NAME,
                    listOf("one", "two", "three").map { Key.builder().partitionValue(it).build() },
                )
                batchGetResult.items.map { it.id }.toSet() shouldBeEqualTo setOf("one", "two", "three")
                batchGetResult.unprocessedKeys.shouldBeEmpty()

                val transactionItem = Item("transaction", "COMMITTED")
                template.transactWrite {
                    addPutItem(template.table<Item>(TABLE_NAME), transactionItem)
                }
                val transactionResult = template.transactGet {
                    addGetItem(
                        template.table<Item>(TABLE_NAME),
                        keyOf(transactionItem.id),
                    )
                }
                transactionResult.size shouldBeEqualTo 1

                template.deleteItem<Item>(
                    TABLE_NAME,
                    Key.builder().partitionValue(first.id).build(),
                )?.state shouldBeEqualTo "READY"

                template.getItem<Item>(
                    TABLE_NAME,
                    keyOf(first.id),
                ).shouldBeNull()
            }
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

    private suspend fun createTable(client: DynamoDbAsyncClient, tableName: String) {
        val exists = try {
            client.describeTable { it.tableName(tableName) }.await()
            true
        } catch (_: ResourceNotFoundException) {
            false
        }
        if (!exists) {
            client.createTable {
                it.tableName(tableName)
                it.attributeDefinitions(attributeDefinitionOf("id", ScalarAttributeType.S))
                it.keySchema(keySchemaElementOf("id", KeyType.HASH))
                it.provisionedThroughput(provisionedThroughputOf(5, 5))
            }.await()
        }

        client.waiter().waitUntilTableExists { it.tableName(tableName) }
    }

    @DynamoDbBean
    class Item() {
        @get:DynamoDbPartitionKey
        var id: String = ""
        var state: String = ""

        constructor(id: String, state: String): this() {
            this.id = id
            this.state = state
        }
    }
}
