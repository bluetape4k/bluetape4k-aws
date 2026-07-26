package io.bluetape4k.aws.cloudwatch

import io.bluetape4k.support.requireNotBlank
import software.amazon.awssdk.services.cloudwatchlogs.CloudWatchLogsClient
import software.amazon.awssdk.services.cloudwatchlogs.model.CreateLogGroupResponse
import software.amazon.awssdk.services.cloudwatchlogs.model.CreateLogStreamResponse
import software.amazon.awssdk.services.cloudwatchlogs.model.DescribeLogGroupsResponse
import software.amazon.awssdk.services.cloudwatchlogs.model.DescribeLogStreamsResponse
import software.amazon.awssdk.services.cloudwatchlogs.model.InputLogEvent
import software.amazon.awssdk.services.cloudwatchlogs.model.PutLogEventsResponse

/**
 * Creates a CloudWatch Logs group with [logGroupName].
 *
 * ## Behavior/Contract
 * - Throws `IllegalArgumentException` when [logGroupName] is blank.
 *
 * ```kotlin
 * val response = cloudWatchLogsClient.createLogGroup("/aws/lambda/my-function")
 * response.sdkHttpResponse().statusCode() == 200
 * ```
 */
fun CloudWatchLogsClient.createLogGroup(
    logGroupName: String,
): CreateLogGroupResponse {
    logGroupName.requireNotBlank("logGroupName")
    return createLogGroup { it.logGroupName(logGroupName) }
}

/**
 * Creates a CloudWatch Logs stream with [logGroupName] and [logStreamName].
 *
 * ## Behavior/Contract
 * - Throws `IllegalArgumentException` when [logGroupName] is blank.
 * - Throws `IllegalArgumentException` when [logStreamName] is blank.
 *
 * ```kotlin
 * val response = cloudWatchLogsClient.createLogStream(
 *     logGroupName = "/aws/lambda/my-function",
 *     logStreamName = "2024/01/01/[$LATEST]abc123"
 * )
 * ```
 */
fun CloudWatchLogsClient.createLogStream(
    logGroupName: String,
    logStreamName: String,
): CreateLogStreamResponse {
    logGroupName.requireNotBlank("logGroupName")
    logStreamName.requireNotBlank("logStreamName")
    return createLogStream {
        it.logGroupName(logGroupName)
        it.logStreamName(logStreamName)
    }
}

/**
 * Publishes [logEvents] to the [logStreamName] stream in [logGroupName].
 *
 * ## Behavior/Contract
 * - Throws `IllegalArgumentException` when [logGroupName] is blank.
 * - Throws `IllegalArgumentException` when [logStreamName] is blank.
 *
 * ```kotlin
 * val response = cloudWatchLogsClient.putLogEvents(
 *     logGroupName = "/aws/lambda/my-function",
 *     logStreamName = "2024/01/01/[$LATEST]abc123",
 *     logEvents = listOf(inputLogEvent)
 * )
 * ```
 */
fun CloudWatchLogsClient.putLogEvents(
    logGroupName: String,
    logStreamName: String,
    logEvents: List<InputLogEvent>,
): PutLogEventsResponse {
    logGroupName.requireNotBlank("logGroupName")
    logStreamName.requireNotBlank("logStreamName")
    return putLogEvents {
        it.logGroupName(logGroupName)
        it.logStreamName(logStreamName)
        it.logEvents(logEvents)
    }
}

/**
 * Lists CloudWatch Logs groups.
 *
 * ```kotlin
 * val response = cloudWatchLogsClient.describeLogGroups(logGroupNamePrefix = "/aws/lambda")
 * response.logGroups().forEach { group -> println(group.logGroupName()) }
 * ```
 */
fun CloudWatchLogsClient.describeLogGroups(
    logGroupNamePrefix: String? = null,
): DescribeLogGroupsResponse =
    describeLogGroups {
        logGroupNamePrefix?.let { prefix -> it.logGroupNamePrefix(prefix) }
    }

/**
 * Lists log streams in [logGroupName].
 *
 * ## Behavior/Contract
 * - Throws `IllegalArgumentException` when [logGroupName] is blank.
 *
 * ```kotlin
 * val response = cloudWatchLogsClient.describeLogStreams("/aws/lambda/my-function")
 * response.logStreams().forEach { stream -> println(stream.logStreamName()) }
 * ```
 */
fun CloudWatchLogsClient.describeLogStreams(
    logGroupName: String,
    logStreamNamePrefix: String? = null,
): DescribeLogStreamsResponse {
    logGroupName.requireNotBlank("logGroupName")
    return describeLogStreams {
        it.logGroupName(logGroupName)
        logStreamNamePrefix?.let { prefix -> it.logStreamNamePrefix(prefix) }
    }
}
