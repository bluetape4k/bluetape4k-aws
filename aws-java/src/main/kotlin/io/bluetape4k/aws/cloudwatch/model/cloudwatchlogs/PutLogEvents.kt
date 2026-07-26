package io.bluetape4k.aws.cloudwatch.model.cloudwatchlogs

import io.bluetape4k.support.requireNotBlank
import software.amazon.awssdk.services.cloudwatchlogs.model.InputLogEvent
import software.amazon.awssdk.services.cloudwatchlogs.model.PutLogEventsRequest

/**
 * Builds a [PutLogEventsRequest] with a DSL block.
 *
 * ```kotlin
 * val request = putLogEventsRequest {
 *     logGroupName("/aws/lambda/my-function")
 *     logStreamName("2024/01/01/[$LATEST]abc123")
 *     logEvents(listOf(inputLogEvent))
 * }
 * ```
 */
inline fun putLogEventsRequest(
    builder: PutLogEventsRequest.Builder.() -> Unit,
): PutLogEventsRequest =
    PutLogEventsRequest.builder().apply(builder).build()

/**
 * Creates a [PutLogEventsRequest] from a log group name, stream name, and event list.
 *
 * ## Behavior/Contract
 * - Throws `IllegalArgumentException` when [logGroupName] is blank.
 * - Throws `IllegalArgumentException` when [logStreamName] is blank.
 *
 * ```kotlin
 * val request = putLogEventsRequestOf(
 *     logGroupName = "/aws/lambda/my-function",
 *     logStreamName = "2024/01/01/[$LATEST]abc123",
 *     logEvents = listOf(inputLogEvent)
 * )
 * ```
 */
inline fun putLogEventsRequestOf(
    logGroupName: String,
    logStreamName: String,
    logEvents: List<InputLogEvent>,
    builder: PutLogEventsRequest.Builder.() -> Unit = {},
): PutLogEventsRequest {
    logGroupName.requireNotBlank("logGroupName")
    logStreamName.requireNotBlank("logStreamName")
    return putLogEventsRequest {
        logGroupName(logGroupName)
        logStreamName(logStreamName)
        logEvents(logEvents)
        builder()
    }
}

/**
 * Builds an [InputLogEvent] with a DSL block.
 *
 * ```kotlin
 * val event = inputLogEvent {
 *     timestamp(System.currentTimeMillis())
 *     message("Hello, CloudWatch Logs!")
 * }
 * ```
 */
inline fun inputLogEvent(
    builder: InputLogEvent.Builder.() -> Unit,
): InputLogEvent =
    InputLogEvent.builder().apply(builder).build()

/**
 * Creates an [InputLogEvent] from a timestamp and message.
 *
 * ```kotlin
 * val event = inputLogEventOf(
 *     timestamp = System.currentTimeMillis(),
 *     message = "Hello, CloudWatch Logs!"
 * )
 * ```
 */
inline fun inputLogEventOf(
    timestamp: Long,
    message: String,
    builder: InputLogEvent.Builder.() -> Unit = {},
): InputLogEvent = inputLogEvent {
    timestamp(timestamp)
    message(message)
    builder()
}
