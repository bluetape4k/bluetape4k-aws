package io.bluetape4k.aws.kotlin.scheduler.model

import aws.sdk.kotlin.services.scheduler.model.CreateScheduleGroupRequest
import aws.sdk.kotlin.services.scheduler.model.CreateScheduleRequest
import aws.sdk.kotlin.services.scheduler.model.DeadLetterConfig
import aws.sdk.kotlin.services.scheduler.model.DeleteScheduleGroupRequest
import aws.sdk.kotlin.services.scheduler.model.DeleteScheduleRequest
import aws.sdk.kotlin.services.scheduler.model.FlexibleTimeWindow
import aws.sdk.kotlin.services.scheduler.model.FlexibleTimeWindowMode
import aws.sdk.kotlin.services.scheduler.model.GetScheduleGroupRequest
import aws.sdk.kotlin.services.scheduler.model.GetScheduleRequest
import aws.sdk.kotlin.services.scheduler.model.ListScheduleGroupsRequest
import aws.sdk.kotlin.services.scheduler.model.ListSchedulesRequest
import aws.sdk.kotlin.services.scheduler.model.RetryPolicy
import aws.sdk.kotlin.services.scheduler.model.ScheduleState
import aws.sdk.kotlin.services.scheduler.model.Target
import aws.sdk.kotlin.services.scheduler.model.UpdateScheduleRequest
import aws.smithy.kotlin.runtime.time.Instant
import io.bluetape4k.support.requireInRange
import io.bluetape4k.support.requireNotBlank

@PublishedApi
internal const val MIN_SCHEDULER_LIST_LIMIT = 1

@PublishedApi
internal const val MAX_SCHEDULER_LIST_LIMIT = 100

@PublishedApi
internal const val MIN_SCHEDULER_FLEXIBLE_WINDOW_MINUTES = 1

@PublishedApi
internal const val MAX_SCHEDULER_FLEXIBLE_WINDOW_MINUTES = 1_440

@PublishedApi
internal const val MIN_SCHEDULER_EVENT_AGE_SECONDS = 60

@PublishedApi
internal const val MAX_SCHEDULER_EVENT_AGE_SECONDS = 86_400

@PublishedApi
internal const val MIN_SCHEDULER_RETRY_ATTEMPTS = 0

@PublishedApi
internal const val MAX_SCHEDULER_RETRY_ATTEMPTS = 185

@PublishedApi
internal fun Int.requireSchedulerListLimit(name: String) {
    requireInRange(MIN_SCHEDULER_LIST_LIMIT, MAX_SCHEDULER_LIST_LIMIT, name)
}

@PublishedApi
internal fun Int.requireSchedulerFlexibleWindowMinutes(name: String) {
    requireInRange(MIN_SCHEDULER_FLEXIBLE_WINDOW_MINUTES, MAX_SCHEDULER_FLEXIBLE_WINDOW_MINUTES, name)
}

@PublishedApi
internal fun Int.requireSchedulerEventAgeSeconds(name: String) {
    requireInRange(MIN_SCHEDULER_EVENT_AGE_SECONDS, MAX_SCHEDULER_EVENT_AGE_SECONDS, name)
}

@PublishedApi
internal fun Int.requireSchedulerRetryAttempts(name: String) {
    requireInRange(MIN_SCHEDULER_RETRY_ATTEMPTS, MAX_SCHEDULER_RETRY_ATTEMPTS, name)
}

/** Builds an EventBridge Scheduler flexible time window. */
inline fun flexibleTimeWindowOf(
    mode: FlexibleTimeWindowMode = FlexibleTimeWindowMode.Off,
    maximumWindowInMinutes: Int? = null,
    crossinline builder: FlexibleTimeWindow.Builder.() -> Unit = {},
): FlexibleTimeWindow {
    maximumWindowInMinutes?.requireSchedulerFlexibleWindowMinutes("maximumWindowInMinutes")
    return FlexibleTimeWindow {
        this.mode = mode
        this.maximumWindowInMinutes = maximumWindowInMinutes
        builder()
    }
}

/** Builds the required OFF flexible time window used by precise schedules. */
fun flexibleTimeWindowOff(): FlexibleTimeWindow = flexibleTimeWindowOf(FlexibleTimeWindowMode.Off)

/** Builds an EventBridge Scheduler retry policy. */
inline fun retryPolicyOf(
    maximumEventAgeInSeconds: Int? = null,
    maximumRetryAttempts: Int? = null,
    crossinline builder: RetryPolicy.Builder.() -> Unit = {},
): RetryPolicy {
    maximumEventAgeInSeconds?.requireSchedulerEventAgeSeconds("maximumEventAgeInSeconds")
    maximumRetryAttempts?.requireSchedulerRetryAttempts("maximumRetryAttempts")
    return RetryPolicy {
        this.maximumEventAgeInSeconds = maximumEventAgeInSeconds
        this.maximumRetryAttempts = maximumRetryAttempts
        builder()
    }
}

/** Builds a dead-letter queue configuration for a Scheduler target. */
inline fun deadLetterConfigOf(
    arn: String,
    crossinline builder: DeadLetterConfig.Builder.() -> Unit = {},
): DeadLetterConfig {
    arn.requireNotBlank("arn")
    return DeadLetterConfig {
        this.arn = arn
        builder()
    }
}

/** Builds a Scheduler target with the required target ARN and execution role ARN. */
inline fun targetOf(
    arn: String,
    roleArn: String,
    input: String? = null,
    retryPolicy: RetryPolicy? = null,
    deadLetterConfig: DeadLetterConfig? = null,
    crossinline builder: Target.Builder.() -> Unit = {},
): Target {
    arn.requireNotBlank("arn")
    roleArn.requireNotBlank("roleArn")
    input?.requireNotBlank("input")
    return Target {
        this.arn = arn
        this.roleArn = roleArn
        this.input = input
        this.retryPolicy = retryPolicy
        this.deadLetterConfig = deadLetterConfig
        builder()
    }
}

/** Builds [CreateScheduleRequest] with the required schedule expression, target, and flexible time window. */
inline fun createScheduleRequestOf(
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
    crossinline builder: CreateScheduleRequest.Builder.() -> Unit = {},
): CreateScheduleRequest {
    name.requireNotBlank("name")
    scheduleExpression.requireNotBlank("scheduleExpression")
    groupName?.requireNotBlank("groupName")
    scheduleExpressionTimezone?.requireNotBlank("scheduleExpressionTimezone")
    description?.requireNotBlank("description")
    clientToken?.requireNotBlank("clientToken")
    kmsKeyArn?.requireNotBlank("kmsKeyArn")
    return CreateScheduleRequest {
        this.name = name
        this.scheduleExpression = scheduleExpression
        this.target = target
        this.flexibleTimeWindow = flexibleTimeWindow
        this.groupName = groupName
        this.scheduleExpressionTimezone = scheduleExpressionTimezone
        this.state = state
        this.startDate = startDate
        this.endDate = endDate
        this.description = description
        this.clientToken = clientToken
        this.kmsKeyArn = kmsKeyArn
        builder()
    }
}

/** Builds [UpdateScheduleRequest]. Scheduler updates replace omitted mutable fields with service defaults. */
inline fun updateScheduleRequestOf(
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
    crossinline builder: UpdateScheduleRequest.Builder.() -> Unit = {},
): UpdateScheduleRequest {
    name.requireNotBlank("name")
    scheduleExpression.requireNotBlank("scheduleExpression")
    groupName?.requireNotBlank("groupName")
    scheduleExpressionTimezone?.requireNotBlank("scheduleExpressionTimezone")
    description?.requireNotBlank("description")
    clientToken?.requireNotBlank("clientToken")
    kmsKeyArn?.requireNotBlank("kmsKeyArn")
    return UpdateScheduleRequest {
        this.name = name
        this.scheduleExpression = scheduleExpression
        this.target = target
        this.flexibleTimeWindow = flexibleTimeWindow
        this.groupName = groupName
        this.scheduleExpressionTimezone = scheduleExpressionTimezone
        this.state = state
        this.startDate = startDate
        this.endDate = endDate
        this.description = description
        this.clientToken = clientToken
        this.kmsKeyArn = kmsKeyArn
        builder()
    }
}

/** Builds [DeleteScheduleRequest]. */
inline fun deleteScheduleRequestOf(
    name: String,
    groupName: String? = null,
    clientToken: String? = null,
    crossinline builder: DeleteScheduleRequest.Builder.() -> Unit = {},
): DeleteScheduleRequest {
    name.requireNotBlank("name")
    groupName?.requireNotBlank("groupName")
    clientToken?.requireNotBlank("clientToken")
    return DeleteScheduleRequest {
        this.name = name
        this.groupName = groupName
        this.clientToken = clientToken
        builder()
    }
}

/** Builds [GetScheduleRequest]. */
inline fun getScheduleRequestOf(
    name: String,
    groupName: String? = null,
    crossinline builder: GetScheduleRequest.Builder.() -> Unit = {},
): GetScheduleRequest {
    name.requireNotBlank("name")
    groupName?.requireNotBlank("groupName")
    return GetScheduleRequest {
        this.name = name
        this.groupName = groupName
        builder()
    }
}

/** Builds [ListSchedulesRequest]. */
inline fun listSchedulesRequestOf(
    groupName: String? = null,
    namePrefix: String? = null,
    state: ScheduleState? = null,
    maxResults: Int? = null,
    nextToken: String? = null,
    crossinline builder: ListSchedulesRequest.Builder.() -> Unit = {},
): ListSchedulesRequest {
    groupName?.requireNotBlank("groupName")
    namePrefix?.requireNotBlank("namePrefix")
    maxResults?.requireSchedulerListLimit("maxResults")
    nextToken?.requireNotBlank("nextToken")
    return ListSchedulesRequest {
        this.groupName = groupName
        this.namePrefix = namePrefix
        this.state = state
        this.maxResults = maxResults
        this.nextToken = nextToken
        builder()
    }
}

/** Builds [CreateScheduleGroupRequest]. */
inline fun createScheduleGroupRequestOf(
    name: String,
    clientToken: String? = null,
    crossinline builder: CreateScheduleGroupRequest.Builder.() -> Unit = {},
): CreateScheduleGroupRequest {
    name.requireNotBlank("name")
    clientToken?.requireNotBlank("clientToken")
    return CreateScheduleGroupRequest {
        this.name = name
        this.clientToken = clientToken
        builder()
    }
}

/** Builds [DeleteScheduleGroupRequest]. */
inline fun deleteScheduleGroupRequestOf(
    name: String,
    clientToken: String? = null,
    crossinline builder: DeleteScheduleGroupRequest.Builder.() -> Unit = {},
): DeleteScheduleGroupRequest {
    name.requireNotBlank("name")
    clientToken?.requireNotBlank("clientToken")
    return DeleteScheduleGroupRequest {
        this.name = name
        this.clientToken = clientToken
        builder()
    }
}

/** Builds [GetScheduleGroupRequest]. */
inline fun getScheduleGroupRequestOf(
    name: String,
    crossinline builder: GetScheduleGroupRequest.Builder.() -> Unit = {},
): GetScheduleGroupRequest {
    name.requireNotBlank("name")
    return GetScheduleGroupRequest {
        this.name = name
        builder()
    }
}

/** Builds [ListScheduleGroupsRequest]. */
inline fun listScheduleGroupsRequestOf(
    namePrefix: String? = null,
    maxResults: Int? = null,
    nextToken: String? = null,
    crossinline builder: ListScheduleGroupsRequest.Builder.() -> Unit = {},
): ListScheduleGroupsRequest {
    namePrefix?.requireNotBlank("namePrefix")
    maxResults?.requireSchedulerListLimit("maxResults")
    nextToken?.requireNotBlank("nextToken")
    return ListScheduleGroupsRequest {
        this.namePrefix = namePrefix
        this.maxResults = maxResults
        this.nextToken = nextToken
        builder()
    }
}
