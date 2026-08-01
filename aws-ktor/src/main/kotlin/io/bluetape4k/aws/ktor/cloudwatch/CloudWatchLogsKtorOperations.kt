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
 * CloudWatch Logs 스트림 식별자입니다.
 *
 * ## 계약
 *
 * [logGroupName]과 [logStreamName]은 모두 비어 있지 않아야 합니다. 두 문자열 식별자의
 * 위치를 혼동하지 않도록 이 값 객체를 사용하세요.
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
 * Ktor 애플리케이션을 위한 코루틴 CloudWatch Logs 작업입니다.
 *
 * ## 계약
 *
 * 작업을 호출할 때만 AWS를 호출합니다. 기본 스트림 메서드는 설치된
 * [CloudWatchLogsKtorPlugin]에 구성한 로그 그룹과 스트림이 필요합니다.
 */
interface CloudWatchLogsKtorOperations {

    /**
     * [logGroupName]을 생성합니다.
     */
    suspend fun createLogGroup(logGroupName: String): CreateLogGroupResponse

    /**
     * [logStream]을 생성합니다.
     */
    suspend fun createLogStream(logStream: CloudWatchLogStream): CreateLogStreamResponse

    /**
     * [logEvents]를 구성된 기본 로그 스트림에 게시합니다.
     */
    suspend fun putLogEvents(logEvents: List<InputLogEvent>): List<PutLogEventsResponse>

    /**
     * [logEvents]를 [logStream]에 게시합니다.
     */
    suspend fun putLogEvents(
        logStream: CloudWatchLogStream,
        logEvents: List<InputLogEvent>,
    ): List<PutLogEventsResponse>

    /**
     * CloudWatch Logs 그룹을 조회합니다.
     */
    suspend fun describeLogGroups(logGroupNamePrefix: String? = null): DescribeLogGroupsResponse

    /**
     * [logGroupName]의 스트림을 조회합니다.
     */
    suspend fun describeLogStreams(
        logGroupName: String,
        logStreamNamePrefix: String? = null,
    ): DescribeLogStreamsResponse
}
