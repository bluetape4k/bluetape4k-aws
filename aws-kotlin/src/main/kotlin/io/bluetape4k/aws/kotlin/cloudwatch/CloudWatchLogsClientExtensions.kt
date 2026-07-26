package io.bluetape4k.aws.kotlin.cloudwatch

import aws.sdk.kotlin.services.cloudwatchlogs.CloudWatchLogsClient
import aws.sdk.kotlin.services.cloudwatchlogs.createLogGroup
import aws.sdk.kotlin.services.cloudwatchlogs.createLogStream
import aws.sdk.kotlin.services.cloudwatchlogs.describeLogGroups
import aws.sdk.kotlin.services.cloudwatchlogs.describeLogStreams
import aws.sdk.kotlin.services.cloudwatchlogs.model.CreateLogGroupRequest
import aws.sdk.kotlin.services.cloudwatchlogs.model.CreateLogGroupResponse
import aws.sdk.kotlin.services.cloudwatchlogs.model.CreateLogStreamRequest
import aws.sdk.kotlin.services.cloudwatchlogs.model.CreateLogStreamResponse
import aws.sdk.kotlin.services.cloudwatchlogs.model.DescribeLogGroupsRequest
import aws.sdk.kotlin.services.cloudwatchlogs.model.DescribeLogGroupsResponse
import aws.sdk.kotlin.services.cloudwatchlogs.model.DescribeLogStreamsRequest
import aws.sdk.kotlin.services.cloudwatchlogs.model.DescribeLogStreamsResponse
import aws.sdk.kotlin.services.cloudwatchlogs.model.InputLogEvent
import aws.sdk.kotlin.services.cloudwatchlogs.model.PutLogEventsRequest
import aws.sdk.kotlin.services.cloudwatchlogs.model.PutLogEventsResponse
import aws.sdk.kotlin.services.cloudwatchlogs.putLogEvents
import io.bluetape4k.support.requireNotBlank

/**
 * Creates a CloudWatch Logs group named [logGroupName].
 *
 * ```kotlin
 * val response = cloudWatchLogsClient.createLogGroup("/aws/lambda/my-function")
 * ```
 *
 * @param logGroupName name of the log group to create
 * @param builder additional configuration for [CreateLogGroupRequest.Builder]
 * @return the [CreateLogGroupResponse]
 */
suspend inline fun CloudWatchLogsClient.createLogGroup(
    logGroupName: String,
    crossinline builder: CreateLogGroupRequest.Builder.() -> Unit = {},
): CreateLogGroupResponse {
    logGroupName.requireNotBlank("logGroupName")
    return createLogGroup {
        this.logGroupName = logGroupName
        builder()
    }
}

/**
 * Creates a CloudWatch Logs stream named [logStreamName] in [logGroupName].
 *
 * ```kotlin
 * val response = cloudWatchLogsClient.createLogStream(
 *     logGroupName = "/aws/lambda/my-function",
 *     logStreamName = "2024/01/01/[$LATEST]abc123"
 * )
 * ```
 *
 * @param logGroupName log group name
 * @param logStreamName name of the log stream to create
 * @param builder additional configuration for [CreateLogStreamRequest.Builder]
 * @return the [CreateLogStreamResponse]
 */
suspend inline fun CloudWatchLogsClient.createLogStream(
    logGroupName: String,
    logStreamName: String,
    crossinline builder: CreateLogStreamRequest.Builder.() -> Unit = {},
): CreateLogStreamResponse {
    logGroupName.requireNotBlank("logGroupName")
    logStreamName.requireNotBlank("logStreamName")
    return createLogStream {
        this.logGroupName = logGroupName
        this.logStreamName = logStreamName
        builder()
    }
}

/**
 * Publishes [logEvents] to [logStreamName] in [logGroupName].
 *
 * ```kotlin
 * val response = cloudWatchLogsClient.putLogEvents(
 *     logGroupName = "/aws/lambda/my-function",
 *     logStreamName = "2024/01/01/[$LATEST]abc123",
 *     logEvents = listOf(inputLogEvent)
 * )
 * ```
 *
 * @param logGroupName log group name
 * @param logStreamName log stream name
 * @param logEvents log events to publish
 * @param builder additional configuration for [PutLogEventsRequest.Builder]
 * @return the [PutLogEventsResponse]
 */
suspend inline fun CloudWatchLogsClient.putLogEvents(
    logGroupName: String,
    logStreamName: String,
    logEvents: List<InputLogEvent>,
    crossinline builder: PutLogEventsRequest.Builder.() -> Unit = {},
): PutLogEventsResponse {
    logGroupName.requireNotBlank("logGroupName")
    logStreamName.requireNotBlank("logStreamName")
    return putLogEvents {
        this.logGroupName = logGroupName
        this.logStreamName = logStreamName
        this.logEvents = logEvents
        builder()
    }
}

/**
 * Lists CloudWatch Logs groups.
 *
 * ```kotlin
 * val response = cloudWatchLogsClient.describeLogGroups(logGroupNamePrefix = "/aws/lambda")
 * response.logGroups?.forEach { group -> println(group.logGroupName) }
 * ```
 *
 * @param logGroupNamePrefix log group name prefix; when null, lists all groups
 * @param builder additional configuration for [DescribeLogGroupsRequest.Builder]
 * @return the [DescribeLogGroupsResponse]
 */
suspend inline fun CloudWatchLogsClient.describeLogGroups(
    logGroupNamePrefix: String? = null,
    crossinline builder: DescribeLogGroupsRequest.Builder.() -> Unit = {},
): DescribeLogGroupsResponse =
    describeLogGroups {
        logGroupNamePrefix?.let { this.logGroupNamePrefix = it }
        builder()
    }

/**
 * Lists log streams in [logGroupName].
 *
 * ```kotlin
 * val response = cloudWatchLogsClient.describeLogStreams("/aws/lambda/my-function")
 * response.logStreams?.forEach { stream -> println(stream.logStreamName) }
 * ```
 *
 * @param logGroupName log group name
 * @param logStreamNamePrefix log stream name prefix; when null, does not filter by prefix
 * @param builder additional configuration for [DescribeLogStreamsRequest.Builder]
 * @return the [DescribeLogStreamsResponse]
 */
suspend inline fun CloudWatchLogsClient.describeLogStreams(
    logGroupName: String,
    logStreamNamePrefix: String? = null,
    crossinline builder: DescribeLogStreamsRequest.Builder.() -> Unit = {},
): DescribeLogStreamsResponse {
    logGroupName.requireNotBlank("logGroupName")
    return describeLogStreams {
        this.logGroupName = logGroupName
        logStreamNamePrefix?.let { this.logStreamNamePrefix = it }
        builder()
    }
}
