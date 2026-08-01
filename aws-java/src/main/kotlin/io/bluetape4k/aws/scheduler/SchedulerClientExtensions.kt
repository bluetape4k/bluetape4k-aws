package io.bluetape4k.aws.scheduler

import io.bluetape4k.aws.scheduler.model.createScheduleGroupRequestOf
import io.bluetape4k.aws.scheduler.model.createScheduleRequestOf
import io.bluetape4k.aws.scheduler.model.deleteScheduleGroupRequestOf
import io.bluetape4k.aws.scheduler.model.deleteScheduleRequestOf
import io.bluetape4k.aws.scheduler.model.flexibleTimeWindowOff
import io.bluetape4k.aws.scheduler.model.getScheduleGroupRequestOf
import io.bluetape4k.aws.scheduler.model.getScheduleRequestOf
import io.bluetape4k.aws.scheduler.model.listScheduleGroupsRequestOf
import io.bluetape4k.aws.scheduler.model.listSchedulesRequestOf
import io.bluetape4k.aws.scheduler.model.updateScheduleRequestOf
import software.amazon.awssdk.services.scheduler.SchedulerClient
import software.amazon.awssdk.services.scheduler.model.CreateScheduleGroupResponse
import software.amazon.awssdk.services.scheduler.model.CreateScheduleResponse
import software.amazon.awssdk.services.scheduler.model.DeleteScheduleGroupResponse
import software.amazon.awssdk.services.scheduler.model.DeleteScheduleResponse
import software.amazon.awssdk.services.scheduler.model.FlexibleTimeWindow
import software.amazon.awssdk.services.scheduler.model.GetScheduleGroupResponse
import software.amazon.awssdk.services.scheduler.model.GetScheduleResponse
import software.amazon.awssdk.services.scheduler.model.ListScheduleGroupsResponse
import software.amazon.awssdk.services.scheduler.model.ListSchedulesResponse
import software.amazon.awssdk.services.scheduler.model.ScheduleState
import software.amazon.awssdk.services.scheduler.model.Target
import software.amazon.awssdk.services.scheduler.model.UpdateScheduleResponse
import java.time.Instant

/** 스케줄을 생성하고 Scheduler 원본 응답을 반환합니다. */
fun SchedulerClient.createSchedule(
    name: String,
    scheduleExpression: String,
    target: Target,
    flexibleTimeWindow: FlexibleTimeWindow = flexibleTimeWindowOff(),
    groupName: String? = null,
    scheduleExpressionTimezone: String? = null,
    state: ScheduleState? = null,
    startDate: Instant? = null,
    endDate: Instant? = null,
    description: String? = null,
    clientToken: String? = null,
    kmsKeyArn: String? = null,
): CreateScheduleResponse =
    createSchedule(
        createScheduleRequestOf(
            name = name,
            scheduleExpression = scheduleExpression,
            target = target,
            flexibleTimeWindow = flexibleTimeWindow,
            groupName = groupName,
            scheduleExpressionTimezone = scheduleExpressionTimezone,
            state = state,
            startDate = startDate,
            endDate = endDate,
            description = description,
            clientToken = clientToken,
            kmsKeyArn = kmsKeyArn,
        ),
    )

/** 명시적인 대체 요청으로 스케줄을 갱신합니다. */
fun SchedulerClient.updateSchedule(
    name: String,
    scheduleExpression: String,
    target: Target,
    flexibleTimeWindow: FlexibleTimeWindow = flexibleTimeWindowOff(),
    groupName: String? = null,
    scheduleExpressionTimezone: String? = null,
    state: ScheduleState? = null,
    startDate: Instant? = null,
    endDate: Instant? = null,
    description: String? = null,
    clientToken: String? = null,
    kmsKeyArn: String? = null,
): UpdateScheduleResponse =
    updateSchedule(
        updateScheduleRequestOf(
            name = name,
            scheduleExpression = scheduleExpression,
            target = target,
            flexibleTimeWindow = flexibleTimeWindow,
            groupName = groupName,
            scheduleExpressionTimezone = scheduleExpressionTimezone,
            state = state,
            startDate = startDate,
            endDate = endDate,
            description = description,
            clientToken = clientToken,
            kmsKeyArn = kmsKeyArn,
        ),
    )

/** 스케줄을 삭제합니다. */
fun SchedulerClient.deleteSchedule(
    name: String,
    groupName: String? = null,
    clientToken: String? = null,
): DeleteScheduleResponse =
    deleteSchedule(deleteScheduleRequestOf(name, groupName, clientToken))

/** 스케줄을 조회합니다. */
fun SchedulerClient.getSchedule(
    name: String,
    groupName: String? = null,
): GetScheduleResponse =
    getSchedule(getScheduleRequestOf(name, groupName))

/** 스케줄 목록을 조회합니다. */
fun SchedulerClient.listSchedules(
    groupName: String? = null,
    namePrefix: String? = null,
    state: ScheduleState? = null,
    maxResults: Int? = null,
    nextToken: String? = null,
): ListSchedulesResponse =
    listSchedules(listSchedulesRequestOf(groupName, namePrefix, state, maxResults, nextToken))

/** 스케줄 그룹을 생성합니다. */
fun SchedulerClient.createScheduleGroup(
    name: String,
    clientToken: String? = null,
): CreateScheduleGroupResponse =
    createScheduleGroup(createScheduleGroupRequestOf(name, clientToken))

/** 스케줄 그룹을 삭제합니다. */
fun SchedulerClient.deleteScheduleGroup(
    name: String,
    clientToken: String? = null,
): DeleteScheduleGroupResponse =
    deleteScheduleGroup(deleteScheduleGroupRequestOf(name, clientToken))

/** 스케줄 그룹을 조회합니다. */
fun SchedulerClient.getScheduleGroup(name: String): GetScheduleGroupResponse =
    getScheduleGroup(getScheduleGroupRequestOf(name))

/** 스케줄 그룹 목록을 조회합니다. */
fun SchedulerClient.listScheduleGroups(
    namePrefix: String? = null,
    maxResults: Int? = null,
    nextToken: String? = null,
): ListScheduleGroupsResponse =
    listScheduleGroups(listScheduleGroupsRequestOf(namePrefix, maxResults, nextToken))
