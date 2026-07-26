package io.bluetape4k.aws.cloudwatch

import io.bluetape4k.support.requireNotBlank
import kotlinx.coroutines.future.await
import software.amazon.awssdk.services.cloudwatchlogs.CloudWatchLogsAsyncClient
import software.amazon.awssdk.services.cloudwatchlogs.model.CreateLogGroupResponse
import software.amazon.awssdk.services.cloudwatchlogs.model.CreateLogStreamResponse
import software.amazon.awssdk.services.cloudwatchlogs.model.DescribeLogGroupsResponse
import software.amazon.awssdk.services.cloudwatchlogs.model.DescribeLogStreamsResponse
import software.amazon.awssdk.services.cloudwatchlogs.model.InputLogEvent
import software.amazon.awssdk.services.cloudwatchlogs.model.PutLogEventsResponse

/**
 * Creates a CloudWatch Logs group with [logGroupName] using coroutines.
 *
 * ## Behavior/Contract
 * - Throws `IllegalArgumentException` when [logGroupName] is blank.
 *
 * ```kotlin
 * val response = cloudWatchLogsAsyncClient.createLogGroup("/aws/lambda/my-function")
 * ```
 */
suspend fun CloudWatchLogsAsyncClient.createLogGroup(
    logGroupName: String,
): CreateLogGroupResponse {
    logGroupName.requireNotBlank("logGroupName")
    return createLogGroup { it.logGroupName(logGroupName) }.await()
}

/**
 * Creates a CloudWatch Logs stream with [logGroupName] and [logStreamName] using coroutines.
 *
 * ## Behavior/Contract
 * - Throws `IllegalArgumentException` when [logGroupName] is blank.
 * - Throws `IllegalArgumentException` when [logStreamName] is blank.
 *
 * ```kotlin
 * val response = cloudWatchLogsAsyncClient.createLogStream(
 *     logGroupName = "/aws/lambda/my-function",
 *     logStreamName = "2024/01/01/[$LATEST]abc123"
 * )
 * ```
 */
suspend fun CloudWatchLogsAsyncClient.createLogStream(
    logGroupName: String,
    logStreamName: String,
): CreateLogStreamResponse {
    logGroupName.requireNotBlank("logGroupName")
    logStreamName.requireNotBlank("logStreamName")
    return createLogStream {
        it.logGroupName(logGroupName)
        it.logStreamName(logStreamName)
    }.await()
}

/**
 * Publishes [logEvents] to the [logStreamName] stream in [logGroupName] using coroutines.
 *
 * ## Behavior/Contract
 * - Throws `IllegalArgumentException` when [logGroupName] is blank.
 * - Throws `IllegalArgumentException` when [logStreamName] is blank.
 *
 * ```kotlin
 * val response = cloudWatchLogsAsyncClient.putLogEvents(
 *     logGroupName = "/aws/lambda/my-function",
 *     logStreamName = "2024/01/01/[$LATEST]abc123",
 *     logEvents = listOf(inputLogEvent)
 * )
 * ```
 */
suspend fun CloudWatchLogsAsyncClient.putLogEvents(
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
    }.await()
}

/**
 * Lists CloudWatch Logs groups using coroutines.
 *
 * ```kotlin
 * val response = cloudWatchLogsAsyncClient.describeLogGroups(logGroupNamePrefix = "/aws/lambda")
 * response.logGroups().forEach { group -> println(group.logGroupName()) }
 * ```
 */
suspend fun CloudWatchLogsAsyncClient.describeLogGroups(
    logGroupNamePrefix: String? = null,
): DescribeLogGroupsResponse =
    describeLogGroups {
        logGroupNamePrefix?.let { prefix -> it.logGroupNamePrefix(prefix) }
    }.await()

/**
 * Lists log streams in [logGroupName] using coroutines.
 *
 * ## Behavior/Contract
 * - Throws `IllegalArgumentException` when [logGroupName] is blank.
 *
 * ```kotlin
 * val response = cloudWatchLogsAsyncClient.describeLogStreams("/aws/lambda/my-function")
 * response.logStreams().forEach { stream -> println(stream.logStreamName()) }
 * ```
 */
suspend fun CloudWatchLogsAsyncClient.describeLogStreams(
    logGroupName: String,
    logStreamNamePrefix: String? = null,
): DescribeLogStreamsResponse {
    logGroupName.requireNotBlank("logGroupName")
    return describeLogStreams {
        it.logGroupName(logGroupName)
        logStreamNamePrefix?.let { prefix -> it.logStreamNamePrefix(prefix) }
    }.await()
}
