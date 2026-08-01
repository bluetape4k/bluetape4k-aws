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
import software.amazon.awssdk.services.scheduler.SchedulerAsyncClient
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
import java.util.concurrent.CompletableFuture

/** 스케줄을 비동기로 생성합니다. */
fun SchedulerAsyncClient.createScheduleAsync(
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
): CompletableFuture<CreateScheduleResponse> =
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

/** 스케줄을 비동기로 갱신합니다. */
fun SchedulerAsyncClient.updateScheduleAsync(
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
): CompletableFuture<UpdateScheduleResponse> =
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

/** 스케줄을 비동기로 삭제합니다. */
fun SchedulerAsyncClient.deleteScheduleAsync(
    name: String,
    groupName: String? = null,
    clientToken: String? = null,
): CompletableFuture<DeleteScheduleResponse> =
    deleteSchedule(deleteScheduleRequestOf(name, groupName, clientToken))

/** 스케줄을 비동기로 조회합니다. */
fun SchedulerAsyncClient.getScheduleAsync(
    name: String,
    groupName: String? = null,
): CompletableFuture<GetScheduleResponse> =
    getSchedule(getScheduleRequestOf(name, groupName))

/** 스케줄 목록을 비동기로 조회합니다. */
fun SchedulerAsyncClient.listSchedulesAsync(
    groupName: String? = null,
    namePrefix: String? = null,
    state: ScheduleState? = null,
    maxResults: Int? = null,
    nextToken: String? = null,
): CompletableFuture<ListSchedulesResponse> =
    listSchedules(listSchedulesRequestOf(groupName, namePrefix, state, maxResults, nextToken))

/** 스케줄 그룹을 비동기로 생성합니다. */
fun SchedulerAsyncClient.createScheduleGroupAsync(
    name: String,
    clientToken: String? = null,
): CompletableFuture<CreateScheduleGroupResponse> =
    createScheduleGroup(createScheduleGroupRequestOf(name, clientToken))

/** 스케줄 그룹을 비동기로 삭제합니다. */
fun SchedulerAsyncClient.deleteScheduleGroupAsync(
    name: String,
    clientToken: String? = null,
): CompletableFuture<DeleteScheduleGroupResponse> =
    deleteScheduleGroup(deleteScheduleGroupRequestOf(name, clientToken))

/** 스케줄 그룹을 비동기로 조회합니다. */
fun SchedulerAsyncClient.getScheduleGroupAsync(name: String): CompletableFuture<GetScheduleGroupResponse> =
    getScheduleGroup(getScheduleGroupRequestOf(name))

/** 스케줄 그룹 목록을 비동기로 조회합니다. */
fun SchedulerAsyncClient.listScheduleGroupsAsync(
    namePrefix: String? = null,
    maxResults: Int? = null,
    nextToken: String? = null,
): CompletableFuture<ListScheduleGroupsResponse> =
    listScheduleGroups(listScheduleGroupsRequestOf(namePrefix, maxResults, nextToken))
