package io.bluetape4k.aws.ktor.cloudwatch

import aws.sdk.kotlin.services.cloudwatchlogs.CloudWatchLogsClient
import aws.sdk.kotlin.services.cloudwatchlogs.model.CreateLogGroupResponse
import aws.sdk.kotlin.services.cloudwatchlogs.model.CreateLogStreamResponse
import aws.sdk.kotlin.services.cloudwatchlogs.model.DescribeLogGroupsResponse
import aws.sdk.kotlin.services.cloudwatchlogs.model.DescribeLogStreamsResponse
import aws.sdk.kotlin.services.cloudwatchlogs.model.InputLogEvent
import aws.sdk.kotlin.services.cloudwatchlogs.model.PutLogEventsResponse
import io.bluetape4k.aws.kotlin.cloudwatch.createLogGroup
import io.bluetape4k.aws.kotlin.cloudwatch.createLogStream
import io.bluetape4k.aws.kotlin.cloudwatch.describeLogGroups
import io.bluetape4k.aws.kotlin.cloudwatch.describeLogStreams
import io.bluetape4k.aws.kotlin.cloudwatch.putLogEvents
import io.bluetape4k.support.requireInRange
import io.bluetape4k.support.requireNotBlank

internal const val CLOUDWATCH_LOGS_MIN_BATCH_SIZE = 1
internal const val CLOUDWATCH_LOGS_MAX_BATCH_SIZE = 10000

/**
 * AWS Kotlin SDK [CloudWatchLogsClient]를 사용하는 기본 [CloudWatchLogsKtorOperations] 구현입니다.
 *
 * ## 계약
 *
 * 로그 이벤트는 [batchSize] 단위로 나눕니다. 빈 이벤트 목록은 아무 작업도 하지 않으며
 * AWS를 호출하지 않습니다.
 */
class CloudWatchLogsKtorTemplate(
    private val cloudWatchLogsClient: CloudWatchLogsClient,
    private val logStream: CloudWatchLogStream? = null,
    private val batchSize: Int = CLOUDWATCH_LOGS_MAX_BATCH_SIZE,
): CloudWatchLogsKtorOperations {

    init {
        batchSize.requireInRange(CLOUDWATCH_LOGS_MIN_BATCH_SIZE, CLOUDWATCH_LOGS_MAX_BATCH_SIZE, "batchSize")
    }

    override suspend fun createLogGroup(logGroupName: String): CreateLogGroupResponse {
        logGroupName.requireNotBlank("logGroupName")
        return cloudWatchLogsClient.createLogGroup(logGroupName)
    }

    override suspend fun createLogStream(logStream: CloudWatchLogStream): CreateLogStreamResponse =
        cloudWatchLogsClient.createLogStream(logStream.logGroupName, logStream.logStreamName)

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
            cloudWatchLogsClient.putLogEvents(logStream.logGroupName, logStream.logStreamName, batch)
        }
    }

    override suspend fun describeLogGroups(logGroupNamePrefix: String?): DescribeLogGroupsResponse =
        cloudWatchLogsClient.describeLogGroups(logGroupNamePrefix)

    override suspend fun describeLogStreams(
        logGroupName: String,
        logStreamNamePrefix: String?,
    ): DescribeLogStreamsResponse {
        logGroupName.requireNotBlank("logGroupName")
        return cloudWatchLogsClient.describeLogStreams(logGroupName, logStreamNamePrefix)
    }

    private fun defaultLogStream(): CloudWatchLogStream =
        logStream ?: throw IllegalArgumentException("logGroupName and logStreamName must be configured.")
}
