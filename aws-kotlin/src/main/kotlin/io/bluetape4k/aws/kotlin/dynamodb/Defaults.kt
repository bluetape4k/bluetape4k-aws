package io.bluetape4k.aws.kotlin.dynamodb

/**
 * Constants and utility values for DynamoDB support.
 */
object Defaults {

    /**
     * DynamoDB BatchWriteItem accepts at most 25 items per batch.
     */
    const val MAX_BATCH_ITEM_SIZE = 25
}
