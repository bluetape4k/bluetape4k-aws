package io.bluetape4k.aws.dynamodb.model

import software.amazon.awssdk.services.dynamodb.model.ProvisionedThroughput

/**
 * See the API documentation for details.
 *
 * ```kotlin
 * val throughput = provisionedThroughput {
 *    readCapacityUnits(10)
 *    writeCapacityUnits(5)
 * }
 * ```
 *
 * @return Return value.
 */
inline fun ProvisionedThroughput(
    builder: ProvisionedThroughput.Builder.() -> Unit,
): ProvisionedThroughput {
    return ProvisionedThroughput.builder().apply(builder).build()
}

/**
 * See the API documentation for details.
 *
 * ```kotlin
 * val throughput = provisionedThroughputOf(10, 5)
 * ```
 *
 * @param readCapacityUnits Parameter.
 * @param writeCapacityUnits Parameter.
 *
 * @return Return value.
 */
fun provisionedThroughputOf(
    readCapacityUnits: Long? = null,
    writeCapacityUnits: Long? = null,
): ProvisionedThroughput = ProvisionedThroughput {
    readCapacityUnits(readCapacityUnits)
    writeCapacityUnits(writeCapacityUnits)
}
