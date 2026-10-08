package io.bluetape4k.aws.sqs

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.concurrent.completableFutureOf
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.debug
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import software.amazon.awssdk.services.sqs.SqsAsyncClient
import software.amazon.awssdk.services.sqs.model.SendMessageRequest
import software.amazon.awssdk.services.sqs.model.SendMessageResponse

class SqsAsyncClientCoroutinesExtensionsTest {

    companion object: KLoggingChannel()

    private val client = mockk<SqsAsyncClient>()

    @BeforeEach
    fun beforeEach() {
        clearMocks(client)
    }

    @Test
    fun `sendSuspend는 sendMessage 결과를 반환한다`() = runTest {
        val requestSlot = slot<SendMessageRequest>()
        val response = SendMessageResponse.builder().messageId("mid-1").build()

        every {
            client.sendMessage(capture(requestSlot))
        } returns completableFutureOf(response)

        val result = client.send(
            queueUrl = "https://sqs.ap-northeast-2.amazonaws.com/000000000000/test-queue",
            messageBody = "hello",
        )
        log.debug { "SendMessageResponse=$result" }
        result.messageId() shouldBeEqualTo "mid-1"
        requestSlot.captured.queueUrl() shouldBeEqualTo "https://sqs.ap-northeast-2.amazonaws.com/000000000000/test-queue"
        requestSlot.captured.messageBody() shouldBeEqualTo "hello"
        verify(exactly = 1) { client.sendMessage(any<SendMessageRequest>()) }
    }
}
