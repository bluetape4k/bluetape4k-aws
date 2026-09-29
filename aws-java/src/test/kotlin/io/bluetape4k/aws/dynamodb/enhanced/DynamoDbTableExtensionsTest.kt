package io.bluetape4k.aws.dynamodb.enhanced

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.aws.dynamodb.AbstractDynamodbTest
import io.bluetape4k.idgenerators.uuid.Uuid
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.debug
import org.junit.jupiter.api.Test
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey
import software.amazon.awssdk.services.dynamodb.DynamoDbClient
import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement
import software.amazon.awssdk.services.dynamodb.model.KeyType
import software.amazon.awssdk.services.dynamodb.model.ProvisionedThroughput
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType
import java.io.Serializable

class DynamoDbTableExtensionsTest: AbstractDynamodbTest() {

    companion object: KLoggingChannel()


    @Test
    fun `getItem by partition key should return item`() = runSuspendIO {
        val tableName = "sync-test-${Uuid.V7.nextIdAsString()}"

        // 테이블 생성
        client.createTestTable(tableName)

        // EnhancedClient는 동기 버전을 생성
        val enhancedClient = DynamoDbEnhancedClient
            .builder()
            .dynamoDbClient(client)
            .build()
        val table = enhancedClient.table<TestEntity>(tableName)

        val entity = TestEntity(Uuid.V7.nextIdAsString(), "John", 30)
        table.putItem(entity)

        val result = table.getItem(entity.id).shouldNotBeNull()
        log.debug { "result=$result" }
        result shouldBeEqualTo entity

        // cleanup
        client.deleteTable { it.tableName(tableName) }
    }

    @Test
    fun `getItem with non-existent key should return null`() = runSuspendIO {
        val tableName = "sync-test-2-${Uuid.V7.nextIdAsString()}"

        // 테이블 생성
        client.createTestTable(tableName)

        val enhancedClient = DynamoDbEnhancedClient
            .builder()
            .dynamoDbClient(client)
            .build()
        val table = enhancedClient.table<TestEntity>(tableName)

        table.getItem("non-existent-id").shouldBeNull()

        // cleanup
        client.deleteTable { it.tableName(tableName) }
    }

    @Test
    fun `deleteItem should remove item`() = runSuspendIO {
        val tableName = "sync-test-3-${Uuid.V7.nextIdAsString()}"

        // 테이블 생성
        client.createTestTable(tableName)

        val enhancedClient = DynamoDbEnhancedClient
            .builder()
            .dynamoDbClient(client)
            .build()
        val table = enhancedClient.table<TestEntity>(tableName)

        val entity = TestEntity(Uuid.V7.nextIdAsString(), "Jane", 25)
        table.putItem(entity)

        table.getItem(entity.id).shouldNotBeNull()

        val deleted = table.deleteItem(entity.id).shouldNotBeNull()
        deleted.id shouldBeEqualTo entity.id

        // 삭제 후 조회 
        table.getItem(entity.id).shouldBeNull()

        // cleanup
        client.deleteTable { it.tableName(tableName) }
    }

    @Test
    fun `findAll should return all items`() = runSuspendIO {
        val tableName = "sync-test-4-${Uuid.V7.nextIdAsString()}"

        // 테이블 생성
        client.createTestTable(tableName)

        val enhancedClient = DynamoDbEnhancedClient
            .builder()
            .dynamoDbClient(client)
            .build()
        val table = enhancedClient.table<TestEntity>(tableName)

        val entities = List(5) {
            TestEntity(Uuid.V7.nextIdAsString(), "User-$it", 20 + it)
        }
        entities.forEach { table.putItem(it) }

        val results = table.findAll()

        results.size shouldBeEqualTo entities.size
        results.map { it.id }.toSet() shouldBeEqualTo entities.map { it.id }.toSet()

        // cleanup
        client.deleteTable { it.tableName(tableName) }
    }

    @Test
    fun `toList should convert PageIterable to List`() = runSuspendIO {
        val tableName = "sync-test-5-${Uuid.V7.nextIdAsString()}"

        // 테이블 생성
        client.createTestTable(tableName)

        val enhancedClient = DynamoDbEnhancedClient
            .builder()
            .dynamoDbClient(client)
            .build()
        val table = enhancedClient.table<TestEntity>(tableName)

        val entities = List(3) {
            TestEntity(Uuid.V7.nextIdAsString(), "User-$it", 20 + it)
        }
        entities.forEach { table.putItem(it) }

        val results = table.scan().toList()
        results shouldBeEqualTo entities

        // cleanup
        client.deleteTable { it.tableName(tableName) }
    }

    @DynamoDbBean
    data class TestEntity(
        @get:DynamoDbPartitionKey
        var id: String = "",
        var name: String = "",
        var age: Int = 0,
    ): Serializable {
        companion object {
            private const val serialVersionUID: Long = 1L
        }
    }

    fun DynamoDbClient.createTestTable(tableName: String) {
        createTable { builder ->
            builder.tableName(tableName)
            builder.attributeDefinitions(
                AttributeDefinition
                    .builder()
                    .attributeName("id")
                    .attributeType(ScalarAttributeType.S)
                    .build(),
            )
            builder.keySchema(
                KeySchemaElement
                    .builder()
                    .attributeName("id")
                    .keyType(KeyType.HASH)
                    .build(),
            )
            builder.provisionedThroughput(
                ProvisionedThroughput
                    .builder()
                    .readCapacityUnits(5L)
                    .writeCapacityUnits(5L)
                    .build(),
            )
        }
    }
}
