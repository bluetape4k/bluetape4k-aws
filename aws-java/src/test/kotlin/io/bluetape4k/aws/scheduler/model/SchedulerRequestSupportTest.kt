package io.bluetape4k.aws.scheduler.model

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import org.junit.jupiter.api.Test
import software.amazon.awssdk.services.scheduler.model.FlexibleTimeWindowMode
import software.amazon.awssdk.services.scheduler.model.ScheduleState

class SchedulerRequestSupportTest {

    @Test
    fun `target builder maps required target arn and role arn`() {
        val retryPolicy = retryPolicyOf(maximumEventAgeInSeconds = 60, maximumRetryAttempts = 3)
        val deadLetterConfig = deadLetterConfigOf("arn:aws:sqs:us-east-1:123456789012:dlq")

        val target = targetOf(
            arn = "arn:aws:scheduler:::aws-sdk:sqs:sendMessage",
            roleArn = "arn:aws:iam::123456789012:role/scheduler-role",
            input = "{}",
            retryPolicy = retryPolicy,
            deadLetterConfig = deadLetterConfig,
        )

        target.arn() shouldBeEqualTo "arn:aws:scheduler:::aws-sdk:sqs:sendMessage"
        target.roleArn() shouldBeEqualTo "arn:aws:iam::123456789012:role/scheduler-role"
        target.input() shouldBeEqualTo "{}"
        target.retryPolicy().maximumRetryAttempts() shouldBeEqualTo 3
        target.deadLetterConfig().arn() shouldBeEqualTo "arn:aws:sqs:us-east-1:123456789012:dlq"
    }

    @Test
    fun `schedule request maps expression target group state and window`() {
        val target = targetOf(
            arn = "arn:aws:scheduler:::aws-sdk:lambda:invoke",
            roleArn = "arn:aws:iam::123456789012:role/scheduler-role",
        )
        val window = flexibleTimeWindowOf(
            mode = FlexibleTimeWindowMode.FLEXIBLE,
            maximumWindowInMinutes = 15,
        )

        val request = createScheduleRequestOf(
            name = "daily-job",
            scheduleExpression = "rate(1 day)",
            target = target,
            flexibleTimeWindow = window,
            groupName = "jobs",
            scheduleExpressionTimezone = "UTC",
            state = ScheduleState.ENABLED,
            description = "Daily job",
            clientToken = "token-1",
        )

        request.name() shouldBeEqualTo "daily-job"
        request.groupName() shouldBeEqualTo "jobs"
        request.scheduleExpression() shouldBeEqualTo "rate(1 day)"
        request.scheduleExpressionTimezone() shouldBeEqualTo "UTC"
        request.state() shouldBeEqualTo ScheduleState.ENABLED
        request.description() shouldBeEqualTo "Daily job"
        request.clientToken() shouldBeEqualTo "token-1"
        request.flexibleTimeWindow().maximumWindowInMinutes() shouldBeEqualTo 15
        request.flexibleTimeWindow().mode() shouldBeEqualTo FlexibleTimeWindowMode.FLEXIBLE
    }

    @Test
    fun `validation rejects blank and out of range scheduler values`() {
        assertFailsWith<IllegalArgumentException> {
            targetOf(" ", "arn:aws:iam::123456789012:role/scheduler-role")
        }
        assertFailsWith<IllegalArgumentException> {
            flexibleTimeWindowOf(FlexibleTimeWindowMode.FLEXIBLE, maximumWindowInMinutes = 0)
        }
        assertFailsWith<IllegalArgumentException> {
            retryPolicyOf(maximumEventAgeInSeconds = 59)
        }
        assertFailsWith<IllegalArgumentException> {
            retryPolicyOf(maximumRetryAttempts = 186)
        }
        assertFailsWith<IllegalArgumentException> {
            listSchedulesRequestOf(maxResults = 101)
        }
    }
}
