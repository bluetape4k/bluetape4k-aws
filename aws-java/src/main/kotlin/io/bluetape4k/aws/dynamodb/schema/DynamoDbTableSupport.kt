package io.bluetape4k.aws.dynamodb.schema

import io.bluetape4k.aws.dynamodb.model.provisionedThroughputOf
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbAsyncTable
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable
import software.amazon.awssdk.enhanced.dynamodb.model.CreateTableEnhancedRequest
import software.amazon.awssdk.enhanced.dynamodb.model.EnhancedGlobalSecondaryIndex
import software.amazon.awssdk.enhanced.dynamodb.model.EnhancedLocalSecondaryIndex
import software.amazon.awssdk.services.dynamodb.model.ProvisionedThroughput

/**
 * See the API documentation for details.
 *
 * ```kotlin
 * val client: DynamoDbClient
 *
 * override val table: DynamoDbTable<FoodDocument> by lazy {
 *     client.table("$tablePrefix${Schema.TABLE_NAME}")
 * }
 *
 * table.createTable(100, 100)
 * ```
 *
 * @param readCapacityUnits Parameter.
 * @param writeCapacityUnits Parameter.

 */
fun <T: Any> DynamoDbTable<T>.createTable(
    readCapacityUnits: Long? = null,
    writeCapacityUnits: Long? = null,
) {
    val request = CreateTableEnhancedRequest {
        provisionedThroughput(provisionedThroughputOf(readCapacityUnits, writeCapacityUnits))
    }
    createTable(request)
}

/**
 * See the API documentation for details.
 *
 * See the API documentation for details.
 * See the API documentation for details.
 * See the API documentation for details.
 *
 * ```kotlin
 * table.putItems(item1, item2, item3)
 * ```
 */
fun <T: Any> DynamoDbTable<T>.putItems(vararg items: T) {
    putItems(items.asList())
}

/**
 * See the API documentation for details.
 *
 * See the API documentation for details.
 * See the API documentation for details.
 * See the API documentation for details.
 *
 * ```kotlin
 * table.putItems(listOf(item1, item2))
 * ```
 */
fun <T: Any> DynamoDbTable<T>.putItems(items: Collection<T>) {
    items.forEach { putItem(it) }
}

/**
 * See the API documentation for details.
 *
 * ```kotlin
 * val request = CreateTableEnhancedRequest {
 *    provisionedThroughput(provisionedThroughputOf(100, 100))
 *    localSecondaryIndices(localSecondaryIndices)
 *    globalSecondaryIndices(globalSecondaryIndices)
 *    // ...
 * }
 *
 * table.createTable(request)
 * ```
 *
 * @return Return value.
 */
inline fun CreateTableEnhancedRequest(
    builder: CreateTableEnhancedRequest.Builder.() -> Unit,
): CreateTableEnhancedRequest {
    return CreateTableEnhancedRequest.builder().apply(builder).build()
}

/**
 * See the API documentation for details.
 *
 * ```kotlin
 * val request = createTableEnhancedRequestOf(
 *    provisionedThroughput = provisionedThroughputOf(100, 100),
 *    localSecondaryIndices = localSecondaryIndices,
 *    globalSecondaryIndices = globalSecondaryIndices,
 * )
 *
 * table.createTable(request)
 * ```
 *
 * @return Return value.
 */
fun createTableEnhancedRequestOf(
    provisionedThroughput: ProvisionedThroughput? = null,
    localSecondaryIndices: Collection<EnhancedLocalSecondaryIndex>? = null,
    globalSecondaryIndices: Collection<EnhancedGlobalSecondaryIndex>? = null,
): CreateTableEnhancedRequest = CreateTableEnhancedRequest {
    provisionedThroughput(provisionedThroughput)
    localSecondaryIndices(localSecondaryIndices)
    globalSecondaryIndices(globalSecondaryIndices)
}
