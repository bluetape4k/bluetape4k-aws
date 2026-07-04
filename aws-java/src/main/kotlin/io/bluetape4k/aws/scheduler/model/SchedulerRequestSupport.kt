package io.bluetape4k.aws.scheduler.model

import io.bluetape4k.support.requireInRange
import io.bluetape4k.support.requireNotBlank
import software.amazon.awssdk.services.scheduler.model.CreateScheduleGroupRequest
import software.amazon.awssdk.services.scheduler.model.CreateScheduleRequest
import software.amazon.awssdk.services.scheduler.model.DeadLetterConfig
import software.amazon.awssdk.services.scheduler.model.DeleteScheduleGroupRequest
import software.amazon.awssdk.services.scheduler.model.DeleteScheduleRequest
import software.amazon.awssdk.services.scheduler.model.FlexibleTimeWindow
import software.amazon.awssdk.services.scheduler.model.FlexibleTimeWindowMode
import software.amazon.awssdk.services.scheduler.model.GetScheduleGroupRequest
import software.amazon.awssdk.services.scheduler.model.GetScheduleRequest
import software.amazon.awssdk.services.scheduler.model.ListScheduleGroupsRequest
import software.amazon.awssdk.services.scheduler.model.ListSchedulesRequest
import software.amazon.awssdk.services.scheduler.model.RetryPolicy
import software.amazon.awssdk.services.scheduler.model.ScheduleState
import software.amazon.awssdk.services.scheduler.model.Target
import software.amazon.awssdk.services.scheduler.model.UpdateScheduleRequest
import java.time.Instant

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
    mode: FlexibleTimeWindowMode = FlexibleTimeWindowMode.OFF,
    maximumWindowInMinutes: Int? = null,
    builder: FlexibleTimeWindow.Builder.() -> Unit = {},
): FlexibleTimeWindow {
    maximumWindowInMinutes?.requireSchedulerFlexibleWindowMinutes("maximumWindowInMinutes")
    return FlexibleTimeWindow.builder()
        .mode(mode)
        .also { maximumWindowInMinutes?.let(it::maximumWindowInMinutes) }
        .apply(builder)
        .build()
}

/** Builds the required OFF flexible time window used by precise schedules. */
fun flexibleTimeWindowOff(): FlexibleTimeWindow = flexibleTimeWindowOf(FlexibleTimeWindowMode.OFF)

/** Builds an EventBridge Scheduler retry policy. */
inline fun retryPolicyOf(
    maximumEventAgeInSeconds: Int? = null,
    maximumRetryAttempts: Int? = null,
    builder: RetryPolicy.Builder.() -> Unit = {},
): RetryPolicy {
    maximumEventAgeInSeconds?.requireSchedulerEventAgeSeconds("maximumEventAgeInSeconds")
    maximumRetryAttempts?.requireSchedulerRetryAttempts("maximumRetryAttempts")
    return RetryPolicy.builder()
        .also { maximumEventAgeInSeconds?.let(it::maximumEventAgeInSeconds) }
        .also { maximumRetryAttempts?.let(it::maximumRetryAttempts) }
        .apply(builder)
        .build()
}

/** Builds a dead-letter queue configuration for a Scheduler target. */
inline fun deadLetterConfigOf(
    arn: String,
    builder: DeadLetterConfig.Builder.() -> Unit = {},
): DeadLetterConfig {
    arn.requireNotBlank("arn")
    return DeadLetterConfig.builder()
        .arn(arn)
        .apply(builder)
        .build()
}

/** Builds a Scheduler target with the required target ARN and execution role ARN. */
inline fun targetOf(
    arn: String,
    roleArn: String,
    input: String? = null,
    retryPolicy: RetryPolicy? = null,
    deadLetterConfig: DeadLetterConfig? = null,
    builder: Target.Builder.() -> Unit = {},
): Target {
    arn.requireNotBlank("arn")
    roleArn.requireNotBlank("roleArn")
    input?.requireNotBlank("input")
    return Target.builder()
        .arn(arn)
        .roleArn(roleArn)
        .also { input?.let(it::input) }
        .also { retryPolicy?.let(it::retryPolicy) }
        .also { deadLetterConfig?.let(it::deadLetterConfig) }
        .apply(builder)
        .build()
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
    builder: CreateScheduleRequest.Builder.() -> Unit = {},
): CreateScheduleRequest {
    name.requireNotBlank("name")
    scheduleExpression.requireNotBlank("scheduleExpression")
    groupName?.requireNotBlank("groupName")
    scheduleExpressionTimezone?.requireNotBlank("scheduleExpressionTimezone")
    description?.requireNotBlank("description")
    clientToken?.requireNotBlank("clientToken")
    kmsKeyArn?.requireNotBlank("kmsKeyArn")
    return CreateScheduleRequest.builder()
        .name(name)
        .scheduleExpression(scheduleExpression)
        .target(target)
        .flexibleTimeWindow(flexibleTimeWindow)
        .also { groupName?.let(it::groupName) }
        .also { scheduleExpressionTimezone?.let(it::scheduleExpressionTimezone) }
        .also { state?.let(it::state) }
        .also { startDate?.let(it::startDate) }
        .also { endDate?.let(it::endDate) }
        .also { description?.let(it::description) }
        .also { clientToken?.let(it::clientToken) }
        .also { kmsKeyArn?.let(it::kmsKeyArn) }
        .apply(builder)
        .build()
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
    builder: UpdateScheduleRequest.Builder.() -> Unit = {},
): UpdateScheduleRequest {
    name.requireNotBlank("name")
    scheduleExpression.requireNotBlank("scheduleExpression")
    groupName?.requireNotBlank("groupName")
    scheduleExpressionTimezone?.requireNotBlank("scheduleExpressionTimezone")
    description?.requireNotBlank("description")
    clientToken?.requireNotBlank("clientToken")
    kmsKeyArn?.requireNotBlank("kmsKeyArn")
    return UpdateScheduleRequest.builder()
        .name(name)
        .scheduleExpression(scheduleExpression)
        .target(target)
        .flexibleTimeWindow(flexibleTimeWindow)
        .also { groupName?.let(it::groupName) }
        .also { scheduleExpressionTimezone?.let(it::scheduleExpressionTimezone) }
        .also { state?.let(it::state) }
        .also { startDate?.let(it::startDate) }
        .also { endDate?.let(it::endDate) }
        .also { description?.let(it::description) }
        .also { clientToken?.let(it::clientToken) }
        .also { kmsKeyArn?.let(it::kmsKeyArn) }
        .apply(builder)
        .build()
}

/** Builds [DeleteScheduleRequest]. */
inline fun deleteScheduleRequestOf(
    name: String,
    groupName: String? = null,
    clientToken: String? = null,
    builder: DeleteScheduleRequest.Builder.() -> Unit = {},
): DeleteScheduleRequest {
    name.requireNotBlank("name")
    groupName?.requireNotBlank("groupName")
    clientToken?.requireNotBlank("clientToken")
    return DeleteScheduleRequest.builder()
        .name(name)
        .also { groupName?.let(it::groupName) }
        .also { clientToken?.let(it::clientToken) }
        .apply(builder)
        .build()
}

/** Builds [GetScheduleRequest]. */
inline fun getScheduleRequestOf(
    name: String,
    groupName: String? = null,
    builder: GetScheduleRequest.Builder.() -> Unit = {},
): GetScheduleRequest {
    name.requireNotBlank("name")
    groupName?.requireNotBlank("groupName")
    return GetScheduleRequest.builder()
        .name(name)
        .also { groupName?.let(it::groupName) }
        .apply(builder)
        .build()
}

/** Builds [ListSchedulesRequest]. */
inline fun listSchedulesRequestOf(
    groupName: String? = null,
    namePrefix: String? = null,
    state: ScheduleState? = null,
    maxResults: Int? = null,
    nextToken: String? = null,
    builder: ListSchedulesRequest.Builder.() -> Unit = {},
): ListSchedulesRequest {
    groupName?.requireNotBlank("groupName")
    namePrefix?.requireNotBlank("namePrefix")
    maxResults?.requireSchedulerListLimit("maxResults")
    nextToken?.requireNotBlank("nextToken")
    return ListSchedulesRequest.builder()
        .also { groupName?.let(it::groupName) }
        .also { namePrefix?.let(it::namePrefix) }
        .also { state?.let(it::state) }
        .also { maxResults?.let(it::maxResults) }
        .also { nextToken?.let(it::nextToken) }
        .apply(builder)
        .build()
}

/** Builds [CreateScheduleGroupRequest]. */
inline fun createScheduleGroupRequestOf(
    name: String,
    clientToken: String? = null,
    builder: CreateScheduleGroupRequest.Builder.() -> Unit = {},
): CreateScheduleGroupRequest {
    name.requireNotBlank("name")
    clientToken?.requireNotBlank("clientToken")
    return CreateScheduleGroupRequest.builder()
        .name(name)
        .also { clientToken?.let(it::clientToken) }
        .apply(builder)
        .build()
}

/** Builds [DeleteScheduleGroupRequest]. */
inline fun deleteScheduleGroupRequestOf(
    name: String,
    clientToken: String? = null,
    builder: DeleteScheduleGroupRequest.Builder.() -> Unit = {},
): DeleteScheduleGroupRequest {
    name.requireNotBlank("name")
    clientToken?.requireNotBlank("clientToken")
    return DeleteScheduleGroupRequest.builder()
        .name(name)
        .also { clientToken?.let(it::clientToken) }
        .apply(builder)
        .build()
}

/** Builds [GetScheduleGroupRequest]. */
inline fun getScheduleGroupRequestOf(
    name: String,
    builder: GetScheduleGroupRequest.Builder.() -> Unit = {},
): GetScheduleGroupRequest {
    name.requireNotBlank("name")
    return GetScheduleGroupRequest.builder()
        .name(name)
        .apply(builder)
        .build()
}

/** Builds [ListScheduleGroupsRequest]. */
inline fun listScheduleGroupsRequestOf(
    namePrefix: String? = null,
    maxResults: Int? = null,
    nextToken: String? = null,
    builder: ListScheduleGroupsRequest.Builder.() -> Unit = {},
): ListScheduleGroupsRequest {
    namePrefix?.requireNotBlank("namePrefix")
    maxResults?.requireSchedulerListLimit("maxResults")
    nextToken?.requireNotBlank("nextToken")
    return ListScheduleGroupsRequest.builder()
        .also { namePrefix?.let(it::namePrefix) }
        .also { maxResults?.let(it::maxResults) }
        .also { nextToken?.let(it::nextToken) }
        .apply(builder)
        .build()
}
