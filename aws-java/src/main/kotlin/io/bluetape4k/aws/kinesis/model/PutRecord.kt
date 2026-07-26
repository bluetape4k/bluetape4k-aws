package io.bluetape4k.aws.kinesis.model

import io.bluetape4k.support.requireNotBlank
import software.amazon.awssdk.core.SdkBytes
import software.amazon.awssdk.services.kinesis.model.PutRecordRequest

/**
 * Builds a [PutRecordRequest] with a DSL block.
 *
 * ```kotlin
 * val req = putRecordRequest {
 *     streamName("my-stream")
 *     partitionKey("partition-1")
 *     data(SdkBytes.fromUtf8String("hello"))
 * }
 * ```
 */
inline fun putRecordRequest(
    builder: PutRecordRequest.Builder.() -> Unit,
): PutRecordRequest =
    PutRecordRequest.builder().apply(builder).build()

/**
 * Creates a [PutRecordRequest] from a stream name, partition key, and data.
 *
 * ## Behavior/Contract
 * - Throws `IllegalArgumentException` when [streamName] is blank.
 * - Throws `IllegalArgumentException` when [partitionKey] is blank.
 *
 * ```kotlin
 * val req = putRecordRequestOf(
 *     streamName = "my-stream",
 *     partitionKey = "pk",
 *     data = SdkBytes.fromUtf8String("hello world")
 * )
 * ```
 */
inline fun putRecordRequestOf(
    streamName: String,
    partitionKey: String,
    data: SdkBytes,
    builder: PutRecordRequest.Builder.() -> Unit = {},
): PutRecordRequest {
    streamName.requireNotBlank("streamName")
    partitionKey.requireNotBlank("partitionKey")
    return putRecordRequest {
        streamName(streamName)
        partitionKey(partitionKey)
        data(data)
        builder()
    }
}
