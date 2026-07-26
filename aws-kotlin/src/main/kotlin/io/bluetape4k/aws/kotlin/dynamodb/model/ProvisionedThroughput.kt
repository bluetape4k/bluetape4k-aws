package io.bluetape4k.aws.kotlin.dynamodb.model

import aws.sdk.kotlin.services.dynamodb.model.ProvisionedThroughput
import io.bluetape4k.support.requirePositiveNumber

/**
 * Creates a DynamoDB [ProvisionedThroughput] value.
 *
 * ## Behavior and contract
 * - [readCapacityUnits] must be positive when it is not null.
 * - [writeCapacityUnits] must be positive when it is not null.
 * - Null values are omitted from the request.
 *
 * ```kotlin
 * val tp = provisionedThroughputOf(readCapacityUnits = 5L, writeCapacityUnits = 5L)
 * // tp.readCapacityUnits == 5L
 * // tp.writeCapacityUnits == 5L
 * ```
 *
 * @param readCapacityUnits read capacity units. Must be positive when not null.
 * @param writeCapacityUnits write capacity units. Must be positive when not null.
 */
fun provisionedThroughputOf(
    readCapacityUnits: Long? = null,
    writeCapacityUnits: Long? = null,
): ProvisionedThroughput {
    readCapacityUnits?.requirePositiveNumber("readCapacityUnits")
    writeCapacityUnits?.requirePositiveNumber("writeCapacityUnits")

    return ProvisionedThroughput {
        this.readCapacityUnits = readCapacityUnits
        this.writeCapacityUnits = writeCapacityUnits
    }
}
