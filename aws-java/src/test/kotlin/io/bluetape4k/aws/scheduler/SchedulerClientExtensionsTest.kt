package io.bluetape4k.aws.scheduler

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeSameInstanceAs
import io.bluetape4k.aws.scheduler.model.targetOf
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import software.amazon.awssdk.services.scheduler.SchedulerClient
import software.amazon.awssdk.services.scheduler.model.CreateScheduleRequest
import software.amazon.awssdk.services.scheduler.model.CreateScheduleResponse
import software.amazon.awssdk.services.scheduler.model.ListSchedulesRequest
import software.amazon.awssdk.services.scheduler.model.ListSchedulesResponse

class SchedulerClientExtensionsTest {

    private val client = mockk<SchedulerClient>()

    @Test
    fun `createSchedule delegates once and preserves raw response`() {
        val target = targetOf(
            arn = "arn:aws:scheduler:::aws-sdk:sqs:sendMessage",
            roleArn = "arn:aws:iam::123456789012:role/scheduler-role",
        )
        val expected = CreateScheduleResponse.builder().scheduleArn("arn:aws:scheduler:us-east-1:123456789012:schedule/jobs/daily-job").build()
        every { client.createSchedule(any<CreateScheduleRequest>()) } returns expected

        val result = client.createSchedule(
            name = "daily-job",
            scheduleExpression = "rate(1 day)",
            target = target,
            groupName = "jobs",
        )

        result shouldBeSameInstanceAs expected
        result.scheduleArn() shouldBeEqualTo "arn:aws:scheduler:us-east-1:123456789012:schedule/jobs/daily-job"
        verify(exactly = 1) { client.createSchedule(any<CreateScheduleRequest>()) }
    }

    @Test
    fun `listSchedules delegates once with mapped request`() {
        val expected = ListSchedulesResponse.builder().nextToken("next").build()
        every { client.listSchedules(any<ListSchedulesRequest>()) } returns expected

        val result = client.listSchedules(groupName = "jobs", namePrefix = "daily", maxResults = 10)

        result shouldBeSameInstanceAs expected
        result.nextToken() shouldBeEqualTo "next"
        verify(exactly = 1) { client.listSchedules(any<ListSchedulesRequest>()) }
    }
}
