package io.bluetape4k.aws.dynamodb.enhanced

import io.bluetape4k.aws.dynamodb.model.keyOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.future.await
import kotlinx.coroutines.reactive.asFlow
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbAsyncTable
import software.amazon.awssdk.enhanced.dynamodb.model.PutItemEnhancedRequest
import software.amazon.awssdk.enhanced.dynamodb.model.QueryConditional
import software.amazon.awssdk.enhanced.dynamodb.model.QueryEnhancedRequest
import software.amazon.awssdk.enhanced.dynamodb.model.ScanEnhancedRequest

/**
 * Gets an item by key.
 *
 * @param partitionValue Partition key value.
 * @param sortValue Sort key value (optional).
 * @return Retrieved item, or null.
 *
 * ```kotlin
 * val item = table.getItem(partitionValue = "user-1")
 * // item?.id == "user-1"
 * ```
 */
suspend inline fun <T: Any> DynamoDbAsyncTable<T>.getItem(
    partitionValue: Any,
    sortValue: Any? = null,
): T? = getItem(keyOf(partitionValue, sortValue)).await()


/**
 * Stores an item.
 *
 * @param item Item to store.
 *
 * ```kotlin
 * table.putItem(myEntity)
 * // table.getItem(partitionValue = myEntity.id) != null
 * ```
 */
suspend inline fun <T: Any> DynamoDbAsyncTable<T>.putItem(item: T) {
    putItem(item).await()
}

/**
 * Stores an item.
 *
 * @param item Item to store.
 * @param builder PutItemEnhancedRequest builder.
 *
 * ```kotlin
 * table.putItem(myEntity) { conditionExpression(expr) }
 * // table.getItem(partitionValue = myEntity.id) != null
 * ```
 */
suspend inline fun <reified T: Any> DynamoDbAsyncTable<T>.putItem(
    item: T,
    builder: PutItemEnhancedRequest.Builder<T>.() -> Unit,
) {
    val request =
        PutItemEnhancedRequest
            .builder(T::class.java)
            .item(item)
            .apply(builder)
            .build()
    putItem(request).await()
}

/**
 * Deletes an item.
 *
 * @param partitionValue Partition key value.
 * @param sortValue Sort key value (optional).
 * @return Deleted item.
 *
 * ```kotlin
 * val deleted = table.deleteItem(partitionValue = "user-1")
 * // deleted?.id == "user-1"
 * ```
 */
suspend inline fun <T: Any> DynamoDbAsyncTable<T>.deleteItem(
    partitionValue: Any,
    sortValue: Any? = null,
): T? = deleteItem(keyOf(partitionValue, sortValue)).await()

/**
 * Scans the whole table.
 *
 * @param builder ScanEnhancedRequest builder.
 * @return Flow of scan results.
 *
 * ```kotlin
 * val flow = table.scanAll()
 * val items = flow.toList()
 * // items.isNotEmpty() == true
 * ```
 */
inline fun <T: Any> DynamoDbAsyncTable<T>.scanAll(
    builder: ScanEnhancedRequest.Builder.() -> Unit = {},
): Flow<T> {
    val request = ScanEnhancedRequest.builder().apply(builder).build()
    return scan(request).items().asFlow()
}

/**
 * Queries the table.
 *
 * @param queryConditional Query condition.
 * @param builder QueryEnhancedRequest builder.
 * @return Flow of query results.
 *
 * ```kotlin
 * val flow = table.queryAll(QueryConditional.keyEqualTo(key))
 * val items = flow.toList()
 * // items.isNotEmpty() == true
 * ```
 */
inline fun <T: Any> DynamoDbAsyncTable<T>.queryAll(
    queryConditional: QueryConditional,
    builder: QueryEnhancedRequest.Builder.() -> Unit = {},
): Flow<T> {
    val request =
        QueryEnhancedRequest
            .builder()
            .queryConditional(queryConditional)
            .apply(builder)
            .build()

    return query(request).items().asFlow()
}

/**
 * Queries by partition key.
 *
 * @param partitionValue Partition key value.
 * @param builder QueryEnhancedRequest builder.
 * @return Flow of query results.
 *
 * ```kotlin
 * val flow = table.queryByPartition("user-1")
 * val items = flow.toList()
 * // items.all { it.userId == "user-1" } == true
 * ```
 */
inline fun <T: Any> DynamoDbAsyncTable<T>.queryByPartition(
    partitionValue: String,
    builder: QueryEnhancedRequest.Builder.() -> Unit = {},
): Flow<T> {
    val key = keyOf(partitionValue)
    return queryAll(QueryConditional.keyEqualTo(key), builder)
}

/**
 * Retrieves all items with a scan.
 *
 * @return Flow of all items.
 *
 * ```kotlin
 * val items = table.findAll().toList()
 * // items.isNotEmpty() == true
 * ```
 */
fun <T: Any> DynamoDbAsyncTable<T>.findAll(): Flow<T> = scanAll()

/**
 * Retrieves all items in a specific partition.
 *
 * @param partitionValue Partition key value.
 * @return Item Flow.
 *
 * ```kotlin
 * val items = table.findByPartition("user-1").toList()
 * // items.all { it.userId == "user-1" } == true
 * ```
 */
fun <T: Any> DynamoDbAsyncTable<T>.findByPartition(partitionValue: String): Flow<T> =
    queryByPartition(partitionValue)

/**
 * Checks whether an item exists.
 *
 * @param partitionValue Partition key value.
 * @param sortValue Sort key value (optional).
 * @return true when the item exists.
 *
 * ```kotlin
 * val exists = table.exists(partitionValue = "user-1")
 * // exists == true
 * ```
 */
suspend inline fun <T: Any> DynamoDbAsyncTable<T>.exists(
    partitionValue: Any,
    sortValue: Any? = null,
): Boolean = getItem(partitionValue, sortValue) != null
