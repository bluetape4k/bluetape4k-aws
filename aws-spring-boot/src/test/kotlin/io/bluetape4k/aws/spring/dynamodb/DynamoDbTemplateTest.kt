package io.bluetape4k.aws.spring.dynamodb

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeSameInstanceAs
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.CancellationException
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbAsyncTable
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClientExtension
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedAsyncClient
import software.amazon.awssdk.enhanced.dynamodb.Key
import software.amazon.awssdk.enhanced.dynamodb.TableSchema
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey
import software.amazon.awssdk.enhanced.dynamodb.model.BatchWriteResult
import software.amazon.awssdk.enhanced.dynamodb.model.BatchWriteItemEnhancedRequest
import software.amazon.awssdk.enhanced.dynamodb.model.TransactWriteItemsEnhancedRequest
import software.amazon.awssdk.services.dynamodb.model.AttributeValue
import software.amazon.awssdk.services.dynamodb.model.PutRequest
import software.amazon.awssdk.services.dynamodb.model.WriteRequest
import java.util.concurrent.CompletableFuture
import kotlin.test.assertFailsWith

class DynamoDbTemplateTest {

    private val client = mockk<DynamoDbEnhancedAsyncClient>()
    private val table = mockk<DynamoDbAsyncTable<Item>>(relaxed = true)
    private val tableSchema = TableSchema.fromBean(Item::class.java)
    private val extension = object : DynamoDbEnhancedClientExtension {}
    private val resolver = DynamoDbTableNameResolver { "test-$it" }
    private lateinit var template: DynamoDbTemplate

    @BeforeEach
    fun setUp() {
        every { client.table("test-items", any<TableSchema<Item>>()) } returns table
        every { table.tableName() } returns "test-items"
        every { table.tableSchema() } returns tableSchema
        every { table.mapperExtension() } returns extension
        template = DynamoDbTemplate(client, resolver, DefaultDynamoDbTableSchemaResolver())
    }

    @Test
    fun `table caches the resolved physical table`() {
        val first = template.table("items", Item::class.java)
        val second = template.table("items", Item::class.java)

        first shouldBeSameInstanceAs second
    }

    @Test
    fun `put and get use the resolved table`() = runTest {
        val item = Item("item-1")
        every { table.putItem(item) } returns CompletableFuture.completedFuture(null)
        every { table.getItem(Key.builder().partitionValue("item-1").build()) } returns
            CompletableFuture.completedFuture(item)

        template.putItem("items", item) shouldBeEqualTo item
        template.getItem("items", Key.builder().partitionValue("item-1").build(), Item::class.java) shouldBeEqualTo item
    }

    @Test
    fun `batch write returns unprocessed items instead of hiding partial failure`() = runTest {
        val unprocessed = WriteRequest.builder()
            .putRequest(
                PutRequest.builder()
                    .item(mapOf("id" to AttributeValue.fromS("item-1")))
                    .build()
            )
            .build()
        val response = BatchWriteResult.builder()
            .unprocessedRequests(mapOf("test-items" to listOf(unprocessed)))
            .build()
        every { client.batchWriteItem(any<BatchWriteItemEnhancedRequest>()) } returns
            CompletableFuture.completedFuture(response)

        val result = template.batchWrite("items", listOf<Item>(Item("item-1")))

        result.responses.size shouldBeEqualTo 1
        result.unprocessedItems.map { it.id } shouldBeEqualTo listOf("item-1")
    }

    @Test
    fun `transaction errors retain the original cause`() = runTest {
        val failure = IllegalStateException("conditional failure")
        every { client.transactWriteItems(any<TransactWriteItemsEnhancedRequest>()) } returns
            CompletableFuture.failedFuture(failure)

        val error = assertFailsWith<DynamoDbTemplateException> {
            template.transactWrite { }
        }

        error.operation shouldBeEqualTo "transactWrite"
        error.cause shouldBeSameInstanceAs failure
    }

    @Test
    fun `transaction cancellation is not wrapped`() = runTest {
        val cancellation = CancellationException("cancelled")
        every { client.transactWriteItems(any<TransactWriteItemsEnhancedRequest>()) } returns
            CompletableFuture.failedFuture(cancellation)

        assertFailsWith<CancellationException> {
            template.transactWrite { }
        }
    }

    @DynamoDbBean
    class Item() {
        @get:DynamoDbPartitionKey
        var id: String = ""

        constructor(id: String) : this() {
            this.id = id
        }
    }
}
