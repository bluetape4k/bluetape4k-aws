package io.bluetape4k.aws.ses

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.aws.ses.model.sendEmailRequest
import io.bluetape4k.concurrent.completableFutureOf
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.debug
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import software.amazon.awssdk.services.ses.SesAsyncClient
import software.amazon.awssdk.services.ses.model.SendEmailResponse

class SesAsyncClientCoroutinesExtensionsTest {

    companion object: KLoggingChannel()

    private val client = mockk<SesAsyncClient>()

    @BeforeEach
    fun beforeEach() {
        clearAllMocks()
    }

    @Test
    fun `sendSuspend는 sendEmail 결과를 반환한다`() = runTest {
        val request = sendEmailRequest { }
        val response = SendEmailResponse.builder().messageId("mid-1").build()

        every { client.sendEmail(request) } returns completableFutureOf(response)

        val result = client.send(request)

        log.debug { "result=$result" }
        result.messageId() shouldBeEqualTo "mid-1"
        verify(exactly = 1) { client.sendEmail(request) }
    }
}
