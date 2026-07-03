package io.bluetape4k.aws.ktor.ses

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldNotContain
import io.bluetape4k.assertions.shouldNotBeEmpty
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import software.amazon.awssdk.core.exception.SdkClientException
import software.amazon.awssdk.services.sesv2.SesV2AsyncClient
import software.amazon.awssdk.services.sesv2.model.AttachmentContentDisposition
import software.amazon.awssdk.services.sesv2.model.SendEmailRequest
import software.amazon.awssdk.services.sesv2.model.SendEmailResponse
import java.util.concurrent.CompletableFuture

class SesKtorTemplateTest {

    @Test
    fun `sendEmail maps simple message headers and attachments`() = runTest {
        val fixture = mockClient()
        val result = template(fixture.client).sendEmail(
            SesEmailRequest(
                destination = SesEmailAddressSet(to = listOf("to@example.com")),
                subject = "hello",
                body = SesEmailBody(text = "text", html = "<b>html</b>"),
                replyTo = listOf("reply@example.com"),
                headers = mapOf("X-Trace-Id" to "trace-1"),
                attachments = listOf(
                    SesEmailAttachment(
                        fileName = "hello.txt",
                        content = "attachment".toByteArray(),
                        contentType = "text/plain",
                        contentDisposition = AttachmentContentDisposition.ATTACHMENT,
                    )
                ),
            )
        )

        val request = fixture.request.captured
        result.messageId() shouldBeEqualTo "message-1"
        request.fromEmailAddress() shouldBeEqualTo "sender@example.com"
        request.destination().toAddresses() shouldBeEqualTo listOf("to@example.com")
        request.replyToAddresses() shouldBeEqualTo listOf("reply@example.com")
        request.configurationSetName() shouldBeEqualTo "config-1"
        request.content().simple().subject().data() shouldBeEqualTo "hello"
        request.content().simple().body().text().data() shouldBeEqualTo "text"
        request.content().simple().body().html().data() shouldBeEqualTo "<b>html</b>"
        request.content().simple().headers().first().name() shouldBeEqualTo "X-Trace-Id"
        request.content().simple().attachments().first().fileName() shouldBeEqualTo "hello.txt"
        request.content().simple().attachments().first().rawContent().asByteArray().shouldNotBeEmpty()
        verify(exactly = 1) { fixture.client.sendEmail(any<SendEmailRequest>()) }
    }

    @Test
    fun `sendTemplateEmail maps template name`() = runTest {
        val fixture = mockClient()

        template(fixture.client).sendTemplateEmail(
            SesTemplateEmailRequest(
                destination = SesEmailAddressSet(to = listOf("to@example.com")),
                templateName = "welcome",
                templateData = """{"name":"Bluetape"}""",
                headers = mapOf("X-Template" to "welcome"),
            )
        )

        val request = fixture.request.captured
        request.fromEmailAddress() shouldBeEqualTo "sender@example.com"
        request.content().template().templateName() shouldBeEqualTo "welcome"
        request.content().template().templateData() shouldBeEqualTo """{"name":"Bluetape"}"""
        request.content().template().headers().first().value() shouldBeEqualTo "welcome"
    }

    @Test
    fun `sendRawEmail maps raw bytes and optional destination`() = runTest {
        val fixture = mockClient()

        template(fixture.client).sendRawEmail(
            SesRawEmailRequest(
                rawContent = "From: sender@example.com\r\n\r\nbody".toByteArray(),
                destination = SesEmailAddressSet(to = listOf("to@example.com")),
            )
        )

        val request = fixture.request.captured
        request.fromEmailAddress() shouldBeEqualTo "sender@example.com"
        request.destination().toAddresses() shouldBeEqualTo listOf("to@example.com")
        request.content().raw().data().asByteArray().shouldNotBeEmpty()
    }

    @Test
    fun `sendEmail requires from when no default is configured`() = runTest {
        val error = assertFailsWith<IllegalArgumentException> {
            SesKtorTemplate(mockk(relaxed = true))
                .sendEmail(
                    SesEmailRequest(
                        destination = SesEmailAddressSet(to = listOf("to@example.com")),
                        subject = "hello",
                        body = SesEmailBody(text = "text"),
                    )
                )
        }

        error.message shouldBeEqualTo "from is required when defaultFrom is not configured."
    }

    @Test
    fun `headers reject injection characters`() {
        val request = {
            SesEmailRequest(
                destination = SesEmailAddressSet(to = listOf("to@example.com")),
                subject = "hello",
                body = SesEmailBody(text = "text"),
                from = "sender@example.com",
                headers = mapOf("Bad:Header" to "value"),
            )
        }

        assertFailsWith<IllegalArgumentException> { request() }
    }

    @Test
    fun `raw content and attachment bytes are defensively copied`() {
        val rawBytes = "raw".toByteArray()
        val raw = SesRawEmailRequest(rawBytes)
        rawBytes[0] = 'X'.code.toByte()
        raw.rawContent[0] = 'Y'.code.toByte()

        val attachmentBytes = "att".toByteArray()
        val attachment = SesEmailAttachment("a.txt", attachmentBytes, "text/plain")
        attachmentBytes[0] = 'X'.code.toByte()
        attachment.content[0] = 'Y'.code.toByte()

        raw.rawContent.decodeToString() shouldBeEqualTo "raw"
        attachment.content.decodeToString() shouldBeEqualTo "att"
    }

    @Test
    fun `toString does not expose raw bytes or recipients`() {
        val raw = SesRawEmailRequest("secret-body".toByteArray(), destination = SesEmailAddressSet(to = listOf("to@example.com")))
        val attachment = SesEmailAttachment("a.txt", "secret-body".toByteArray(), "text/plain")

        raw.toString() shouldNotContain "secret-body"
        attachment.toString() shouldNotContain "secret-body"
        raw.toString() shouldNotContain "to@example.com"
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
        val email = sampleEmail()
        val template = SesTemplateEmailRequest(
            destination = SesEmailAddressSet(to = listOf("to@example.com")),
            templateName = "welcome",
        )

        assertFailsWith<IllegalArgumentException> {
            email.copy(subject = "bad\r\nsubject")
        }
        assertFailsWith<IllegalArgumentException> {
            template.copy(templateName = null, templateArn = null)
        }
    }

    @Test
    fun `sendEmail cancels the backing future when coroutine is cancelled`() = runTest {
        val client = mockk<SesV2AsyncClient>()
        val future = CompletableFuture<SendEmailResponse>()
        every { client.sendEmail(any<SendEmailRequest>()) } returns future
        val job = launch {
            template(client).sendEmail(sampleEmail())
        }
        runCurrent()

        job.cancel()

        future.isCancelled.shouldBeTrue()
    }

    @Test
    fun `sendTemplateEmail cancels the backing future when coroutine is cancelled`() = runTest {
        val client = mockk<SesV2AsyncClient>()
        val future = CompletableFuture<SendEmailResponse>()
        every { client.sendEmail(any<SendEmailRequest>()) } returns future
        val job = launch {
            template(client).sendTemplateEmail(
                SesTemplateEmailRequest(
                    destination = SesEmailAddressSet(to = listOf("to@example.com")),
                    templateName = "welcome",
                )
            )
        }
        runCurrent()

        job.cancel()

        future.isCancelled.shouldBeTrue()
    }

    @Test
    fun `sendRawEmail cancels the backing future when coroutine is cancelled`() = runTest {
        val client = mockk<SesV2AsyncClient>()
        val future = CompletableFuture<SendEmailResponse>()
        every { client.sendEmail(any<SendEmailRequest>()) } returns future
        val job = launch {
            template(client).sendRawEmail(SesRawEmailRequest("raw".toByteArray()))
        }
        runCurrent()

        job.cancel()

        future.isCancelled.shouldBeTrue()
    }

    @Test
    fun `send raw SDK request cancels the backing future when coroutine is cancelled`() = runTest {
        val client = mockk<SesV2AsyncClient>()
        val future = CompletableFuture<SendEmailResponse>()
        every { client.sendEmail(any<SendEmailRequest>()) } returns future
        val job = launch {
            template(client).send(SendEmailRequest.builder().build())
        }
        runCurrent()

        job.cancel()

        future.isCancelled.shouldBeTrue()
    }

    @Test
    fun `failed SES future preserves original exception`() = runTest {
        val client = mockk<SesV2AsyncClient>()
        val failure = SdkClientException.create("boom")
        every { client.sendEmail(any<SendEmailRequest>()) } returns CompletableFuture.failedFuture(failure)

        val error = assertFailsWith<SdkClientException> {
            template(client).sendEmail(sampleEmail())
        }

        error shouldBeEqualTo failure
    }

    private fun template(client: SesV2AsyncClient): SesKtorTemplate =
        SesKtorTemplate(
            sesAsyncClient = client,
            defaultFrom = "sender@example.com",
            configurationSetName = "config-1",
        )

    private fun mockClient(): SesClientFixture {
        val client = mockk<SesV2AsyncClient>()
        val request = slot<SendEmailRequest>()
        every { client.sendEmail(capture(request)) } returns
            CompletableFuture.completedFuture(SendEmailResponse.builder().messageId("message-1").build())
        return SesClientFixture(client, request)
    }

    private fun sampleEmail(): SesEmailRequest =
        SesEmailRequest(
            destination = SesEmailAddressSet(to = listOf("to@example.com")),
            subject = "hello",
            body = SesEmailBody(text = "text"),
        )

    private class SesClientFixture(
        val client: SesV2AsyncClient,
        val request: io.mockk.CapturingSlot<SendEmailRequest>,
    )
}
