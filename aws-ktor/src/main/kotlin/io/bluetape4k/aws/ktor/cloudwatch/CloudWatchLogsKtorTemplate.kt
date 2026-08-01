package io.bluetape4k.aws.ktor.cloudwatch

import io.bluetape4k.aws.cloudwatch.createLogGroup
import io.bluetape4k.aws.cloudwatch.createLogStream
import io.bluetape4k.aws.cloudwatch.describeLogGroups
import io.bluetape4k.aws.cloudwatch.describeLogStreams
import io.bluetape4k.aws.cloudwatch.putLogEvents
import io.bluetape4k.support.requireInRange
import io.bluetape4k.support.requireNotBlank
import software.amazon.awssdk.services.cloudwatchlogs.CloudWatchLogsAsyncClient
import software.amazon.awssdk.services.cloudwatchlogs.model.CreateLogGroupResponse
import software.amazon.awssdk.services.cloudwatchlogs.model.CreateLogStreamResponse
import software.amazon.awssdk.services.cloudwatchlogs.model.DescribeLogGroupsResponse
import software.amazon.awssdk.services.cloudwatchlogs.model.DescribeLogStreamsResponse
import software.amazon.awssdk.services.cloudwatchlogs.model.InputLogEvent
import software.amazon.awssdk.services.cloudwatchlogs.model.PutLogEventsResponse

internal const val CLOUDWATCH_LOGS_MIN_BATCH_SIZE = 1
internal const val CLOUDWATCH_LOGS_MAX_BATCH_SIZE = 10000

/**
 * [CloudWatchLogsAsyncClient]를 사용하는 기본 [CloudWatchLogsKtorOperations] 구현입니다.
 *
 * ## 계약
 *
 * 로그 이벤트는 [batchSize] 단위로 나눕니다. 빈 이벤트 목록은 아무 작업도 하지 않으며
 * AWS를 호출하지 않습니다.
 */
class CloudWatchLogsKtorTemplate(
    private val cloudWatchLogsAsyncClient: CloudWatchLogsAsyncClient,
    private val logStream: CloudWatchLogStream? = null,
    private val batchSize: Int = CLOUDWATCH_LOGS_MAX_BATCH_SIZE,
): CloudWatchLogsKtorOperations {

    init {
        batchSize.requireInRange(CLOUDWATCH_LOGS_MIN_BATCH_SIZE, CLOUDWATCH_LOGS_MAX_BATCH_SIZE, "batchSize")
    }

    override suspend fun createLogGroup(logGroupName: String): CreateLogGroupResponse {
        logGroupName.requireNotBlank("logGroupName")
        return cloudWatchLogsAsyncClient.createLogGroup(logGroupName)
    }

    override suspend fun createLogStream(logStream: CloudWatchLogStream): CreateLogStreamResponse =
        cloudWatchLogsAsyncClient.createLogStream(logStream.logGroupName, logStream.logStreamName)

    override suspend fun putLogEvents(logEvents: List<InputLogEvent>): List<PutLogEventsResponse> =
        putLogEvents(defaultLogStream(), logEvents)

    override suspend fun putLogEvents(
        logStream: CloudWatchLogStream,
        logEvents: List<InputLogEvent>,
    ): List<PutLogEventsResponse> {
        if (logEvents.isEmpty()) {
            return emptyList()
        }

        return logEvents.chunked(batchSize).map { batch ->
            cloudWatchLogsAsyncClient.putLogEvents(logStream.logGroupName, logStream.logStreamName, batch)
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

    private fun defaultLogStream(): CloudWatchLogStream =
        logStream ?: throw IllegalArgumentException("logGroupName and logStreamName must be configured.")
}
