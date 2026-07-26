package io.bluetape4k.aws.kotlin.cloudwatch.model.cloudwatchlogs

import aws.sdk.kotlin.services.cloudwatchlogs.model.InputLogEvent

/**
 * Builds an [InputLogEvent] with a DSL block.
 *
 * ```kotlin
 * val event = inputLogEvent {
 *     timestamp = System.currentTimeMillis()
 *     message = "Hello, CloudWatch Logs!"
 * }
 * ```
 */
inline fun inputLogEvent(
    crossinline builder: InputLogEvent.Builder.() -> Unit,
): InputLogEvent =
    InputLogEvent { builder() }

/**
 * Creates an [InputLogEvent] from a timestamp and message.
 *
 * ```kotlin
 * val event = inputLogEventOf(
 *     timestamp = System.currentTimeMillis(),
 *     message = "Hello, CloudWatch Logs!"
 * )
 * ```
 *
 * @param timestamp event timestamp in Unix epoch milliseconds
 * @param message log message
 * @param builder additional configuration for [InputLogEvent.Builder]
 * @return the [InputLogEvent]
 */
inline fun inputLogEventOf(
    timestamp: Long,
    message: String,
    crossinline builder: InputLogEvent.Builder.() -> Unit = {},
): InputLogEvent = inputLogEvent {
    this.timestamp = timestamp
    this.message = message
    builder()
}
