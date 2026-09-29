package io.bluetape4k.aws.scheduler

import io.bluetape4k.assertions.shouldBeSameInstanceAs
import io.bluetape4k.aws.scheduler.model.targetOf
import io.bluetape4k.concurrent.completableFutureOf
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import software.amazon.awssdk.services.scheduler.SchedulerAsyncClient
import software.amazon.awssdk.services.scheduler.model.CreateScheduleRequest
import software.amazon.awssdk.services.scheduler.model.CreateScheduleResponse

class SchedulerAsyncClientExtensionsTest {

    companion object: KLogging()

    private val client = mockk<SchedulerAsyncClient>()

    @BeforeEach
    fun beforeEach() {
        clearMocks(client)
    }

    @Test
    fun `createScheduleAsync delegates once and preserves future`() {
        val target = targetOf(
            arn = "arn:aws:scheduler:::aws-sdk:sqs:sendMessage",
            roleArn = "arn:aws:iam::123456789012:role/scheduler-role",
        )
        log.debug { "target=$target" }
        val expected = completableFutureOf(CreateScheduleResponse.builder().build())

        every { client.createSchedule(any<CreateScheduleRequest>()) } returns expected

        val result = client.createScheduleAsync("daily-job", "rate(1 day)", target)
        result shouldBeSameInstanceAs expected

        verify(exactly = 1) { client.createSchedule(any<CreateScheduleRequest>()) }
    }
}
