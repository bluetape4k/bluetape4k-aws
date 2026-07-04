package io.bluetape4k.aws.kotlin.scheduler

import aws.sdk.kotlin.services.scheduler.SchedulerClient
import aws.sdk.kotlin.services.scheduler.createSchedule
import aws.sdk.kotlin.services.scheduler.createScheduleGroup
import aws.sdk.kotlin.services.scheduler.deleteSchedule
import aws.sdk.kotlin.services.scheduler.deleteScheduleGroup
import aws.sdk.kotlin.services.scheduler.getSchedule
import aws.sdk.kotlin.services.scheduler.getScheduleGroup
import aws.sdk.kotlin.services.scheduler.listScheduleGroups
import aws.sdk.kotlin.services.scheduler.listSchedules
import aws.sdk.kotlin.services.scheduler.model.CreateScheduleGroupResponse
import aws.sdk.kotlin.services.scheduler.model.CreateScheduleResponse
import aws.sdk.kotlin.services.scheduler.model.DeleteScheduleGroupResponse
import aws.sdk.kotlin.services.scheduler.model.DeleteScheduleResponse
import aws.sdk.kotlin.services.scheduler.model.FlexibleTimeWindow
import aws.sdk.kotlin.services.scheduler.model.GetScheduleGroupResponse
import aws.sdk.kotlin.services.scheduler.model.GetScheduleResponse
import aws.sdk.kotlin.services.scheduler.model.ListScheduleGroupsResponse
import aws.sdk.kotlin.services.scheduler.model.ListSchedulesResponse
import aws.sdk.kotlin.services.scheduler.model.ScheduleState
import aws.sdk.kotlin.services.scheduler.model.Target
import aws.sdk.kotlin.services.scheduler.model.UpdateScheduleResponse
import aws.sdk.kotlin.services.scheduler.updateSchedule
import aws.smithy.kotlin.runtime.time.Instant
import io.bluetape4k.aws.kotlin.scheduler.model.createScheduleGroupRequestOf
import io.bluetape4k.aws.kotlin.scheduler.model.createScheduleRequestOf
import io.bluetape4k.aws.kotlin.scheduler.model.deleteScheduleGroupRequestOf
import io.bluetape4k.aws.kotlin.scheduler.model.deleteScheduleRequestOf
import io.bluetape4k.aws.kotlin.scheduler.model.flexibleTimeWindowOff
import io.bluetape4k.aws.kotlin.scheduler.model.getScheduleGroupRequestOf
import io.bluetape4k.aws.kotlin.scheduler.model.getScheduleRequestOf
import io.bluetape4k.aws.kotlin.scheduler.model.listScheduleGroupsRequestOf
import io.bluetape4k.aws.kotlin.scheduler.model.listSchedulesRequestOf
import io.bluetape4k.aws.kotlin.scheduler.model.updateScheduleRequestOf

/** Creates a schedule and returns the raw Scheduler response. */
suspend fun SchedulerClient.createSchedule(
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

/** Updates a schedule with an explicit replacement request. */
suspend fun SchedulerClient.updateSchedule(
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

/** Deletes a schedule. */
suspend fun SchedulerClient.deleteSchedule(
    name: String,
    groupName: String? = null,
    clientToken: String? = null,
): DeleteScheduleResponse =
    deleteSchedule(deleteScheduleRequestOf(name, groupName, clientToken))

/** Reads a schedule. */
suspend fun SchedulerClient.getSchedule(
    name: String,
    groupName: String? = null,
): GetScheduleResponse =
    getSchedule(getScheduleRequestOf(name, groupName))

/** Lists schedules. */
suspend fun SchedulerClient.listSchedules(
    groupName: String? = null,
    namePrefix: String? = null,
    state: ScheduleState? = null,
    maxResults: Int? = null,
    nextToken: String? = null,
): ListSchedulesResponse =
    listSchedules(listSchedulesRequestOf(groupName, namePrefix, state, maxResults, nextToken))

/** Creates a schedule group. */
suspend fun SchedulerClient.createScheduleGroup(
    name: String,
    clientToken: String? = null,
): CreateScheduleGroupResponse =
    createScheduleGroup(createScheduleGroupRequestOf(name, clientToken))

/** Deletes a schedule group. */
suspend fun SchedulerClient.deleteScheduleGroup(
    name: String,
    clientToken: String? = null,
): DeleteScheduleGroupResponse =
    deleteScheduleGroup(deleteScheduleGroupRequestOf(name, clientToken))

/** Reads a schedule group. */
suspend fun SchedulerClient.getScheduleGroup(name: String): GetScheduleGroupResponse =
    getScheduleGroup(getScheduleGroupRequestOf(name))

/** Lists schedule groups. */
suspend fun SchedulerClient.listScheduleGroups(
    namePrefix: String? = null,
    maxResults: Int? = null,
    nextToken: String? = null,
): ListScheduleGroupsResponse =
    listScheduleGroups(listScheduleGroupsRequestOf(namePrefix, maxResults, nextToken))
