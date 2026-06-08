package io.bluetape4k.aws.ktor.cloudwatch

import io.bluetape4k.support.requireNotBlank
import software.amazon.awssdk.services.cloudwatchlogs.model.CreateLogGroupResponse
import software.amazon.awssdk.services.cloudwatchlogs.model.CreateLogStreamResponse
import software.amazon.awssdk.services.cloudwatchlogs.model.DescribeLogGroupsResponse
import software.amazon.awssdk.services.cloudwatchlogs.model.DescribeLogStreamsResponse
import software.amazon.awssdk.services.cloudwatchlogs.model.InputLogEvent
import software.amazon.awssdk.services.cloudwatchlogs.model.PutLogEventsResponse
import java.io.Serializable

/**
 * CloudWatch Logs stream identity.
 *
 * ## Contract
 *
 * Both [logGroupName] and [logStreamName] must be non-blank. Use this value
 * object to avoid positional mistakes between the two string identifiers.
 */
data class CloudWatchLogStream(
    val logGroupName: String,
    val logStreamName: String,
): Serializable {
    init {
        logGroupName.requireNotBlank("logGroupName")
        logStreamName.requireNotBlank("logStreamName")
    }

    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

/**
 * Coroutine CloudWatch Logs operations for Ktor applications.
 *
 * ## Contract
 *
 * Operations call AWS only when invoked. Default-stream methods require the log
 * group and stream configured for the installed [CloudWatchLogsKtorPlugin].
 */
interface CloudWatchLogsKtorOperations {

    /**
     * Creates [logGroupName].
     */
    suspend fun createLogGroup(logGroupName: String): CreateLogGroupResponse

    /**
     * Creates [logStream].
     */
    suspend fun createLogStream(logStream: CloudWatchLogStream): CreateLogStreamResponse

    /**
     * Publishes [logEvents] to the configured default log stream.
     */
    suspend fun putLogEvents(logEvents: List<InputLogEvent>): List<PutLogEventsResponse>

    /**
     * Publishes [logEvents] to [logStream].
     */
    suspend fun putLogEvents(
        logStream: CloudWatchLogStream,
        logEvents: List<InputLogEvent>,
    ): List<PutLogEventsResponse>

    /**
     * Describes CloudWatch Logs groups.
     */
    suspend fun describeLogGroups(logGroupNamePrefix: String? = null): DescribeLogGroupsResponse

    /**
     * Describes streams in [logGroupName].
     */
    suspend fun describeLogStreams(
        logGroupName: String,
        logStreamNamePrefix: String? = null,
    ): DescribeLogStreamsResponse
}
