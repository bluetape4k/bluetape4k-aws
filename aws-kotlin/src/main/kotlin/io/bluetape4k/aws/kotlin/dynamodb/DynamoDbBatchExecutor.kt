package io.bluetape4k.aws.kotlin.dynamodb

import aws.sdk.kotlin.services.dynamodb.DynamoDbClient
import aws.sdk.kotlin.services.dynamodb.batchWriteItem
import aws.sdk.kotlin.services.dynamodb.model.AttributeValue
import aws.sdk.kotlin.services.dynamodb.model.WriteRequest
import io.bluetape4k.aws.kotlin.dynamodb.Defaults.MAX_BATCH_ITEM_SIZE
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.github.resilience4j.kotlin.retry.executeSuspendFunction
import io.github.resilience4j.retry.Retry
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.buffer
import java.io.Serializable

/**
 * Executes DynamoDB batch put and delete operations with the AWS SDK for Kotlin.
 *
 * ## Behavior / Contract
 * - Splits work into [MAX_BATCH_ITEM_SIZE] chunks and calls `BatchWriteItem`.
 * - Recursively retries unprocessed items up to [maxUnprocessedRetry].
 * - Applies a [Retry] policy for transient failures such as throttling.
 *
 * ```kotlin
 * val executor = DynamoDbBatchExecutor<Order>(client)
 * executor.putAll("orders", orders, OrderMapper())
 * ```
 *
 * @param T Entity type handled by the batch operation.
 * @param client AWS SDK for Kotlin [DynamoDbClient] instance.
 * @param retry Resilience4j [Retry] policy. Defaults to `"dynamo-batch"`.
 * @param maxUnprocessedRetry Maximum retry count for unprocessed items. Defaults to 10.
 */
// WHY: Methods are suspend functions and should use the caller's coroutine context.
// A private SupervisorJob-backed scope can survive callers and leak resources.
class DynamoDbBatchExecutor<T: Any>(
    private val client: DynamoDbClient,
    private val retry: Retry = Retry.ofDefaults("dynamo-batch"),
    private val maxUnprocessedRetry: Int = 10,
) {
    companion object: KLoggingChannel()

    /**
     * Batch work item that pairs a table name with a [WriteRequest].
     *
     * @property tableName DynamoDB table name.
     * @property writeRequest Write request to execute.
     */
    data class TableItemTuple(
        val tableName: String,
        val writeRequest: WriteRequest,
    ): Serializable {
        companion object {
            private const val serialVersionUID: Long = 1L
        }
    }

    /**
     * Retry state containing unprocessed items and the current attempt number.
     *
     * @property attempt Current attempt number, starting at 1.
     * @property items [TableItemTuple] values to retry.
     */
    data class RetryablePut(
        val attempt: Int,
        val items: List<TableItemTuple>,
    ): Serializable {
        companion object {
            private const val serialVersionUID: Long = 1L
        }
    }

    /**
     * Stores attribute maps in [tableName] with DynamoDB batch put requests.
     *
     * ## Behavior / Contract
     * - Puts items that are already mapped to `Map<String, AttributeValue>`.
     * - Splits items into 25-item chunks and executes `BatchWriteItem`.
     *
     * ```kotlin
     * val items = listOf(mapOf("id" to AttributeValue.S("1")))
     * executor.putAll("users", items)
     * ```
     *
     * @param tableName DynamoDB table name to store items in.
     * @param items DynamoDB attribute maps to store.
     */
    suspend fun putAll(
        tableName: String,
        items: List<Map<String, AttributeValue>>,
    ) {
        val writeRequests =
            items.map {
                val request =
                    WriteRequest {
                        putRequest {
                            this.item = it
                        }
                    }
                TableItemTuple(tableName, request)
            }
        persist(writeRequests)
    }

    /**
     * Maps [items] with [mapper] and stores them in [tableName] with batch put requests.
     *
     * ## Behavior / Contract
     * - [mapper] converts each entity to `Map<String, AttributeValue>`.
     * - Splits items into 25-item chunks and executes `BatchWriteItem`.
     *
     * ```kotlin
     * executor.putAll("orders", listOf(order1, order2), OrderMapper())
     * ```
     *
     * @param tableName DynamoDB table name to store items in.
     * @param items Entities to store.
     * @param mapper [DynamoItemMapper] that maps entities to DynamoDB attribute maps.
     */
    suspend fun putAll(
        tableName: String,
        items: List<T>,
        mapper: DynamoItemMapper<T>,
    ) {
        val writeRequests =
            items.map {
                val request =
                    WriteRequest {
                        putRequest {
                            this.item = mapper.mapToDynamoItem(it)
                        }
                    }
                TableItemTuple(tableName, request)
            }
        persist(writeRequests)
    }

    /**
     * Deletes [items] from [tableName] with keys extracted by [primaryKeySelector].
     *
     * ## Behavior / Contract
     * - [primaryKeySelector] extracts the key attribute map for each entity.
     * - Splits items into 25-item chunks and executes `BatchWriteItem` delete requests.
     *
     * ```kotlin
     * executor.deleteAll("orders", orders) { mapOf("id" to AttributeValue.S(it.id)) }
     * ```
     *
     * @param tableName DynamoDB table name to delete items from.
     * @param items Entities to delete.
     * @param primaryKeySelector Function that extracts a DynamoDB key map from an entity.
     */
    suspend fun deleteAll(
        tableName: String,
        items: List<T>,
        primaryKeySelector: (T) -> Map<String, AttributeValue>,
    ) {
        val writeRequests =
            items.map { item ->
                val request =
                    WriteRequest {
                        deleteRequest {
                            key = primaryKeySelector(item)
                        }
                    }
                TableItemTuple(tableName, request)
            }
        persist(writeRequests)
    }

    /**
     * Deletes [items] from [tableName] with keys extracted by [primaryKeyMapper].
     *
     * ## Behavior / Contract
     * - [primaryKeyMapper] returns the key attribute map for each entity.
     * - Splits items into 25-item chunks and executes `BatchWriteItem` delete requests.
     *
     * ```kotlin
     * executor.deleteAll("orders", orders, OrderKeyMapper())
     * ```
     *
     * @param tableName DynamoDB table name to delete items from.
     * @param items Entities to delete.
     * @param primaryKeyMapper [DynamoItemMapper] that extracts a DynamoDB key attribute map.
     */
    suspend fun deleteAll(
        tableName: String,
        items: List<T>,
        primaryKeyMapper: DynamoItemMapper<T>,
    ) {
        val writeRequests =
            items.map {
                val request =
                    WriteRequest {
                        deleteRequest {
                            key = primaryKeyMapper.mapToDynamoItem(it)
                        }
                    }
                TableItemTuple(tableName, request)
            }
        persist(writeRequests)
    }

    private suspend fun persist(items: List<TableItemTuple>) {
        items
            .chunked(MAX_BATCH_ITEM_SIZE)
            .asFlow()
            .buffer()
            .collect { chunked ->
                retry.executeSuspendFunction { persistAll(chunked) }
            }
    }

    private tailrec suspend fun persistAll(
        items: List<TableItemTuple>,
        attempt: Int = 1,
    ) {
        val requestItems = items.groupBy({ it.tableName }, { it.writeRequest })

        val result =
            client.batchWriteItem {
                this.requestItems = requestItems
            }

        if (result.unprocessedItems?.isNotEmpty() == true) {
            check(attempt < maxUnprocessedRetry) {
                "Failed to process batch write after $attempt attempts; unprocessed items remained."
            }

            val unprocessedItems =
                requireNotNull(result.unprocessedItems).entries.flatMap { entry ->
                    entry.value.map { TableItemTuple(entry.key, it) }
                }
            persistAll(unprocessedItems, attempt + 1)
        }
    }
}
