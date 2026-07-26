@file:Suppress("NOTHING_TO_INLINE")

package io.bluetape4k.aws.dynamodb.enhanced

import io.bluetape4k.aws.dynamodb.model.keyOf
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable
import software.amazon.awssdk.enhanced.dynamodb.Key
import software.amazon.awssdk.enhanced.dynamodb.model.PageIterable
import software.amazon.awssdk.enhanced.dynamodb.model.QueryConditional

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
inline fun <T: Any> DynamoDbTable<T>.getItem(
    partitionValue: Any,
    sortValue: Any? = null,
): T? =
    getItem(keyOf(partitionValue, sortValue))

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
inline fun <T: Any> DynamoDbTable<T>.deleteItem(
    partitionValue: Any,
    sortValue: Any? = null,
): T? {
    val key =
        when (sortValue) {
            null -> {
                Key.builder().partitionValue(partitionValue.toString()).build()
            }

            else -> {
                Key
                    .builder()
                    .partitionValue(partitionValue.toString())
                    .sortValue(sortValue.toString())
                    .build()
            }
        }
    return deleteItem(key)
}

/**
 * Retrieves all items with a scan.
 *
 * @return List of all items.
 *
 * ```kotlin
 * val items = table.findAll()
 * // items.isNotEmpty() == true
 * ```
 */
fun <T> DynamoDbTable<T>.findAll(): List<T> = scan().items().toList()

/**
 * Retrieves all items in a specific partition.
 *
 * @param partitionValue Partition key value.
 * @return Item list.
 *
 * ```kotlin
 * val items = table.findByPartition("user-1")
 * // items.all { it.userId == "user-1" } == true
 * ```
 */
fun <T: Any> DynamoDbTable<T>.findByPartition(partitionValue: String): List<T> {
    val key = Key.builder().partitionValue(partitionValue).build()
    return query(QueryConditional.keyEqualTo(key)).items().toList()
}

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
inline fun <T: Any> DynamoDbTable<T>.exists(
    partitionValue: Any,
    sortValue: Any? = null,
): Boolean = getItem(partitionValue, sortValue) != null

/**
 * Converts results to a List.
 *
 * @return List of all items.
 *
 * ```kotlin
 * val items = pageIterable.toList()
 * // items.isNotEmpty() == true
 * ```
 */
fun <T> PageIterable<T>.toList(): List<T> = items().toList()
