package io.bluetape4k.aws.spring.dynamodb

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.future.await
import kotlinx.coroutines.reactive.asFlow
import software.amazon.awssdk.core.async.SdkPublisher
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbAsyncTable
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedAsyncClient
import software.amazon.awssdk.enhanced.dynamodb.Key
import software.amazon.awssdk.enhanced.dynamodb.TableSchema
import software.amazon.awssdk.enhanced.dynamodb.model.Page
import software.amazon.awssdk.enhanced.dynamodb.model.QueryConditional
import software.amazon.awssdk.enhanced.dynamodb.model.QueryEnhancedRequest
import software.amazon.awssdk.enhanced.dynamodb.model.ScanEnhancedRequest

/**
 * Base coroutine DynamoDB repository implementation for `@DynamoDbBean` entities.
 */
abstract class AbstractCoroutinesDynamoDbRepository<T: Any, ID: Any>(
    private val enhancedClient: DynamoDbEnhancedAsyncClient,
    private val tableNameResolver: DynamoDbTableNameResolver,
    private val entityClass: Class<T>,
): CoroutinesDynamoDbRepository<T, ID> {

    /**
     * Table schema used by the enhanced client.
     */
    protected open val tableSchema: TableSchema<T> =
        TableSchema.fromBean(entityClass)

    final override val table: DynamoDbAsyncTable<T> by lazy {
        enhancedClient.table(tableNameResolver.resolve(tableName), tableSchema)
    }

    /**
     * Converts an identifier into a DynamoDB [Key].
     */
    protected abstract fun keyFromId(id: ID): Key

    /**
     * Converts an item into a DynamoDB [Key].
     */
    protected open fun keyFromItem(item: T): Key =
        throw UnsupportedOperationException("Override keyFromItem(item) to delete items by entity.")

    override suspend fun save(item: T): T {
        table.putItem(item).await()
        return item
    }

    override suspend fun findById(id: ID): T? =
        table.getItem(keyFromId(id)).await()

    override suspend fun existsById(id: ID): Boolean =
        findById(id) != null

    override suspend fun deleteById(id: ID): T? =
        table.deleteItem(keyFromId(id)).await()

    override suspend fun delete(item: T): T? =
        table.deleteItem(keyFromItem(item)).await()

    override suspend fun update(item: T): T? =
        table.updateItem(item).await()

    override fun scan(builder: ScanEnhancedRequest.Builder.() -> Unit): Flow<T> {
        val request = ScanEnhancedRequest.builder()
            .apply(builder)
            .build()
        return table.scan(request).toItemsFlow()
    }

    override fun query(
        queryConditional: QueryConditional,
        builder: QueryEnhancedRequest.Builder.() -> Unit,
    ): Flow<T> {
        val request = QueryEnhancedRequest.builder()
            .queryConditional(queryConditional)
            .apply(builder)
            .build()
        return table.query(request).toItemsFlow()
    }

    override fun queryIndex(
        indexName: String,
        queryConditional: QueryConditional,
        builder: QueryEnhancedRequest.Builder.() -> Unit,
    ): Flow<T> {
        val request = QueryEnhancedRequest.builder()
            .queryConditional(queryConditional)
            .apply(builder)
            .build()
        return table.index(indexName).query(request).toItemsFlow()
    }

    private fun SdkPublisher<Page<T>>.toItemsFlow(): Flow<T> =
        flow {
            asFlow().collect { page ->
                page.items().forEach { emit(it) }
            }
        }
}
