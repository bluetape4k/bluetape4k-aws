package io.bluetape4k.aws.kotlin.scheduler

import aws.sdk.kotlin.services.scheduler.SchedulerClient
import aws.sdk.kotlin.services.scheduler.model.CreateScheduleRequest
import aws.sdk.kotlin.services.scheduler.model.CreateScheduleResponse
import aws.sdk.kotlin.services.scheduler.model.ListSchedulesRequest
import aws.sdk.kotlin.services.scheduler.model.ListSchedulesResponse
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeSameInstanceAs
import io.bluetape4k.aws.kotlin.scheduler.model.targetOf
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class SchedulerClientExtensionsTest {

    private val client = mockk<SchedulerClient>()

    @BeforeEach
    fun setup() {
        clearMocks(client)
    }

    @Test
    fun `createSchedule delegates once and preserves raw response`() = runTest {
        val target = targetOf(
            arn = "arn:aws:scheduler:::aws-sdk:sqs:sendMessage",
            roleArn = "arn:aws:iam::123456789012:role/scheduler-role",
        )
        val expected = CreateScheduleResponse {
            scheduleArn = "arn:aws:scheduler:us-east-1:123456789012:schedule/jobs/daily-job"
        }
        coEvery { client.createSchedule(any<CreateScheduleRequest>()) } returns expected

        val result = client.createSchedule(
            name = "daily-job",
            scheduleExpression = "rate(1 day)",
            target = target,
            groupName = "jobs",
        )

        result shouldBeSameInstanceAs expected
        result.scheduleArn shouldBeEqualTo "arn:aws:scheduler:us-east-1:123456789012:schedule/jobs/daily-job"
        coVerify(exactly = 1) { client.createSchedule(any<CreateScheduleRequest>()) }
    }

    @Test
    fun `listSchedules delegates once with mapped request`() = runTest {
        val expected = ListSchedulesResponse {
            schedules = emptyList()
            nextToken = "next"
        }
        coEvery { client.listSchedules(any<ListSchedulesRequest>()) } returns expected

        val result = client.listSchedules(groupName = "jobs", namePrefix = "daily", maxResults = 10)

        result shouldBeSameInstanceAs expected
        result.nextToken shouldBeEqualTo "next"
        coVerify(exactly = 1) { client.listSchedules(any<ListSchedulesRequest>()) }
    }
}
