package io.bluetape4k.aws.spring.cloudwatch

import io.bluetape4k.aws.cloudwatch.createLogGroup
import io.bluetape4k.aws.cloudwatch.createLogStream
import io.bluetape4k.aws.cloudwatch.describeLogGroups
import io.bluetape4k.aws.cloudwatch.describeLogStreams
import io.bluetape4k.aws.cloudwatch.putLogEvents
import io.bluetape4k.support.requireNotBlank
import software.amazon.awssdk.services.cloudwatchlogs.CloudWatchLogsAsyncClient
import software.amazon.awssdk.services.cloudwatchlogs.model.CreateLogGroupResponse
import software.amazon.awssdk.services.cloudwatchlogs.model.CreateLogStreamResponse
import software.amazon.awssdk.services.cloudwatchlogs.model.DescribeLogGroupsResponse
import software.amazon.awssdk.services.cloudwatchlogs.model.DescribeLogStreamsResponse
import software.amazon.awssdk.services.cloudwatchlogs.model.InputLogEvent
import software.amazon.awssdk.services.cloudwatchlogs.model.PutLogEventsResponse

/**
 * AWS SDK v2 [CloudWatchLogsAsyncClient]를 사용하는 코루틴 친화적인 [CloudWatchLogsOperations]입니다.
 */
class CloudWatchLogsCoroutinesTemplate(
    private val cloudWatchLogsAsyncClient: CloudWatchLogsAsyncClient,
    private val properties: CloudWatchLogsProperties,
): CloudWatchLogsOperations {

    override suspend fun createLogGroup(logGroupName: String): CreateLogGroupResponse {
        logGroupName.requireNotBlank("logGroupName")
        return cloudWatchLogsAsyncClient.createLogGroup(logGroupName)
    }

    override suspend fun createLogStream(
        logGroupName: String,
        logStreamName: String,
    ): CreateLogStreamResponse {
        logGroupName.requireNotBlank("logGroupName")
        logStreamName.requireNotBlank("logStreamName")
        return cloudWatchLogsAsyncClient.createLogStream(logGroupName, logStreamName)
    }

    override suspend fun putLogEvents(logEvents: List<InputLogEvent>): List<PutLogEventsResponse> =
        putLogEvents(requireDefaultLogGroupName(), requireDefaultLogStreamName(), logEvents)

    override suspend fun putLogEvents(
        logGroupName: String,
        logStreamName: String,
        logEvents: List<InputLogEvent>,
    ): List<PutLogEventsResponse> {
        logGroupName.requireNotBlank("logGroupName")
        logStreamName.requireNotBlank("logStreamName")
        if (logEvents.isEmpty()) {
            return emptyList()
        }

        return logEvents.chunked(properties.batchSize).map { batch ->
            cloudWatchLogsAsyncClient.putLogEvents(logGroupName, logStreamName, batch)
        }
    }

    override suspend fun describeLogGroups(logGroupNamePrefix: String?): DescribeLogGroupsResponse =
        cloudWatchLogsAsyncClient.describeLogGroups(logGroupNamePrefix)

    override suspend fun describeLogStreams(
        logGroupName: String,
        logStreamNamePrefix: String?,
    ): DescribeLogStreamsResponse {
        logGroupName.requireNotBlank("logGroupName")
        return cloudWatchLogsAsyncClient.describeLogStreams(logGroupName, logStreamNamePrefix)
    }

    private fun requireDefaultLogGroupName(): String =
        properties.logGroupName?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("$CLOUDWATCH_LOGS_PROPERTIES_PREFIX.log-group-name is required.")

    private fun requireDefaultLogStreamName(): String =
        properties.logStreamName?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("$CLOUDWATCH_LOGS_PROPERTIES_PREFIX.log-stream-name is required.")
}
