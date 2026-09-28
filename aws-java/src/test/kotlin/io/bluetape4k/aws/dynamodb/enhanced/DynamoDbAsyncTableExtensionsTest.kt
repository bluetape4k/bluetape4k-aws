package io.bluetape4k.aws.dynamodb.enhanced

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.aws.dynamodb.AbstractDynamodbTest
import io.bluetape4k.idgenerators.uuid.Uuid
import io.bluetape4k.junit5.awaitility.untilSuspending
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.debug
import kotlinx.coroutines.future.await
import org.awaitility.kotlin.atMost
import org.awaitility.kotlin.await
import org.awaitility.kotlin.withPollInterval
import org.junit.jupiter.api.Test
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbAsyncTable
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient
import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement
import software.amazon.awssdk.services.dynamodb.model.KeyType
import software.amazon.awssdk.services.dynamodb.model.ProvisionedThroughput
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType
import software.amazon.awssdk.services.dynamodb.model.TableStatus
import java.io.Serializable
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class DynamoDbAsyncTableExtensionsTest: AbstractDynamodbTest() {

    companion object: KLoggingChannel()

    @Test
    fun `getItem by partition key should return item`() = runSuspendIO {
        val tableName = "async-test-${Uuid.V7.nextIdAsString()}"
        val table = enhancedAsyncClient.table<TestEntity>(tableName)

        // 테이블 생성
        asyncClient.createTestTable(tableName)

        // 테이블 활성화 대기
        waitUntilTableActive(tableName)

        val entity = TestEntity(Uuid.V7.nextIdAsString(), "John", 30)
        table.putItem(entity)
        waitUntilItemExists(table, entity.id)

        val result = table.getItem(entity.id).shouldNotBeNull()
        log.debug { "result=$result" }
        result shouldBeEqualTo entity

        // cleanup
        asyncClient.deleteTable { it.tableName(tableName) }.await()
    }

    @Test
    fun `getItem with non-existent key should return null`() = runSuspendIO {
        val tableName = "async-test-2-${Uuid.V7.nextIdAsString()}"
        val table = enhancedAsyncClient.table<TestEntity>(tableName)

        // 테이블 생성
        asyncClient.createTestTable(tableName)

        waitUntilTableActive(tableName)

        // 존재하지 않는 Id
        table.getItem("non-existent-id").shouldBeNull()

        // cleanup
        asyncClient.deleteTable { it.tableName(tableName) }.await()
    }

    @Test
    fun `deleteItem should remove item`() = runSuspendIO {
        val tableName = "async-test-3-${Uuid.V7.nextIdAsString()}"
        val table = enhancedAsyncClient.table<TestEntity>(tableName)

        // 테이블 생성
        asyncClient.createTestTable(tableName)

        waitUntilTableActive(tableName)

        val entity = TestEntity(Uuid.V7.nextIdAsString(), "Jane", 25)
        table.putItem(entity)
        waitUntilItemExists(table, entity.id)

        // 삭제 전 엔티티는 존재한다.
        val beforeDelete = table.getItem(entity.id).shouldNotBeNull()
        log.debug { "beforeDelete=$beforeDelete" }

        // 삭제된 엔티티 
        val deleted = table.deleteItem(entity.id).shouldNotBeNull()
        log.debug { "deleted=$deleted" }
        deleted.id shouldBeEqualTo entity.id

        waitUntilItemMissing(table, entity.id)

        // 삭제 후 조회
        table.getItem(entity.id).shouldBeNull()

        // cleanup
        asyncClient.deleteTable { it.tableName(tableName) }.await()
    }

    @Test
    fun `exists should return true for existing item`() = runSuspendIO {
        val tableName = "async-test-5-${Uuid.V7.nextIdAsString()}"
        val table = enhancedAsyncClient.table<TestEntity>(tableName)

        // 테이블 생성
        asyncClient.createTestTable(tableName)
        waitUntilTableActive(tableName)

        val entity = TestEntity(Uuid.V7.nextIdAsString(), "Test", 30)
        table.putItem(entity)
        waitUntilItemExists(table, entity.id)

        table.exists(entity.id).shouldBeTrue()

        // cleanup
        asyncClient.deleteTable { it.tableName(tableName) }.await()
    }

    @Test
    fun `exists should return false for non-existing item`() = runSuspendIO {
        val tableName = "async-test-6-${Uuid.V7.nextIdAsString()}"
        val table = enhancedAsyncClient.table<TestEntity>(tableName)

        // 테이블 생성
        asyncClient.createTestTable(tableName)

        waitUntilTableActive(tableName)

        table.exists("non-existent").shouldBeFalse()

        // cleanup
        asyncClient.deleteTable { it.tableName(tableName) }.await()
    }

    @DynamoDbBean
    data class TestEntity(
        @get:DynamoDbPartitionKey
        var id: String = "",
        var name: String = "",
        var age: Int = 0,
    ): Serializable

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

    private suspend fun waitUntilTableActive(tableName: String) {
        await atMost 10.seconds withPollInterval 1.seconds untilSuspending {
            try {
                asyncClient
                    .describeTable { it.tableName(tableName) }
                    .await()
                    .table()
                    .tableStatus() == TableStatus.ACTIVE
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                false
            }
        }
    }

    private suspend fun waitUntilItemExists(table: DynamoDbAsyncTable<TestEntity>, id: String) {
        await atMost 10.seconds withPollInterval 500.milliseconds untilSuspending {
            table.getItem(id) != null
        }
    }

    private suspend fun waitUntilItemMissing(table: DynamoDbAsyncTable<TestEntity>, id: String) {
        await atMost 10.seconds withPollInterval 500.milliseconds untilSuspending {
            table.getItem(id) == null
        }
    }
}
