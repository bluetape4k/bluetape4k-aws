package io.bluetape4k.aws.dynamodb

/**
 * See the API documentation for details.
 *
 * ```kotlin
 * val maxSize = DynamoDb.MAX_BATCH_ITEM_SIZE
 * // maxSize == 25
 * ```
 */
object DynamoDb {

    /**
     * See the API documentation for details.
     *
     * ```kotlin
     * val chunks = items.chunked(DynamoDb.MAX_BATCH_ITEM_SIZE)
     * // chunks.all { it.size <= 25 } == true
     * ```
     */
    const val MAX_BATCH_ITEM_SIZE = 25

}
