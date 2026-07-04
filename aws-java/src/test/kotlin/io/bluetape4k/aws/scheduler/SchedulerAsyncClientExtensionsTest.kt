package io.bluetape4k.aws.scheduler

import io.bluetape4k.assertions.shouldBeSameInstanceAs
import io.bluetape4k.aws.scheduler.model.targetOf
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import software.amazon.awssdk.services.scheduler.SchedulerAsyncClient
import software.amazon.awssdk.services.scheduler.model.CreateScheduleRequest
import software.amazon.awssdk.services.scheduler.model.CreateScheduleResponse
import java.util.concurrent.CompletableFuture

class SchedulerAsyncClientExtensionsTest {

    private val client = mockk<SchedulerAsyncClient>()

    @Test
    fun `createScheduleAsync delegates once and preserves future`() {
        val target = targetOf(
            arn = "arn:aws:scheduler:::aws-sdk:sqs:sendMessage",
            roleArn = "arn:aws:iam::123456789012:role/scheduler-role",
        )
        val expected = CompletableFuture.completedFuture(CreateScheduleResponse.builder().build())
        every { client.createSchedule(any<CreateScheduleRequest>()) } returns expected

        val result = client.createScheduleAsync("daily-job", "rate(1 day)", target)

        result shouldBeSameInstanceAs expected
        verify(exactly = 1) { client.createSchedule(any<CreateScheduleRequest>()) }
    }
}
