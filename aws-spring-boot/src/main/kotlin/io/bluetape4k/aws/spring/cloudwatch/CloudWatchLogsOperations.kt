package io.bluetape4k.aws.spring.cloudwatch

import software.amazon.awssdk.services.cloudwatchlogs.model.CreateLogGroupResponse
import software.amazon.awssdk.services.cloudwatchlogs.model.CreateLogStreamResponse
import software.amazon.awssdk.services.cloudwatchlogs.model.DescribeLogGroupsResponse
import software.amazon.awssdk.services.cloudwatchlogs.model.DescribeLogStreamsResponse
import software.amazon.awssdk.services.cloudwatchlogs.model.InputLogEvent
import software.amazon.awssdk.services.cloudwatchlogs.model.PutLogEventsResponse

/**
 * Spring 애플리케이션을 위한 코루틴 기반 CloudWatch Logs 작업입니다.
 */
interface CloudWatchLogsOperations {

    /**
     * [logGroupName]이라는 CloudWatch Logs 로그 그룹을 생성합니다.
     */
    suspend fun createLogGroup(logGroupName: String): CreateLogGroupResponse

    /**
     * [logGroupName] 안에 [logStreamName]이라는 로그 스트림을 생성합니다.
     */
    suspend fun createLogStream(
        logGroupName: String,
        logStreamName: String,
    ): CreateLogStreamResponse

    /**
     * [logEvents]를 구성된 기본 로그 그룹과 스트림에 게시합니다.
     */
    suspend fun putLogEvents(logEvents: List<InputLogEvent>): List<PutLogEventsResponse>

    /**
     * [logEvents]를 지정한 [logGroupName]과 [logStreamName]에 게시합니다.
     */
    suspend fun putLogEvents(
        logGroupName: String,
        logStreamName: String,
        logEvents: List<InputLogEvent>,
    ): List<PutLogEventsResponse>

    /**
     * 로그 그룹 목록을 조회하며, 선택적으로 [logGroupNamePrefix]로 제한합니다.
     */
    suspend fun describeLogGroups(logGroupNamePrefix: String? = null): DescribeLogGroupsResponse

    /**
     * [logGroupName]의 로그 스트림 목록을 조회하며, 선택적으로 [logStreamNamePrefix]로 제한합니다.
     */
    suspend fun describeLogStreams(
        logGroupName: String,
        logStreamNamePrefix: String? = null,
    ): DescribeLogStreamsResponse
}
