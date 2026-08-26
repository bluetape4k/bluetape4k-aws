package io.bluetape4k.aws.spring.dynamodb

import io.bluetape4k.aws.spring.AwsAutoConfiguration
import io.bluetape4k.aws.spring.test.AwsSpringBootTestEmulator
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.testcontainers.aws.getCredentialProvider
import io.bluetape4k.junit5.coroutines.runSuspendIO
import kotlinx.coroutines.future.await
import org.awaitility.kotlin.await
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider
import software.amazon.awssdk.enhanced.dynamodb.Key
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient
import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement
import software.amazon.awssdk.services.dynamodb.model.KeyType
import software.amazon.awssdk.services.dynamodb.model.ProvisionedThroughput
import software.amazon.awssdk.services.dynamodb.model.ResourceNotFoundException
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType
import software.amazon.awssdk.services.dynamodb.model.TableStatus
import java.time.Duration

class DynamoDbTemplateAwsEmulatorTest {

    companion object {
        private const val TABLE_NAME = "template-items"
        private const val TABLE_PREFIX = "spring-dynamodb-"
        private val awsEmulator by lazy {
            AwsSpringBootTestEmulator.get("dynamodb")
        }
    }

    @Test
    fun `template supports typed crud batch and transaction operations`() {
        contextRunner().run { context ->
            val asyncClient = context.getBean(DynamoDbAsyncClient::class.java)
            val template = context.getBean(DynamoDbTemplate::class.java)
            val tableNameResolver = context.getBean(DynamoDbTableNameResolver::class.java)
            val actualTableName = tableNameResolver.resolve(TABLE_NAME)

            runSuspendIO {
                createTable(asyncClient, actualTableName)

                val first = Item("one", "NEW")
                template.putItem(TABLE_NAME, first) shouldBeEqualTo first
                template.getItem(
                    TABLE_NAME,
                    Key.builder().partitionValue(first.id).build(),
                    Item::class.java,
                )?.state shouldBeEqualTo "NEW"

                first.state = "READY"
                template.updateItem(TABLE_NAME, first)?.state shouldBeEqualTo "READY"

                val batchResult = template.batchWrite(
                    TABLE_NAME,
                    listOf(Item("two", "NEW"), Item("three", "NEW")),
                )
                batchResult.responses.size shouldBeEqualTo 1
                batchResult.unprocessedItems shouldBeEqualTo emptyList()

                val batchGetResult = template.batchGet(
                    TABLE_NAME,
                    listOf("one", "two", "three").map { Key.builder().partitionValue(it).build() },
                    Item::class.java,
                )
                batchGetResult.items.map { it.id }.toSet() shouldBeEqualTo setOf("one", "two", "three")
                batchGetResult.unprocessedKeys shouldBeEqualTo emptyList()

                val transactionItem = Item("transaction", "COMMITTED")
                template.transactWrite {
                    addPutItem(template.table(TABLE_NAME, Item::class.java), transactionItem)
                }
                val transactionResult = template.transactGet {
                    addGetItem(
                        template.table(TABLE_NAME, Item::class.java),
                        Key.builder().partitionValue(transactionItem.id).build(),
                    )
                }
                transactionResult.size shouldBeEqualTo 1

                template.deleteItem(
                    TABLE_NAME,
                    Key.builder().partitionValue(first.id).build(),
                    Item::class.java,
                )?.state shouldBeEqualTo "READY"
                template.getItem(
                    TABLE_NAME,
                    Key.builder().partitionValue(first.id).build(),
                    Item::class.java,
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
                it.attributeDefinitions(
                    AttributeDefinition.builder()
                        .attributeName("id")
                        .attributeType(ScalarAttributeType.S)
                        .build(),
                )
                it.keySchema(
                    KeySchemaElement.builder().attributeName("id").keyType(KeyType.HASH).build(),
                )
                it.provisionedThroughput(
                    ProvisionedThroughput.builder()
                        .readCapacityUnits(5)
                        .writeCapacityUnits(5)
                        .build(),
                )
            }.await()
        }
        await.atMost(Duration.ofSeconds(20)).until {
            client.describeTable { it.tableName(tableName) }
                .get()
                .table()
                .tableStatus() == TableStatus.ACTIVE
        }
    }

    @DynamoDbBean
    class Item() {
        @get:DynamoDbPartitionKey
        var id: String = ""
        var state: String = ""

        constructor(id: String, state: String) : this() {
            this.id = id
            this.state = state
        }
    }
}
