package io.bluetape4k.aws.kotlin.sesv2

import aws.sdk.kotlin.services.sesv2.SesV2Client
import aws.sdk.kotlin.services.sesv2.model.EmailTemplateContent
import aws.sdk.kotlin.services.sesv2.model.GetEmailTemplateResponse
import aws.sdk.kotlin.services.sesv2.model.SendBulkEmailRequest
import aws.sdk.kotlin.services.sesv2.model.SendBulkEmailResponse
import aws.sdk.kotlin.services.sesv2.model.SendEmailRequest
import aws.sdk.kotlin.services.sesv2.model.SendEmailResponse
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldNotBeNull
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * MockK-based contract tests for [SesV2Client] extension functions.
 *
 * LocalStack does not support SES V2 (issue #99). These tests use MockK to verify
 * the bluetape4k coroutine wrappers delegate correctly without a live emulator.
 */
class SesV2ClientExtensionsMockTest {

    companion object: KLoggingChannel()

    private val client = mockk<SesV2Client>()

    @BeforeEach
    fun setup() {
        clearMocks(client)
    }

    @Test
    fun `send delegates to sendEmail`() = runSuspendIO {
        val request = mockk<SendEmailRequest>()
        val expected = SendEmailResponse { messageId = "msg-001" }
        coEvery { client.sendEmail(any()) } returns expected

        val result = client.send(request)

        result.messageId shouldBeEqualTo "msg-001"
        coVerify(exactly = 1) { client.sendEmail(any()) }
    }

    @Test
    fun `sendBulk delegates to sendBulkEmail`() = runSuspendIO {
        val request = mockk<SendBulkEmailRequest>()
        val expected = mockk<SendBulkEmailResponse>(relaxed = true)
        coEvery { client.sendBulkEmail(any()) } returns expected

        val result = client.sendBulk(request)

        result shouldBeEqualTo expected
        coVerify(exactly = 1) { client.sendBulkEmail(any()) }
    }

    @Test
    fun `getTemplateOrNull returns template when templateContent is non-null`() = runSuspendIO {
        val templateName = "welcome-template"
        val content = EmailTemplateContent {
            subject = "Hello, {{name}}"
            html = "<h1>Hello, {{name}}</h1>"
            text = "Hello, {{name}}"
        }
        coEvery { client.getEmailTemplate(any()) } returns GetEmailTemplateResponse {
            this.templateName = templateName
            this.templateContent = content
        }

        val result = client.getTemplateOrNull(templateName)

        result.shouldNotBeNull()
        result.templateName shouldBeEqualTo templateName
    }

    @Test
    fun `getTemplateOrNull returns null when templateContent is null`() = runSuspendIO {
        coEvery { client.getEmailTemplate(any()) } returns GetEmailTemplateResponse {
            this.templateName = "empty-template"
            this.templateContent = null
        }

        val result = client.getTemplateOrNull("empty-template")

        result.shouldBeNull()
    }

    @Test
    fun `getTemplateOrNull returns null on non-cancellation exception`() = runSuspendIO {
        coEvery { client.getEmailTemplate(any()) } throws RuntimeException("TemplateDoesNotExist")

        val result = client.getTemplateOrNull("nonexistent")

        result.shouldBeNull()
    }

    @Test
    fun `getTemplateOrNull rethrows CancellationException`() = runTest {
        coEvery { client.getEmailTemplate(any()) } throws CancellationException("test-cancelled")

        assertFailsWith<CancellationException> {
            client.getTemplateOrNull("any")
        }
    }
}
