package io.bluetape4k.aws.dynamodb.enhanced

import io.bluetape4k.aws.dynamodb.DynamoDb
import io.bluetape4k.aws.dynamodb.DynamoDb.MAX_BATCH_ITEM_SIZE
import io.bluetape4k.aws.dynamodb.model.BatchWriteItemEnhancedRequest
import io.bluetape4k.aws.dynamodb.model.writeBatchOf
import io.bluetape4k.support.requireNotBlank
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient
import software.amazon.awssdk.services.dynamodb.model.ResourceNotFoundException
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable
import software.amazon.awssdk.enhanced.dynamodb.MappedTableResource
import software.amazon.awssdk.enhanced.dynamodb.TableSchema
import software.amazon.awssdk.enhanced.dynamodb.model.BatchWriteResult

/**
 * Create DynamoDb Table with specific name ([tableName])
 *
 * ```kotlin
 * val table = enhancedClient.table<MyEntity>("orders")
 * check(table.tableName() == "orders")
 * ```
 *
 * @param T entity type
 * @param tableName table name
 * @return [DynamoDbTable] instance
 */
inline fun <reified T: Any> DynamoDbEnhancedClient.table(tableName: String): DynamoDbTable<T> {
    tableName.requireNotBlank("tableName")
    return table(tableName, TableSchema.fromBean(T::class.java))
}

/**
 * Splits large item writes into chunks of up to [DynamoDb.MAX_BATCH_ITEM_SIZE].
 *
 * As verified by the same logic in `EnhancedAsyncClientExtensionsTest`, when `items=30` and `chunkSize=25`,
 * the result collection size is `2`.
 *
 * @param T  entity type
 * @param itemClass entity class
 * @param table [MappedTableResource] instance
 * @param items Item collection to store.
 * @param chunkSize Must be no greater than [DynamoDb.MAX_BATCH_ITEM_SIZE] (1..25).
 * @return [BatchWriteResult] collection.
 */
fun <T: Any> DynamoDbEnhancedClient.batchWriteItems(
    itemClass: Class<T>,
    table: MappedTableResource<T>,
    items: Collection<T>,
    chunkSize: Int = MAX_BATCH_ITEM_SIZE,
): List<BatchWriteResult> {
    val chunk = chunkSize.coerceIn(1, MAX_BATCH_ITEM_SIZE)
    return items
        .chunked(chunk)
        .map { chunkedItems ->
            val request =
                BatchWriteItemEnhancedRequest {
                    addWriteBatch(writeBatchOf(table, chunkedItems, itemClass))
                }
            batchWriteItem(request)
        }
}

/**
 * Splits large item writes into chunks of up to [DynamoDb.MAX_BATCH_ITEM_SIZE].
 *
 * ```kotlin
 * val results = enhancedClient.batchWriteItems(table, items, chunkSize = 10)
 * check(results.size == items.chunked(10).size)
 * ```
 *
 * @param T entity type
 * @param table [MappedTableResource] instance
 * @param items Item collection to store.
 * @param chunkSize Must be no greater than [DynamoDb.MAX_BATCH_ITEM_SIZE] (1..25).
 * @return [BatchWriteResult] collection.
 */
inline fun <reified T: Any> DynamoDbEnhancedClient.batchWriteItems(
    table: MappedTableResource<T>,
    items: Collection<T>,
    chunkSize: Int = MAX_BATCH_ITEM_SIZE,
): List<BatchWriteResult> = batchWriteItems(T::class.java, table, items, chunkSize)

/**
 * Returns `true` if the named table exists, `false` if it does not.
 *
 * Only [ResourceNotFoundException] is normalized to `false`; all other exceptions
 * (auth failures, network errors, etc.) propagate to the caller.
 *
 * ```kotlin
 * val exists = enhancedClient.existsTable("orders")
 * check(exists is Boolean)
 * ```
 *
 * @param tableName table name to check
 * @return `true` if the table exists, `false` if [ResourceNotFoundException] is thrown
 */
fun DynamoDbEnhancedClient.existsTable(tableName: String): Boolean =
    try {
        table<Any>(tableName).describeTable()
        true
    } catch (_: ResourceNotFoundException) {
        false
    }
