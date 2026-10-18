package io.bluetape4k.aws.spring.ses

import io.bluetape4k.concurrent.FutureUtils
import io.bluetape4k.logging.KLogging
import io.bluetape4k.support.requireNotNull
import jakarta.mail.Message.RecipientType
import jakarta.mail.MessagingException
import jakarta.mail.Session
import jakarta.mail.internet.MimeMessage
import org.springframework.mail.MailAuthenticationException
import org.springframework.mail.MailParseException
import org.springframework.mail.MailPreparationException
import org.springframework.mail.MailSendException
import org.springframework.mail.SimpleMailMessage
import org.springframework.mail.javamail.JavaMailSender
import software.amazon.awssdk.core.exception.SdkClientException
import software.amazon.awssdk.services.sesv2.model.SesV2Exception
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.*
import java.util.concurrent.CompletionException

/**
 * [SesOperations]를 사용하는 Spring [JavaMailSender] 어댑터입니다.
 *
 * ## 계약
 *
 * 코루틴 디스패처를 블로킹하거나 `runBlocking`을 사용하지 않고 [SimpleMailMessage]는 SES 단순
 * 메시지로, [MimeMessage]는 SES 원본 메시지로 전송합니다.
 */
class SesJavaMailSender(
    private val sesOperations: SesOperations,
): JavaMailSender {

    companion object: KLogging() {
        private val mailSession: Session by lazy { Session.getInstance(Properties()) }
    }

    override fun createMimeMessage(): MimeMessage =
        MimeMessage(mailSession)

    override fun createMimeMessage(contentStream: InputStream): MimeMessage =
        try {
            MimeMessage(mailSession, contentStream)
        } catch (e: MessagingException) {
            throw MailParseException(e)
        }

    override fun send(vararg mimeMessages: MimeMessage) {
        val failedMessages = linkedMapOf<Any, Exception>()

        val responses = mimeMessages.map { message ->
            sesOperations.sendRawEmailAsync(message.toRawEmailRequest())
                .whenComplete { response, throwable ->
                    if (throwable != null) {
                        failedMessages[message] = throwable.toMailException()
                    }
                }
        }
        FutureUtils.allAsList(responses).join()

        if (failedMessages.isNotEmpty()) {
            throw MailSendException(failedMessages)
        }
    }

    override fun send(vararg simpleMessages: SimpleMailMessage) {
        val failedMessages = linkedMapOf<Any, Exception>()

        val repsonses = simpleMessages.map { message ->
            sesOperations.sendEmailAsync(message.toEmailRequest())
                .whenComplete { response, throwable ->
                    if (throwable != null) {
                        failedMessages[message] = throwable.toMailException()
                    }
                }
        }
        FutureUtils.allAsList(repsonses).join()

        if (failedMessages.isNotEmpty()) {
            throw MailSendException(failedMessages)
        }
    }

    private fun SimpleMailMessage.toEmailRequest(): SesEmailRequest =
        SesEmailRequest(
            from = from,
            destination = SesEmailAddressSet(
                to = to?.toList().orEmpty(),
                cc = cc?.toList().orEmpty(),
                bcc = bcc?.toList().orEmpty(),
            ),
            subject = subject?.takeIf { it.isNotBlank() }
                ?: throw MailPreparationException("SimpleMailMessage subject must not be blank."),
            body = SesEmailBody(text = text),
            replyTo = replyTo?.let(::listOf).orEmpty(),
        )

    private fun MimeMessage.toRawEmailRequest(): SesRawEmailRequest =
        SesRawEmailRequest(
            rawContent = toByteArray(),
            from = from?.firstOrNull()?.toString(),
            destination = toEmailAddressSetOrNull(),
        )

    private fun MimeMessage.toEmailAddressSetOrNull(): SesEmailAddressSet? {
        val to = getRecipients(RecipientType.TO)?.map { it.toString() }.orEmpty()
        val cc = getRecipients(RecipientType.CC)?.map { it.toString() }.orEmpty()
        val bcc = getRecipients(RecipientType.BCC)?.map { it.toString() }.orEmpty()
        return if (to.isEmpty() && cc.isEmpty() && bcc.isEmpty()) {
            null
        } else {
            SesEmailAddressSet(to = to, cc = cc, bcc = bcc)
        }
    }

    private fun MimeMessage.toByteArray(): ByteArray =
        try {
            ByteArrayOutputStream().use { output ->
                writeTo(output)
                output.toByteArray()
            }
        } catch (e: MessagingException) {
            throw MailParseException(e)
        } catch (e: IOException) {
            throw MailParseException(e)
        }

    private fun Throwable.toMailException(): Exception {
        val cause = unwrapCompletion()
        if (cause !is Exception) {
            throw cause
        }
        return when (cause) {
            is MailParseException,
            is MailAuthenticationException,
            is MailPreparationException,
            is MailSendException,
                -> cause

            is IllegalArgumentException -> MailParseException(cause)
            is MessagingException -> MailParseException(cause)
            is SesV2Exception ->
                if (cause.statusCode() == 401 || cause.statusCode() == 403) {
                    MailAuthenticationException(cause)
                } else {
                    MailSendException("Failed to send SES email.", cause)
                }

            is SdkClientException -> MailSendException("Failed to send SES email.", cause)
            else -> MailSendException("Failed to send SES email.", cause)
        }
    }

    private fun Throwable.unwrapCompletion(): Throwable {
        var current: Throwable = this
        while (current is CompletionException && current.cause != null) {
            current = current.cause.requireNotNull("cause")
        }
        return current
    }
}
