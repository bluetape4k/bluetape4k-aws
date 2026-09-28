package io.bluetape4k.aws.dynamodb.enhanced

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.aws.dynamodb.AbstractDynamodbTest
import io.bluetape4k.idgenerators.uuid.Uuid
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.debug
import kotlinx.coroutines.flow.count
import kotlinx.coroutines.future.await
import org.junit.jupiter.api.Test
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient
import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement
import software.amazon.awssdk.services.dynamodb.model.KeyType
import software.amazon.awssdk.services.dynamodb.model.ProvisionedThroughput
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType
import java.io.Serializable

class EnhancedAsyncClientExtensionsTest: AbstractDynamodbTest() {

    companion object: KLoggingChannel()

    @DynamoDbBean
    data class BatchTestEntity(
        @get:DynamoDbPartitionKey
        var id: String = "",
        var name: String = "",
    ): Serializable

    @Test
    fun `table should create DynamoDbAsyncTable with type`() = runSuspendIO {
        val tableName = "test-table-${Uuid.V7.nextIdAsString()}"

        // 테이블 생성
        asyncClient.createTestTable(tableName)

        val table = enhancedAsyncClient.table<BatchTestEntity>(tableName)

        log.debug { "table=$table" }
        table.tableName() shouldBeEqualTo tableName

        // cleanup
        asyncClient.deleteTable { it.tableName(tableName) }.await()
    }

    @Test
    fun `batchWriteItems should write multiple items in batches`() = runSuspendIO {
        val tableName = "batch-test-table-${Uuid.V7.nextIdAsString()}"

        // 테이블 생성
        asyncClient.createTestTable(tableName)

        val table = enhancedAsyncClient.table<BatchTestEntity>(tableName)
        val items = List(30) {
            BatchTestEntity(Uuid.V7.nextIdAsString(), "Item-$it")
        }

        val resultCount = enhancedAsyncClient.batchWriteItems(table, items).count()
        resultCount shouldBeEqualTo 2 // 30 items / 25 batch size = 2 batches

        // cleanup
        asyncClient.deleteTable { it.tableName(tableName) }.await()
    }

    @Test
    fun `batchWriteItems with chunkSize should respect chunk size`() = runSuspendIO {
        val tableName = "batch-test-table-2-${Uuid.V7.nextIdAsString()}"

        // 테이블 생성
        asyncClient.createTestTable(tableName)

        val table = enhancedAsyncClient.table<BatchTestEntity>(tableName)
        val items = List(20) {
            BatchTestEntity(Uuid.V7.nextIdAsString(), "Item-$it")
        }

        val resultCount = enhancedAsyncClient.batchWriteItems(table, items, chunkSize = 5).count()
        resultCount shouldBeEqualTo 4 // 20 items / 5 chunk size = 4 batches

        // cleanup
        asyncClient.deleteTable { it.tableName(tableName) }.await()
    }

    private suspend fun DynamoDbAsyncClient.createTestTable(tableName: String) {
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
        }.await()
    }
}
