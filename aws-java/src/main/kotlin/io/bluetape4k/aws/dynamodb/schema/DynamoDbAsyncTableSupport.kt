package io.bluetape4k.aws.dynamodb.schema

import io.bluetape4k.aws.dynamodb.model.provisionedThroughputOf
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbAsyncTable
import java.util.concurrent.CompletableFuture

/**
 * See the API documentation for details.
 *
 * ```kotlin
 * val client: DynamoDbEnhancedAsyncClient
 *
 * override val table: DynamoDbAsyncTable<FoodDocument> by lazy {
 *     client.table("$tablePrefix${Schema.TABLE_NAME}")
 * }
 *
 * table.createTable(100, 100).await()
 * ```
 *
 * @param readCapacityUnits Parameter.
 * @param writeCapacityUnits Parameter.
 *
 * @return Return value.
 */
fun <T: Any> DynamoDbAsyncTable<T>.createTable(
    readCapacityUnits: Long? = null,
    writeCapacityUnits: Long? = null,
): CompletableFuture<Void> {
    val request = CreateTableEnhancedRequest {
        provisionedThroughput(provisionedThroughputOf(readCapacityUnits, writeCapacityUnits))
    }
    return createTable(request)
}

/**
 * See the API documentation for details.
 *
 * ```kotlin
 * val client: DynamoDbEnhancedAsyncClient
 *
 * override val table: DynamoDbAsyncTable<FoodDocument> by lazy {
 *     client.table("$tablePrefix${Schema.TABLE_NAME}")
 * }
 *
 * table.createTable(100, 100).await()
 * table.putItems(food1, food2, food3).await()
 * ```
 *
 * @param items Parameter.
 * @return Return value.
 *
 */
fun <T: Any> DynamoDbAsyncTable<T>.putItems(vararg items: T): CompletableFuture<Void> {
    return putItems(items.asList())
}

/**
 * See the API documentation for details.
 *
 * ```kotlin
 * val client: DynamoDbEnhancedAsyncClient
 *
 * override val table: DynamoDbAsyncTable<FoodDocument> by lazy {
 *     client.table("$tablePrefix${Schema.TABLE_NAME}")
 * }
 *
 * table.createTable(100, 100).await()
 * table.putItems(listOf(food1, food2, food3)).await()
 * ```
 *
 * @param items Parameter.
 * @return Return value.
 *
 */
fun <T: Any> DynamoDbAsyncTable<T>.putItems(items: Collection<T>): CompletableFuture<Void> {
    val futures = items.map { putItem(it) }
    return CompletableFuture.allOf(*futures.toTypedArray())
}
