package io.bluetape4k.aws.ktor.ses

import aws.sdk.kotlin.services.sesv2.SesV2Client
import aws.sdk.kotlin.services.sesv2.model.AttachmentContentDisposition
import aws.sdk.kotlin.services.sesv2.model.AttachmentContentTransferEncoding
import aws.sdk.kotlin.services.sesv2.model.SendEmailRequest
import aws.sdk.kotlin.services.sesv2.model.SendEmailResponse
import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeSameInstanceAs
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldNotContain
import io.bluetape4k.support.toUtf8Bytes
import io.bluetape4k.support.toUtf8String
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@Suppress("UnusedDataClassCopyResult")
class SesKtorTemplateTest {

    private val client = mockk<SesV2Client>()

    @BeforeEach
    fun beforeEach() {
        clearMocks(client)
    }

    @Test
    fun `sendEmail maps message headers attachments and defaults`() = runTest {
        val request = slot<SendEmailRequest>()
        val response = SendEmailResponse { messageId = "message-1" }
        val attachmentBytes = "attachment".toUtf8Bytes()
        coEvery { client.sendEmail(capture(request)) } returns response

        val result = template(client).sendEmail(
            SesEmailRequest(
                destination = SesEmailAddressSet(to = listOf("to@example.com")),
                subject = "hello",
                body = SesEmailBody(text = "text", html = "<b>html</b>"),
                replyTo = listOf("reply@example.com"),
                headers = mapOf("X-Trace-Id" to "trace-1"),
                attachments = listOf(
                    SesEmailAttachment(
                        fileName = "hello.txt",
                        content = attachmentBytes,
                        contentType = "text/plain",
                        contentDisposition = AttachmentContentDisposition.Inline,
                        contentTransferEncoding = AttachmentContentTransferEncoding.QuotedPrintable,
                        contentDescription = "report",
                        contentId = "report-1",
                    )
                ),
            )
        )

        result shouldBeSameInstanceAs response
        result.messageId shouldBeEqualTo "message-1"
        request.captured.fromEmailAddress shouldBeEqualTo "sender@example.com"
        request.captured.destination?.toAddresses shouldBeEqualTo listOf("to@example.com")
        request.captured.replyToAddresses shouldBeEqualTo listOf("reply@example.com")
        request.captured.configurationSetName shouldBeEqualTo "config-1"
        request.captured.content?.simple?.subject?.data shouldBeEqualTo "hello"
        request.captured.content?.simple?.body?.text?.data shouldBeEqualTo "text"
        request.captured.content?.simple?.body?.html?.data shouldBeEqualTo "<b>html</b>"
        request.captured.content?.simple?.headers?.single()?.name shouldBeEqualTo "X-Trace-Id"
        val attachment = request.captured.content?.simple?.attachments?.single()
        attachment?.fileName shouldBeEqualTo "hello.txt"
        attachment?.rawContent?.toList() shouldBeEqualTo attachmentBytes.toList()
        attachment?.contentDisposition shouldBeEqualTo AttachmentContentDisposition.Inline
        attachment?.contentTransferEncoding shouldBeEqualTo AttachmentContentTransferEncoding.QuotedPrintable
        attachment?.contentDescription shouldBeEqualTo "report"
        attachment?.contentId shouldBeEqualTo "report-1"
        coVerify(exactly = 1) { client.sendEmail(any<SendEmailRequest>()) }
    }

    @Test
    fun `sendTemplateEmail maps template ARN and request overrides`() = runTest {
        val request = slot<SendEmailRequest>()
        coEvery { client.sendEmail(capture(request)) } returns SendEmailResponse { messageId = "template-1" }

        template(client).sendTemplateEmail(
            SesTemplateEmailRequest(
                destination = SesEmailAddressSet(to = listOf("to@example.com")),
                templateArn = "arn:aws:ses:us-east-1:123456789012:template/welcome",
                templateData = """{"name":"Bluetape"}""",
                from = "override@example.com",
                configurationSetName = "request-config",
                headers = mapOf("X-Template" to "welcome"),
            )
        )

        val sent = request.captured
        sent.fromEmailAddress shouldBeEqualTo "override@example.com"
        sent.configurationSetName shouldBeEqualTo "request-config"
        sent.content?.template?.templateArn shouldBeEqualTo "arn:aws:ses:us-east-1:123456789012:template/welcome"
        sent.content?.template?.templateData shouldBeEqualTo """{"name":"Bluetape"}"""
        sent.content?.template?.headers?.single()?.value shouldBeEqualTo "welcome"
    }

    @Test
    fun `sendRawEmail preserves bytes and optional destination`() = runTest {
        val request = slot<SendEmailRequest>()
        val rawBytes = "From: sender@example.com\r\n\r\nbody".toUtf8Bytes()
        coEvery { client.sendEmail(capture(request)) } returns SendEmailResponse { messageId = "raw-1" }

        template(client).sendRawEmail(
            SesRawEmailRequest(
                rawContent = rawBytes,
                destination = SesEmailAddressSet(to = listOf("to@example.com")),
            )
        )

        request.captured.fromEmailAddress shouldBeEqualTo "sender@example.com"
        request.captured.destination?.toAddresses shouldBeEqualTo listOf("to@example.com")
        request.captured.content?.raw?.data?.toList() shouldBeEqualTo rawBytes.toList()
    }

    @Test
    fun `attachment enum variants including unknown values survive mapping`() = runTest {
        val dispositions = AttachmentContentDisposition.values() + AttachmentContentDisposition.SdkUnknown("FUTURE")
        val encodings = AttachmentContentTransferEncoding.values() +
            AttachmentContentTransferEncoding.SdkUnknown("FUTURE")
        val request = slot<SendEmailRequest>()
        coEvery { client.sendEmail(capture(request)) } returns SendEmailResponse { messageId = "enum-1" }

        for (disposition in dispositions) {
            template(client).sendEmail(sampleEmail().copy(
                attachments = listOf(SesEmailAttachment("a.txt", byteArrayOf(1), "text/plain", disposition))
            ))
            request.captured.content?.simple?.attachments?.single()?.contentDisposition shouldBeEqualTo disposition
        }
        for (encoding in encodings) {
            template(client).sendEmail(sampleEmail().copy(
                attachments = listOf(
                    SesEmailAttachment(
                        "a.txt",
                        byteArrayOf(1),
                        "text/plain",
                        contentTransferEncoding = encoding,
                    )
                )
            ))
            request.captured.content?.simple?.attachments?.single()?.contentTransferEncoding shouldBeEqualTo encoding
        }
    }

    @Test
    fun `send preserves raw Kotlin SDK request`() = runTest {
        val sdkRequest = SendEmailRequest { fromEmailAddress = "sdk@example.com" }
        coEvery { client.sendEmail(sdkRequest) } returns SendEmailResponse { messageId = "sdk-1" }

        template(client).send(sdkRequest) shouldBeEqualTo SendEmailResponse { messageId = "sdk-1" }
        coVerify(exactly = 1) { client.sendEmail(sdkRequest) }
    }

    @Test
    fun `sendEmail requires from when no default is configured`() = runTest {
        val error = assertFailsWith<IllegalArgumentException> {
            SesKtorTemplate(mockk(relaxed = true)).sendEmail(sampleEmail())
        }

        error.message shouldBeEqualTo "from is required when defaultFrom is not configured."
    }

    @Test
    fun `headers reject injection characters`() {
        assertFailsWith<IllegalArgumentException> {
            SesEmailRequest(
                destination = SesEmailAddressSet(to = listOf("to@example.com")),
                subject = "hello",
                body = SesEmailBody(text = "text"),
                from = "sender@example.com",
                headers = mapOf("Bad:Header" to "value"),
            )
        }
    }

    @Test
    fun `raw content and attachment bytes are defensively copied and mapped exactly`() = runTest {
        val rawBytes = "raw".toUtf8Bytes()
        val raw = SesRawEmailRequest(rawBytes)
        rawBytes[0] = 'X'.code.toByte()
        raw.rawContent[0] = 'Y'.code.toByte()
        raw.rawContent.toUtf8String() shouldBeEqualTo "raw"

        val attachmentBytes = "att".toUtf8Bytes()
        val attachment = SesEmailAttachment("a.txt", attachmentBytes, "text/plain")
        attachmentBytes[0] = 'X'.code.toByte()
        attachment.content[0] = 'Y'.code.toByte()
        attachment.content.toUtf8String() shouldBeEqualTo "att"

        val sent = slot<SendEmailRequest>()
        coEvery { client.sendEmail(capture(sent)) } returns SendEmailResponse { messageId = "copy-1" }
        template(client).sendRawEmail(raw)
        sent.captured.content?.raw?.data?.toUtf8String() shouldBeEqualTo "raw"
        template(client).sendEmail(sampleEmail().copy(attachments = listOf(attachment)))
        sent.captured.content?.simple?.attachments?.single()?.rawContent?.toUtf8String() shouldBeEqualTo "att"
    }

    @Test
    fun `toString does not expose raw bytes or recipients`() {
        val raw = SesRawEmailRequest(
            "secret-body".toUtf8Bytes(),
            destination = SesEmailAddressSet(to = listOf("to@example.com"))
        )
        val attachment = SesEmailAttachment("a.txt", "secret-body".toUtf8Bytes(), "text/plain")

        raw.toString() shouldNotContain "secret-body"
        raw.toString() shouldNotContain "to@example.com"
        attachment.toString() shouldNotContain "secret-body"
    }

    @Test
    fun `attachment rejects SES 40 MB limit`() {
        assertFailsWith<IllegalArgumentException> {
            SesEmailAttachment("too-large.bin", ByteArray(MAX_SES_MESSAGE_BYTES + 1), "application/octet-stream")
        }
    }

    @Test
    fun `email requests reject total SES 40 MB limit`() {
        val largeBody = "a".repeat(MAX_SES_MESSAGE_BYTES)
        assertFailsWith<IllegalArgumentException> {
            SesEmailRequest(
                destination = SesEmailAddressSet(to = listOf("to@example.com")),
                subject = "too large",
                body = SesEmailBody(text = largeBody),
                from = "sender@example.com",
            )
        }
        assertFailsWith<IllegalArgumentException> {
            SesTemplateEmailRequest(
                destination = SesEmailAddressSet(to = listOf("to@example.com")),
                templateName = "welcome",
                templateData = largeBody,
                from = "sender@example.com",
            )
        }
    }

    @Test
    fun `copy revalidates SES request models`() {
        assertFailsWith<IllegalArgumentException> { sampleEmail().copy(subject = "bad\r\nsubject") }
        assertFailsWith<IllegalArgumentException> {
            SesTemplateEmailRequest(
                destination = SesEmailAddressSet(to = listOf("to@example.com")),
                templateName = "welcome",
            ).copy(templateName = null, templateArn = null)
        }
    }

    @Test
    fun `sendEmail cancellation cancels the suspend request`() = runTest {
        coEvery { client.sendEmail(any<SendEmailRequest>()) } coAnswers { awaitCancellation() }
        val job = launch { template(client).sendEmail(sampleEmail()) }
        runCurrent()
        job.cancel()
        job.isCancelled.shouldBeTrue()
    }

    @Test
    fun `template and raw email cancellation propagates`() = runTest {
        coEvery { client.sendEmail(any<SendEmailRequest>()) } coAnswers { awaitCancellation() }
        val templateJob = launch {
            template(client).sendTemplateEmail(
                SesTemplateEmailRequest(SesEmailAddressSet(to = listOf("to@example.com")), templateName = "welcome")
            )
        }
        runCurrent()
        templateJob.cancel()
        templateJob.isCancelled.shouldBeTrue()

        val rawJob = launch { template(client).sendRawEmail(SesRawEmailRequest("raw".toUtf8Bytes())) }
        runCurrent()
        rawJob.cancel()
        rawJob.isCancelled.shouldBeTrue()
    }

    @Test
    fun `failed SES request preserves original exception`() = runTest {
        val failure = IllegalStateException("boom")
        coEvery { client.sendEmail(any<SendEmailRequest>()) } throws failure

        val error = assertFailsWith<IllegalStateException> { template(client).sendEmail(sampleEmail()) }
        error shouldBeEqualTo failure
    }

    private fun template(client: SesV2Client): SesKtorTemplate =
        SesKtorTemplate(client, defaultFrom = "sender@example.com", configurationSetName = "config-1")

    private fun sampleEmail(): SesEmailRequest = SesEmailRequest(
        destination = SesEmailAddressSet(to = listOf("to@example.com")),
        subject = "hello",
        body = SesEmailBody(text = "text"),
    )
}
