package io.bluetape4k.aws.kotlin.dynamodb

import aws.sdk.kotlin.services.dynamodb.DynamoDbClient
import aws.sdk.kotlin.services.dynamodb.createTable
import aws.sdk.kotlin.services.dynamodb.deleteTable
import aws.sdk.kotlin.services.dynamodb.model.AttributeDefinition
import aws.sdk.kotlin.services.dynamodb.model.CreateTableRequest
import aws.sdk.kotlin.services.dynamodb.model.CreateTableResponse
import aws.sdk.kotlin.services.dynamodb.model.DeleteTableResponse
import aws.sdk.kotlin.services.dynamodb.model.DescribeTableRequest
import aws.sdk.kotlin.services.dynamodb.model.KeySchemaElement
import aws.sdk.kotlin.services.dynamodb.model.PutItemRequest
import aws.sdk.kotlin.services.dynamodb.model.PutItemResponse
import aws.sdk.kotlin.services.dynamodb.model.ResourceNotFoundException
import aws.sdk.kotlin.services.dynamodb.model.ScanRequest
import aws.sdk.kotlin.services.dynamodb.model.ScanResponse
import aws.sdk.kotlin.services.dynamodb.model.TableStatus
import aws.sdk.kotlin.services.dynamodb.paginators.listTablesPaginated
import aws.sdk.kotlin.services.dynamodb.paginators.scanPaginated
import aws.sdk.kotlin.services.dynamodb.paginators.tableNames
import aws.sdk.kotlin.services.dynamodb.putItem
import io.bluetape4k.aws.kotlin.dynamodb.model.toAttributeValue
import io.bluetape4k.aws.kotlin.dynamodb.model.toAttributeValueMap
import io.bluetape4k.logging.KotlinLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.support.requireNotBlank
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.any
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private val log = KotlinLogging.logger {}


/**
 * Creates a DynamoDB table with a DSL block.
 *
 * ## Behavior and contract
 * - Throws `IllegalArgumentException` when [tableName] is blank.
 * - Sets `provisionedThroughput` only when [readCapacityUnits] or [writeCapacityUnits] is not null.
 * - Additional options can be supplied through [builder].
 *
 * ```kotlin
 * val response = client.createTable("orders") {
 *     keySchema = listOf(keySchemaElementOf("id", KeyType.Hash))
 *     attributeDefinitions = listOf(attributeDefinitionOf("id", ScalarAttributeType.S))
 * }
 * ```
 *
 * @param tableName table name to create.
 * @param readCapacityUnits read capacity units, omitted when null.
 * @param writeCapacityUnits write capacity units, omitted when null.
 * @throws IllegalArgumentException if [tableName] is blank.
 */
suspend fun DynamoDbClient.createTable(
    tableName: String,
    keySchema: List<KeySchemaElement>? = null,
    attributeDefinitions: List<AttributeDefinition>? = null,
    readCapacityUnits: Long? = null,
    writeCapacityUnits: Long? = null,
    builder: CreateTableRequest.Builder.() -> Unit = {},
): CreateTableResponse {
    tableName.requireNotBlank("tableName")

    return createTable {
        this.tableName = tableName
        keySchema?.let { this.keySchema = it }
        attributeDefinitions?.let { this.attributeDefinitions = it }
        if (readCapacityUnits != null || writeCapacityUnits != null) {
            provisionedThroughput {
                readCapacityUnits?.let { this.readCapacityUnits = it }
                writeCapacityUnits?.let { this.writeCapacityUnits = it }
            }
        }

        builder()
    }
}

/**
 * Checks whether a DynamoDB table named [name] exists.
 *
 * ## Behavior and contract
 * - Iterates through every page from `listTablesPaginated` and returns whether [name] is present.
 * - Throws `IllegalArgumentException` when [name] is blank.
 *
 * ```kotlin
 * val exists = client.existsTable("orders")
 * // exists == true or false
 * ```
 *
 * @throws IllegalArgumentException if [name] is blank.
 */
suspend fun DynamoDbClient.existsTable(name: String): Boolean {
    name.requireNotBlank("name")
    return listTablesPaginated()
        .tableNames()
        .any { it == name }
}

/**
 * Deletes the DynamoDB table named [name] when it exists.
 *
 * ## Behavior and contract
 * - Returns [DeleteTableResponse] after deleting an existing table.
 * - Returns null when the table does not exist.
 *
 * ```kotlin
 * val response = client.deleteTableIfExists("orders")
 * // response != null -> deleted, null -> table missing
 * ```
 */
suspend fun DynamoDbClient.deleteTableIfExists(name: String): DeleteTableResponse? =
    if (existsTable(name)) {
        log.debug { "DynamoDB 테이블[$name]을 삭제합니다." }
        deleteTable { this.tableName = name }
    } else {
        null
    }

/**
 * Returns the status of the DynamoDB table named [name].
 *
 * ## Behavior and contract
 * - Calls `DescribeTable` and returns [TableStatus].
 * - Missing table responses return `null`.
 * - Retryable and operational service failures are propagated.
 *
 * ```kotlin
 * val status = client.getTableStatus("orders")
 * // status == TableStatus.Active or null
 * ```
 */
suspend fun DynamoDbClient.getTableStatus(name: String): TableStatus? =
    try {
        val req = DescribeTableRequest { tableName = name }
        describeTable(req).table?.tableStatus
    } catch (_: ResourceNotFoundException) {
        null
    }

/**
 * Waits until the DynamoDB table named [name] reaches the `ACTIVE` state.
 *
 * ## Behavior and contract
 * - Polls table status every 10 ms and returns only when the status is `ACTIVE`.
 * - Throws `TimeoutCancellationException` when the table is not ready within [timeout].
 *
 * ```kotlin
 * client.createTable("orders") { ... }
 * client.waitForTableReady("orders", 30.seconds)
 * ```
 *
 * @param timeout maximum wait time. Defaults to 60 seconds.
 * @throws kotlinx.coroutines.TimeoutCancellationException when the wait exceeds [timeout].
 */
suspend fun DynamoDbClient.waitForTableReady(
    name: String,
    timeout: Duration = 60.seconds,
) {
    log.debug { "DynamoDb 테이블[$name]이 준비될 때까지 [timeout] 만큼 대기합니다 ... " }

    withTimeout(timeout) {
        while (true) {
            if (getTableStatus(name) == TableStatus.Active) {
                log.debug { "DynamoDb 테이블[$name]이 준비되었습니다." }
                break
            }
            delay(10.milliseconds)
        }
    }
}

/**
 * Stores [item] in [tableName] as a `Map<String, Any?>`.
 *
 * ## Behavior and contract
 * - Converts [item] values into `AttributeValue` entries with [toAttributeValueMap], then calls PutItem.
 * - Throws `IllegalArgumentException` when [tableName] is blank.
 * - Additional options can be supplied through [builder].
 *
 * ```kotlin
 * client.putItem("users", mapOf("id" to "u1", "name" to "Alice"))
 * ```
 *
 * @param tableName DynamoDB table name to store the item in.
 * @param item item to store. Values are converted to `AttributeValue` automatically.
 * @throws IllegalArgumentException if [tableName] is blank.
 */
suspend inline fun DynamoDbClient.putItem(
    tableName: String,
    item: Map<String, Any?>,
    crossinline builder: PutItemRequest.Builder.() -> Unit = {},
): PutItemResponse {
    tableName.requireNotBlank("tableName")

    return putItem {
        this.tableName = tableName
        this.item = item.toAttributeValueMap()

        builder()
    }
}

/**
 * Scans [tableName] with pagination starting from [exclusiveStartKey].
 *
 * ## Behavior and contract
 * - Converts [exclusiveStartKey] values with [toAttributeValue] and uses them as the starting key.
 * - Limits the maximum number of items returned per page with [limit].
 * - Returns results as a `Flow<ScanResponse>` stream that can be collected from coroutines.
 *
 * ```kotlin
 * val pages: Flow<ScanResponse> = client.scanPaginated("orders", emptyMap(), limit = 100)
 * pages.collect { page -> page.items?.forEach { process(it) } }
 * ```
 *
 * @param tableName DynamoDB table name to scan.
 * @param exclusiveStartKey page starting key. Use an empty map for the first page.
 * @param limit maximum number of items per page. Defaults to 1.
 */
inline fun DynamoDbClient.scanPaginated(
    tableName: String,
    exclusiveStartKey: Map<String, Any?>,
    limit: Int = 1,
    crossinline builder: ScanRequest.Builder.() -> Unit = {},
): Flow<ScanResponse> {
    tableName.requireNotBlank("tableName")

    return scanPaginated {
        this.tableName = tableName
        this.exclusiveStartKey = exclusiveStartKey.mapValues { it.value.toAttributeValue() }
        this.limit = limit

        builder()
    }
}
