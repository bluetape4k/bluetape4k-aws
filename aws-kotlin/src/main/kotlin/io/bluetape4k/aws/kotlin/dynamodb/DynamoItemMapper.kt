package io.bluetape4k.aws.kotlin.dynamodb

import aws.sdk.kotlin.services.dynamodb.model.AttributeValue
import aws.sdk.kotlin.services.dynamodb.model.WriteRequest

/**
 * Mapper interface that converts an entity into a DynamoDB item attribute map.
 *
 * ## Behavior and contract
 * - This is a `fun interface`, so callers can instantiate it directly with a lambda.
 * - [mapToDynamoItem] implementations convert each entity field into an `AttributeValue`.
 *
 * ```kotlin
 * val mapper = DynamoItemMapper<Order> { order ->
 *     mapOf("id" to AttributeValue.S(order.id), "total" to AttributeValue.N(order.total.toString()))
 * }
 * ```
 */
fun interface DynamoItemMapper<T: Any> {

    /**
     * Converts the [item] entity into a DynamoDB attribute map.
     *
     * @return mapping from column name to [AttributeValue].
     */
    fun mapToDynamoItem(item: T): Map<String, AttributeValue>
}

/**
 * Reads a DynamoDB item attribute map into an application type.
 *
 * Contract:
 * - Implementations should be explicit and deterministic; this interface does
 *   not use reflection or the preview AWS Kotlin DynamoDB mapper.
 * - Missing or malformed attributes should fail fast instead of returning a
 *   partially initialized domain object.
 *
 * ```kotlin
 * val reader = DynamoItemReader<Order> { item ->
 *     Order(id = item.getValue("id").asS(), total = item.getValue("total").asN().toBigDecimal())
 * }
 * ```
 */
fun interface DynamoItemReader<T: Any> {

    /**
     * Converts one DynamoDB item attribute map into [T].
     */
    fun readDynamoItem(item: Map<String, AttributeValue>): T
}

/**
 * Converts the entities in this [Iterable] into [WriteRequest] values for DynamoDB Put operations.
 *
 * ```kotlin
 * val writeRequests = items.buildWriteRequests(mapper)
 * ```
 */
fun <T: Any> Iterable<T>.buildWritePutRequests(mapper: DynamoItemMapper<T>): List<WriteRequest> {
    return map {
        WriteRequest {
            putRequest {
                item = mapper.mapToDynamoItem(it)
            }
        }
    }
}

/**
 * Converts the entities in this [Iterable] into [WriteRequest] values for DynamoDB Delete operations.
 *
 * ```kotlin
 * val writeRequests = items.buildWriteDeleteRequests(mapper)
 * ```
 */
fun <T: Any> Iterable<T>.buildWriteDeleteRequests(keySelector: DynamoItemMapper<T>): List<WriteRequest> {
    return this.map {
        WriteRequest {
            deleteRequest {
                key = keySelector.mapToDynamoItem(it)
            }
        }
    }
}
