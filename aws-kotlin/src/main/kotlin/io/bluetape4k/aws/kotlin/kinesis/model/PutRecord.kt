package io.bluetape4k.aws.kotlin.kinesis.model

import aws.sdk.kotlin.services.kinesis.model.PutRecordRequest
import io.bluetape4k.support.requireNotBlank

/**
 * Creates a [PutRecordRequest] from a stream name, partition key, and data.
 *
 * ## Behavior and contract
 * - Throws `IllegalArgumentException` when [streamName] is blank.
 * - Throws `IllegalArgumentException` when [partitionKey] is blank.
 *
 * ```kotlin
 * val req = putRecordRequestOf(
 *     streamName = "my-stream",
 *     partitionKey = "pk",
 *     data = "hello".toByteArray()
 * )
 * ```
 */
inline fun putRecordRequestOf(
    streamName: String,
    partitionKey: String,
    data: ByteArray,
    crossinline builder: PutRecordRequest.Builder.() -> Unit = {},
): PutRecordRequest {
    streamName.requireNotBlank("streamName")
    partitionKey.requireNotBlank("partitionKey")
    return PutRecordRequest {
        this.streamName = streamName
        this.partitionKey = partitionKey
        this.data = data
        builder()
    }
}
