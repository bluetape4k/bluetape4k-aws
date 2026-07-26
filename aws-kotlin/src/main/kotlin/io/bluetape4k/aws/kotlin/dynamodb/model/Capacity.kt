package io.bluetape4k.aws.kotlin.dynamodb.model

import aws.sdk.kotlin.services.dynamodb.model.Capacity
import io.bluetape4k.support.requirePositiveNumber

/**
 * Builds a DynamoDB [Capacity] value with a DSL block.
 *
 * ## Behavior and contract
 * - [capacityUnits], [readCapacityUnits], and [writeCapacityUnits] must be positive when they are not null.
 * - Null values are omitted from the request.
 * - Additional fields can be overridden through [builder].
 *
 * ```kotlin
 * val cap = capacityOf(capacityUnits = 5.0, readCapacityUnits = 3.0)
 * // cap.capacityUnits == 5.0
 * // cap.readCapacityUnits == 3.0
 * ```
 *
 * @param capacityUnits total consumed capacity units. Must be positive when not null.
 * @param readCapacityUnits read capacity units. Must be positive when not null.
 * @param writeCapacityUnits write capacity units. Must be positive when not null.
 */
inline fun capacityOf(
    capacityUnits: Double? = null,
    readCapacityUnits: Double? = null,
    writeCapacityUnits: Double? = null,
    crossinline builder: Capacity.Builder.() -> Unit = {},
): Capacity {
    capacityUnits?.requirePositiveNumber("capacityUnits")
    readCapacityUnits?.requirePositiveNumber("readCapacityUnits")
    writeCapacityUnits?.requirePositiveNumber("writeCapacityUnits")

    return Capacity {
        this.capacityUnits = capacityUnits
        this.readCapacityUnits = readCapacityUnits
        this.writeCapacityUnits = writeCapacityUnits

        builder()
    }
}
