package io.bluetape4k.aws.kotlin.kinesis.model

import aws.sdk.kotlin.services.kinesis.model.PutRecordsRequestEntry
import io.bluetape4k.support.requireNotBlank

/**
 * Creates a [PutRecordsRequestEntry] from a partition key and data.
 *
 * ## Behavior and contract
 * - Throws `IllegalArgumentException` when [partitionKey] is blank.
 *
 * ```kotlin
 * val entry = putRecordsRequestEntryOf(
 *     partitionKey = "pk",
 *     data = "hello".toByteArray()
 * )
 * ```
 */
inline fun putRecordsRequestEntryOf(
    partitionKey: String,
    data: ByteArray,
    crossinline builder: PutRecordsRequestEntry.Builder.() -> Unit = {},
): PutRecordsRequestEntry {
    partitionKey.requireNotBlank("partitionKey")
    return PutRecordsRequestEntry {
        this.partitionKey = partitionKey
        this.data = data
        builder()
    }
}
