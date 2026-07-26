package io.bluetape4k.aws.cloudwatch.model.cloudwatchlogs

import io.bluetape4k.support.requireNotBlank
import software.amazon.awssdk.services.cloudwatchlogs.model.CreateLogStreamRequest

/**
 * Builds a [CreateLogStreamRequest] with a DSL block.
 *
 * ```kotlin
 * val request = createLogStreamRequest {
 *     logGroupName("/aws/lambda/my-function")
 *     logStreamName("2024/01/01/[$LATEST]abc123")
 * }
 * ```
 */
inline fun createLogStreamRequest(
    builder: CreateLogStreamRequest.Builder.() -> Unit,
): CreateLogStreamRequest =
    CreateLogStreamRequest.builder().apply(builder).build()

/**
 * Creates a [CreateLogStreamRequest] from a log group name and stream name.
 *
 * ## Behavior/Contract
 * - Throws `IllegalArgumentException` when [logGroupName] is blank.
 * - Throws `IllegalArgumentException` when [logStreamName] is blank.
 *
 * ```kotlin
 * val request = createLogStreamRequestOf(
 *     logGroupName = "/aws/lambda/my-function",
 *     logStreamName = "2024/01/01/[$LATEST]abc123"
 * )
 * ```
 */
inline fun createLogStreamRequestOf(
    logGroupName: String,
    logStreamName: String,
    builder: CreateLogStreamRequest.Builder.() -> Unit = {},
): CreateLogStreamRequest {
    logGroupName.requireNotBlank("logGroupName")
    logStreamName.requireNotBlank("logStreamName")
    return createLogStreamRequest {
        logGroupName(logGroupName)
        logStreamName(logStreamName)
        builder()
    }
}
