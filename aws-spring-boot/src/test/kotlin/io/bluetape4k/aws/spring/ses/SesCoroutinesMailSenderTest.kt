package io.bluetape4k.aws.spring.ses

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldNotBeEmpty
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.io.Serializable
import software.amazon.awssdk.services.sesv2.SesV2AsyncClient
import software.amazon.awssdk.services.sesv2.model.AttachmentContentDisposition
import software.amazon.awssdk.services.sesv2.model.SendEmailRequest
import software.amazon.awssdk.services.sesv2.model.SendEmailResponse
import java.util.concurrent.CompletableFuture

class SesCoroutinesMailSenderTest {

    @Test
    fun `sendEmail maps simple message headers and attachments`() = runTest {
        val fixture = mockClient()
        val result = sender(fixture.client).sendEmail(
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
    fun `sendTemplateEmail maps template headers and attachments`() = runTest {
        val fixture = mockClient()

        sender(fixture.client).sendTemplateEmail(
            SesTemplateEmailRequest(
                destination = SesEmailAddressSet(to = listOf("to@example.com")),
                templateName = "welcome",
                templateData = """{"name":"Bluetape"}""",
                headers = mapOf("X-Template" to "welcome"),
                attachments = listOf(
                    SesEmailAttachment(
                        fileName = "terms.txt",
                        content = "terms".toByteArray(),
                        contentType = "text/plain",
                    )
                ),
            )
        )

        val request = fixture.request.captured
        request.fromEmailAddress() shouldBeEqualTo "sender@example.com"
        request.content().template().templateName() shouldBeEqualTo "welcome"
        request.content().template().templateData() shouldBeEqualTo """{"name":"Bluetape"}"""
        request.content().template().headers().first().value() shouldBeEqualTo "welcome"
        request.content().template().attachments().first().contentType() shouldBeEqualTo "text/plain"
    }

    @Test
    fun `sendRawEmail maps raw bytes and optional destination`() = runTest {
        val fixture = mockClient()

        sender(fixture.client).sendRawEmail(
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
            SesCoroutinesMailSender(mockk(relaxed = true), SesProperties())
                .sendEmail(
                    SesEmailRequest(
                        destination = SesEmailAddressSet(to = listOf("to@example.com")),
                        subject = "hello",
                        body = SesEmailBody(text = "text"),
                    )
                )
        }

        error.message.orEmpty() shouldBeEqualTo
            "from is required when bluetape4k.aws.ses.default-from is not configured."
    }

    private fun sender(client: SesV2AsyncClient): SesCoroutinesMailSender =
        SesCoroutinesMailSender(
            sesAsyncClient = client,
            properties = SesProperties(
                region = "us-east-1",
                defaultFrom = "sender@example.com",
                configurationSetName = "config-1",
            ),
        )

    private fun mockClient(): SesClientFixture {
        val client = mockk<SesV2AsyncClient>()
        val request = slot<SendEmailRequest>()
        every { client.sendEmail(capture(request)) } returns
            CompletableFuture.completedFuture(SendEmailResponse.builder().messageId("message-1").build())
        return SesClientFixture(client, request)
    }

    private data class SesClientFixture(
        val client: SesV2AsyncClient,
        val request: io.mockk.CapturingSlot<SendEmailRequest>,
    ): Serializable {
        companion object {
            private const val serialVersionUID: Long = 1L
        }
    }
}
