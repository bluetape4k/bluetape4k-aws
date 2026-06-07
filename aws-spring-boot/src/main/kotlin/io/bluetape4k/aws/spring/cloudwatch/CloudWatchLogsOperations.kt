package io.bluetape4k.aws.spring.cloudwatch

import software.amazon.awssdk.services.cloudwatchlogs.model.CreateLogGroupResponse
import software.amazon.awssdk.services.cloudwatchlogs.model.CreateLogStreamResponse
import software.amazon.awssdk.services.cloudwatchlogs.model.DescribeLogGroupsResponse
import software.amazon.awssdk.services.cloudwatchlogs.model.DescribeLogStreamsResponse
import software.amazon.awssdk.services.cloudwatchlogs.model.InputLogEvent
import software.amazon.awssdk.services.cloudwatchlogs.model.PutLogEventsResponse

/**
 * Coroutine-based CloudWatch Logs operations for Spring applications.
 */
interface CloudWatchLogsOperations {

    /**
     * Creates a CloudWatch Logs log group named [logGroupName].
     */
    suspend fun createLogGroup(logGroupName: String): CreateLogGroupResponse

    /**
     * Creates a log stream named [logStreamName] inside [logGroupName].
     */
    suspend fun createLogStream(
        logGroupName: String,
        logStreamName: String,
    ): CreateLogStreamResponse

    /**
     * Publishes [logEvents] to the configured default log group and stream.
     */
    suspend fun putLogEvents(logEvents: List<InputLogEvent>): List<PutLogEventsResponse>

    /**
     * Publishes [logEvents] to the given [logGroupName] and [logStreamName].
     */
    suspend fun putLogEvents(
        logGroupName: String,
        logStreamName: String,
        logEvents: List<InputLogEvent>,
    ): List<PutLogEventsResponse>

    /**
     * Lists log groups, optionally restricted by [logGroupNamePrefix].
     */
    suspend fun describeLogGroups(logGroupNamePrefix: String? = null): DescribeLogGroupsResponse

    /**
     * Lists log streams in [logGroupName], optionally restricted by [logStreamNamePrefix].
     */
    suspend fun describeLogStreams(
        logGroupName: String,
        logStreamNamePrefix: String? = null,
    ): DescribeLogStreamsResponse
}
