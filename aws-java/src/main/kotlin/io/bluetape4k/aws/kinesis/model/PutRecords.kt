package io.bluetape4k.aws.kinesis.model

import io.bluetape4k.aws.kinesis.validateKinesisPutRecordsEntries
import io.bluetape4k.support.requireNotBlank
import software.amazon.awssdk.core.SdkBytes
import software.amazon.awssdk.services.kinesis.model.PutRecordsRequest
import software.amazon.awssdk.services.kinesis.model.PutRecordsRequestEntry

/**
 * Builds a [PutRecordsRequest] with a DSL block.
 *
 * ```kotlin
 * val req = putRecordsRequest {
 *     streamName("my-stream")
 *     records(entries)
 * }
 * ```
 */
inline fun putRecordsRequest(
    builder: PutRecordsRequest.Builder.() -> Unit,
): PutRecordsRequest =
    PutRecordsRequest.builder().apply(builder).build()

/**
 * Creates a [PutRecordsRequest] from a stream name and record list.
 *
 * ## Behavior/Contract
 * - Throws `IllegalArgumentException` when [streamName] is blank.
 * - [entries] must contain 1..500 entries.
 *
 * ```kotlin
 * val req = putRecordsRequestOf("my-stream", entries)
 * ```
 */
inline fun putRecordsRequestOf(
    streamName: String,
    entries: List<PutRecordsRequestEntry>,
    builder: PutRecordsRequest.Builder.() -> Unit = {},
): PutRecordsRequest {
    streamName.requireNotBlank("streamName")
    entries.validateKinesisPutRecordsEntries("entries")
    return putRecordsRequest {
        streamName(streamName)
        records(entries)
        builder()
    }
}

/**
 * Creates a [PutRecordsRequestEntry] from a partition key and data.
 *
 * ## Behavior/Contract
 * - Throws `IllegalArgumentException` when [partitionKey] is blank.
 *
 * ```kotlin
 * val entry = putRecordsRequestEntryOf(
 *     partitionKey = "pk",
 *     data = SdkBytes.fromUtf8String("hello")
 * )
 * ```
 */
fun putRecordsRequestEntryOf(
    partitionKey: String,
    data: SdkBytes,
): PutRecordsRequestEntry {
    partitionKey.requireNotBlank("partitionKey")
    return PutRecordsRequestEntry.builder()
        .partitionKey(partitionKey)
        .data(data)
        .build()
}
