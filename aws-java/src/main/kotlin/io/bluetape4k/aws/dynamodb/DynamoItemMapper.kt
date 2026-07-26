package io.bluetape4k.aws.dynamodb

import software.amazon.awssdk.services.dynamodb.model.AttributeValue
import software.amazon.awssdk.services.dynamodb.model.WriteRequest

/**
 * See the API documentation for details.
 */
interface DynamoItemMapper<T: Any> {

    /**
     * See the API documentation for details.
     *
     * @param entity Parameter.
     * @return Return value.
     */
    fun mapToDynamoItem(item: T): Map<String, AttributeValue>
}

/**
 * See the API documentation for details.
 *
 * ```kotlin
 * val writeRequests = items.buildWriteRequest(mapper)
 * ```
 */
fun <T: Any> Iterable<T>.buildWriteRequest(mapper: DynamoItemMapper<T>): List<WriteRequest> {
    return this
        .map {
            val item = mapper.mapToDynamoItem(it)
            WriteRequest.builder()
                .putRequest { builder -> builder.item(item) }
                .build()
        }
}
