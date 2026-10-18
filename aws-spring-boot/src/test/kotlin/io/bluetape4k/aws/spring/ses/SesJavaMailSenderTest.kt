package io.bluetape4k.aws.spring.ses

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldNotBeEmpty
import io.bluetape4k.concurrent.completableFutureOf
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import jakarta.mail.Message.RecipientType
import jakarta.mail.Session
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeMessage
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.mail.SimpleMailMessage
import software.amazon.awssdk.services.sesv2.model.SendEmailResponse
import java.util.*
import java.util.concurrent.CompletableFuture

class SesJavaMailSenderTest {

    companion object: KLogging()

    private val operations = mockk<SesOperations>()

    @BeforeEach
    fun beforeEach() {
        clearMocks(operations)
    }

    @Test
    fun `send SimpleMailMessage delegates to SES simple email`() {
        val operations = mockk<SesOperations>()
        val request = slot<SesEmailRequest>()
        every { operations.sendEmailAsync(capture(request)) } returns completedResponse()

        val message = SimpleMailMessage().apply {
            from = "sender@example.com"
            setTo("to@example.com")
            setCc("cc@example.com")
            replyTo = "reply@example.com"
            subject = "hello"
            text = "text body"
        }

        SesJavaMailSender(operations).send(message)

        log.debug { "request=$request" }
        request.captured.from shouldBeEqualTo "sender@example.com"
        request.captured.destination.to shouldBeEqualTo listOf("to@example.com")
        request.captured.destination.cc shouldBeEqualTo listOf("cc@example.com")
        request.captured.replyTo shouldBeEqualTo listOf("reply@example.com")
        request.captured.subject shouldBeEqualTo "hello"
        request.captured.body.text shouldBeEqualTo "text body"

        verify(exactly = 1) { operations.sendEmailAsync(any()) }
    }

    @Test
    fun `send MimeMessage delegates to SES raw email`() {
        val request = slot<SesRawEmailRequest>()
        every { operations.sendRawEmailAsync(capture(request)) } returns completedResponse()

        val mimeMessage = MimeMessage(Session.getInstance(Properties())).apply {
            setFrom(InternetAddress("sender@example.com"))
            setRecipients(RecipientType.TO, "to@example.com")
            subject = "hello"
            setText("raw body")
            saveChanges()
        }

        SesJavaMailSender(operations).send(mimeMessage)

        log.debug { "request=$request" }
        request.captured.from shouldBeEqualTo "sender@example.com"
        request.captured.destination?.to shouldBeEqualTo listOf("to@example.com")
        request.captured.rawContent.shouldNotBeEmpty()

        verify(exactly = 1) { operations.sendRawEmailAsync(any()) }
    }

    @Test
    fun `send MimeMessage omits SES destination when recipients are absent`() {
        val request = slot<SesRawEmailRequest>()
        every { operations.sendRawEmailAsync(capture(request)) } returns completedResponse()

        val mimeMessage = MimeMessage(Session.getInstance(Properties())).apply {
            setFrom(InternetAddress("sender@example.com"))
            subject = "hello"
            setText("raw body")
            saveChanges()
        }

        SesJavaMailSender(operations).send(mimeMessage)

        log.debug { "request=$request" }
        request.captured.destination.shouldBeNull()
        request.captured.rawContent.shouldNotBeEmpty()
        verify(exactly = 1) { operations.sendRawEmailAsync(any()) }
    }

    private fun completedResponse(): CompletableFuture<SendEmailResponse> =
        completableFutureOf(
            SendEmailResponse.builder()
                .messageId("message-1")
                .build()
        )
}
