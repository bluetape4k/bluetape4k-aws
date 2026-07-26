package io.bluetape4k.aws.kinesis.model

import io.bluetape4k.support.requireNotBlank
import software.amazon.awssdk.services.kinesis.model.DescribeStreamRequest

/**
 * Builds a [DescribeStreamRequest] with a DSL block.
 *
 * ```kotlin
 * val req = describeStreamRequest {
 *     streamName("my-stream")
 * }
 * ```
 */
inline fun describeStreamRequest(
    builder: DescribeStreamRequest.Builder.() -> Unit,
): DescribeStreamRequest =
    DescribeStreamRequest.builder().apply(builder).build()

/**
 * Creates a [DescribeStreamRequest] from a stream name.
 *
 * ## Behavior/Contract
 * - Throws `IllegalArgumentException` when [streamName] is blank.
 *
 * ```kotlin
 * val req = describeStreamRequestOf("my-stream")
 * ```
 */
inline fun describeStreamRequestOf(
    streamName: String,
    builder: DescribeStreamRequest.Builder.() -> Unit = {},
): DescribeStreamRequest {
    streamName.requireNotBlank("streamName")
    return describeStreamRequest {
        streamName(streamName)
        builder()
    }
}
