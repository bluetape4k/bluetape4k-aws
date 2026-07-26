package io.bluetape4k.aws.dynamodb.enhanced

import io.bluetape4k.aws.dynamodb.DynamoDb
import io.bluetape4k.aws.dynamodb.DynamoDb.MAX_BATCH_ITEM_SIZE
import io.bluetape4k.aws.dynamodb.model.BatchWriteItemEnhancedRequest
import io.bluetape4k.aws.dynamodb.model.writeBatchOf
import io.bluetape4k.coroutines.flow.extensions.chunked
import io.bluetape4k.support.requireNotBlank
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.future.await
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbAsyncTable
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedAsyncClient
import software.amazon.awssdk.enhanced.dynamodb.MappedTableResource
import software.amazon.awssdk.enhanced.dynamodb.TableSchema
import software.amazon.awssdk.enhanced.dynamodb.model.BatchWriteResult

/**
 * Creates a DynamoDb Table named [tableName].
 *
 * ```kotlin
 * val table = enhancedAsyncClient.table<MyEntity>("orders")
 * check(table.tableName() == "orders")
 * ```
 *
 * @param T DynamoDB table entity type.
 * @param tableName Table name.
 * @return [DynamoDbAsyncTable] instance
 */
inline fun <reified T: Any> DynamoDbEnhancedAsyncClient.table(tableName: String): DynamoDbAsyncTable<T> {
    tableName.requireNotBlank("tableName")
    return table(tableName, TableSchema.fromBean(T::class.java))
}

/**
 * Splits large item writes into chunks of up to [DynamoDb.MAX_BATCH_ITEM_SIZE].
 *
 * Based on `EnhancedAsyncClientExtensionsTest`, when 30 items are provided with the default chunk size 25,
 * the [Flow] emits 2 results.
 *
 * @param T
 * @param itemClass entity class
 * @param table [DynamoDbAsyncTable] instance
 * @param items Item collection to store.
 * @param chunkSize Must be no greater than [DynamoDb.MAX_BATCH_ITEM_SIZE] (1..25).
 * @return [BatchWriteResult] collection.
 */
fun <T: Any> DynamoDbEnhancedAsyncClient.batchWriteItems(
    itemClass: Class<T>,
    table: MappedTableResource<T>,
    items: Collection<T>,
    chunkSize: Int = MAX_BATCH_ITEM_SIZE,
): Flow<BatchWriteResult> {
    val chunk = chunkSize.coerceIn(1, MAX_BATCH_ITEM_SIZE)

    return items
        .asFlow()
        .buffer(chunk)
        .chunked(chunk)
        .map { chunkedItems ->
            val request =
                BatchWriteItemEnhancedRequest {
                    addWriteBatch(writeBatchOf(table, chunkedItems, itemClass))
                }
            batchWriteItem(request).await()
        }
}

/**
 * Splits large item writes into chunks of up to [DynamoDb.MAX_BATCH_ITEM_SIZE].
 *
 * ```kotlin
 * val resultCount = enhancedAsyncClient.batchWriteItems(table, items, chunkSize = 5).count()
 * check(resultCount == items.chunked(5).size)
 * ```
 *
 * @param T
 * @param table [DynamoDbAsyncTable] instance
 * @param items Item collection to store.
 * @param chunkSize Must be no greater than [DynamoDb.MAX_BATCH_ITEM_SIZE] (1..25).
 * @return [BatchWriteResult] collection.
 */
inline fun <reified T: Any> DynamoDbEnhancedAsyncClient.batchWriteItems(
    table: MappedTableResource<T>,
    items: Collection<T>,
    chunkSize: Int = MAX_BATCH_ITEM_SIZE,
): Flow<BatchWriteResult> =
    batchWriteItems(T::class.java, table, items, chunkSize)
