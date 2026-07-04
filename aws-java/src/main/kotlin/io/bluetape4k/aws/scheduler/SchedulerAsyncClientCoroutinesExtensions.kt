package io.bluetape4k.aws.scheduler

import io.bluetape4k.aws.scheduler.model.flexibleTimeWindowOff
import kotlinx.coroutines.future.await
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

/** Coroutine adapter for [createScheduleAsync]. */
suspend fun SchedulerAsyncClient.createSchedule(
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
    createScheduleAsync(
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
    ).await()

/** Coroutine adapter for [updateScheduleAsync]. */
suspend fun SchedulerAsyncClient.updateSchedule(
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
    updateScheduleAsync(
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
    ).await()

/** Coroutine adapter for [deleteScheduleAsync]. */
suspend fun SchedulerAsyncClient.deleteSchedule(
    name: String,
    groupName: String? = null,
    clientToken: String? = null,
): DeleteScheduleResponse =
    deleteScheduleAsync(name, groupName, clientToken).await()

/** Coroutine adapter for [getScheduleAsync]. */
suspend fun SchedulerAsyncClient.getSchedule(
    name: String,
    groupName: String? = null,
): GetScheduleResponse =
    getScheduleAsync(name, groupName).await()

/** Coroutine adapter for [listSchedulesAsync]. */
suspend fun SchedulerAsyncClient.listSchedules(
    groupName: String? = null,
    namePrefix: String? = null,
    state: ScheduleState? = null,
    maxResults: Int? = null,
    nextToken: String? = null,
): ListSchedulesResponse =
    listSchedulesAsync(groupName, namePrefix, state, maxResults, nextToken).await()

/** Coroutine adapter for [createScheduleGroupAsync]. */
suspend fun SchedulerAsyncClient.createScheduleGroup(
    name: String,
    clientToken: String? = null,
): CreateScheduleGroupResponse =
    createScheduleGroupAsync(name, clientToken).await()

/** Coroutine adapter for [deleteScheduleGroupAsync]. */
suspend fun SchedulerAsyncClient.deleteScheduleGroup(
    name: String,
    clientToken: String? = null,
): DeleteScheduleGroupResponse =
    deleteScheduleGroupAsync(name, clientToken).await()

/** Coroutine adapter for [getScheduleGroupAsync]. */
suspend fun SchedulerAsyncClient.getScheduleGroup(name: String): GetScheduleGroupResponse =
    getScheduleGroupAsync(name).await()

/** Coroutine adapter for [listScheduleGroupsAsync]. */
suspend fun SchedulerAsyncClient.listScheduleGroups(
    namePrefix: String? = null,
    maxResults: Int? = null,
    nextToken: String? = null,
): ListScheduleGroupsResponse =
    listScheduleGroupsAsync(namePrefix, maxResults, nextToken).await()
