package io.bluetape4k.aws.spring.dynamodb

import io.bluetape4k.aws.dynamodb.model.writeBatchOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.future.await
import kotlinx.coroutines.reactive.asFlow
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbAsyncTable
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedAsyncClient
import software.amazon.awssdk.enhanced.dynamodb.Key
import software.amazon.awssdk.enhanced.dynamodb.TableSchema
import software.amazon.awssdk.enhanced.dynamodb.model.BatchGetItemEnhancedRequest
import software.amazon.awssdk.enhanced.dynamodb.model.BatchGetResultPage
import software.amazon.awssdk.enhanced.dynamodb.model.BatchWriteItemEnhancedRequest
import software.amazon.awssdk.enhanced.dynamodb.model.BatchWriteResult
import software.amazon.awssdk.enhanced.dynamodb.model.ReadBatch
import software.amazon.awssdk.enhanced.dynamodb.model.TransactGetItemsEnhancedRequest
import software.amazon.awssdk.enhanced.dynamodb.model.TransactWriteItemsEnhancedRequest
import java.util.concurrent.ConcurrentHashMap

/**
 * DynamoDB Enhanced Async Client를 coroutine-friendly API로 노출하는 공통 template입니다.
 *
 * table 해석 순서는 `logical name -> DynamoDbTableNameResolver -> physical name`이며,
 * physical name과 entity class 조합으로 만든 async table을 cache합니다. schema는
 * 호출자가 전달한 explicit schema를 먼저 사용하고, 없으면
 * [DynamoDbTableSchemaResolver]의 class별 cache를 사용합니다.
 *
 * ```kotlin
 * val item = template.putItem("orders", Order("order-1"))
 * val loaded = template.getItem("orders", key, Order::class.java)
 * ```
 */
class DynamoDbTemplate(
    private val enhancedClient: DynamoDbEnhancedAsyncClient,
    private val tableNameResolver: DynamoDbTableNameResolver,
    private val schemaResolver: DynamoDbTableSchemaResolver = DefaultDynamoDbTableSchemaResolver(),
) {

    private val tables = ConcurrentHashMap<TableCacheKey, DynamoDbAsyncTable<*>>()

    /** logical table name과 entity class로 typed async table을 얻습니다. */
    @Suppress("UNCHECKED_CAST")
    fun <T : Any> table(
        tableName: String,
        entityClass: Class<T>,
        schema: TableSchema<T>? = null,
    ): DynamoDbAsyncTable<T> {
        require(tableName.isNotBlank()) { "tableName must not be blank." }
        val physicalTableName = tableNameResolver.resolve(tableName)
        if (schema != null) {
            return enhancedClient.table(physicalTableName, schema)
        }

        val cacheKey = TableCacheKey(physicalTableName, entityClass)
        return tables.computeIfAbsent(cacheKey) {
            enhancedClient.table(physicalTableName, schemaResolver.resolve(entityClass))
        } as DynamoDbAsyncTable<T>
    }

    /** reified entity class를 이용하는 [table] 단축 진입점입니다. */
    inline fun <reified T : Any> table(
        tableName: String,
        schema: TableSchema<T>? = null,
    ): DynamoDbAsyncTable<T> = table(tableName, T::class.java, schema)

    /** item을 저장하고 입력 item을 반환합니다. */
    suspend fun <T : Any> putItem(
        tableName: String,
        item: T,
        schema: TableSchema<T>? = null,
    ): T = execute("putItem") {
        table(tableName, item.javaClass, schema).putItem(item).await()
        item
    }

    /** key로 item을 조회합니다. */
    suspend fun <T : Any> getItem(
        tableName: String,
        key: Key,
        entityClass: Class<T>,
        schema: TableSchema<T>? = null,
    ): T? = execute("getItem") {
        table(tableName, entityClass, schema).getItem(key).await()
    }

    /** key로 item을 삭제하고 삭제된 item을 반환합니다. */
    suspend fun <T : Any> deleteItem(
        tableName: String,
        key: Key,
        entityClass: Class<T>,
        schema: TableSchema<T>? = null,
    ): T? = execute("deleteItem") {
        table(tableName, entityClass, schema).deleteItem(key).await()
    }

    /** item을 갱신하고 DynamoDB가 반환한 값을 반환합니다. */
    suspend fun <T : Any> updateItem(
        tableName: String,
        item: T,
        schema: TableSchema<T>? = null,
    ): T? = execute("updateItem") {
        table(tableName, item.javaClass, schema).updateItem(item).await()
    }

    /**
     * 최대 25개씩 Enhanced batch write를 실행합니다.
     *
     * DynamoDB가 반환한 unprocessed item은 숨기지 않고 결과에 남깁니다.
     */
    suspend fun <T : Any> batchWrite(
        tableName: String,
        items: Collection<T>,
        chunkSize: Int = MAX_BATCH_WRITE_ITEMS,
        schema: TableSchema<T>? = null,
    ): DynamoDbBatchWriteResult<T> = execute("batchWrite") {
        require(chunkSize in 1..MAX_BATCH_WRITE_ITEMS) {
            "chunkSize must be between 1 and $MAX_BATCH_WRITE_ITEMS."
        }
        if (items.isEmpty()) return@execute DynamoDbBatchWriteResult(emptyList(), emptyList())

        val table = table(tableName, items.first().javaClass, schema)
        val responses = items.chunked(chunkSize).map { chunk ->
            val request = BatchWriteItemEnhancedRequest.builder()
                .addWriteBatch(writeBatchOf(table, chunk, items.first().javaClass))
                .build()
            enhancedClient.batchWriteItem(request).await()
        }
        val unprocessed = responses.flatMap { response -> response.unprocessedPutItemsForTable(table) }
        DynamoDbBatchWriteResult(responses, unprocessed)
    }

    /** 최대 100개씩 Enhanced batch get을 실행하고 page/unprocessed key를 함께 반환합니다. */
    suspend fun <T : Any> batchGet(
        tableName: String,
        keys: Collection<Key>,
        entityClass: Class<T>,
        chunkSize: Int = MAX_BATCH_GET_ITEMS,
        schema: TableSchema<T>? = null,
    ): DynamoDbBatchGetResult<T> = execute("batchGet") {
        require(chunkSize in 1..MAX_BATCH_GET_ITEMS) {
            "chunkSize must be between 1 and $MAX_BATCH_GET_ITEMS."
        }
        if (keys.isEmpty()) return@execute DynamoDbBatchGetResult(emptyList(), emptyList(), emptyList())

        val table = table(tableName, entityClass, schema)
        val pages = keys.chunked(chunkSize).flatMap { chunk ->
            val readBatch = ReadBatch.builder(entityClass)
                .mappedTableResource(table)
                .apply { chunk.forEach { addGetItem(it) } }
                .build()
            val request = BatchGetItemEnhancedRequest.builder()
                .addReadBatch(readBatch)
                .build()
            enhancedClient.batchGetItem(request).asFlow().toList()
        }
        val items = pages.flatMap { it.resultsForTable(table) }
        val unprocessedKeys = pages.flatMap { it.unprocessedKeysForTable(table) }
        DynamoDbBatchGetResult(items, pages, unprocessedKeys)
    }

    /** builder에서 조건식/put/update/delete를 조합해 atomic transaction을 실행합니다. */
    suspend fun transactWrite(
        builder: TransactWriteItemsEnhancedRequest.Builder.() -> Unit,
    ) = execute("transactWrite") {
        enhancedClient.transactWriteItems(
            TransactWriteItemsEnhancedRequest.builder().apply(builder).build()
        ).await()
    }

    /** typed table을 builder에서 조합해 transaction get을 실행합니다. */
    suspend fun transactGet(
        builder: TransactGetItemsEnhancedRequest.Builder.() -> Unit,
    ) = execute("transactGet") {
        enhancedClient.transactGetItems(
            TransactGetItemsEnhancedRequest.builder().apply(builder).build()
        ).await()
    }

    private suspend fun <T> execute(operation: String, block: suspend () -> T): T =
        try {
            block()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            throw DynamoDbTemplateException(operation, error)
        }

    private data class TableCacheKey(
        val physicalTableName: String,
        val entityClass: Class<*>,
    )

    companion object {
        const val MAX_BATCH_WRITE_ITEMS: Int = 25
        const val MAX_BATCH_GET_ITEMS: Int = 100
    }
}

/** batch write에서 반환된 partial failure를 호출자에게 보존합니다. */
data class DynamoDbBatchWriteResult<T>(
    val responses: List<BatchWriteResult>,
    val unprocessedItems: List<T>,
)

/** batch get page와 unprocessed key를 함께 표현합니다. */
data class DynamoDbBatchGetResult<T>(
    val items: List<T>,
    val pages: List<BatchGetResultPage>,
    val unprocessedKeys: List<Key>,
)

/** DynamoDB template 작업 실패를 operation과 원인과 함께 표현합니다. */
class DynamoDbTemplateException(
    val operation: String,
    cause: Throwable,
) : RuntimeException("DynamoDB $operation failed.", cause)
