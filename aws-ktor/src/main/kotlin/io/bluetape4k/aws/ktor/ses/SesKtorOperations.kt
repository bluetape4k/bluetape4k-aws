package io.bluetape4k.aws.ktor.ses

import software.amazon.awssdk.services.sesv2.model.SendEmailRequest
import software.amazon.awssdk.services.sesv2.model.SendEmailResponse
import java.util.concurrent.CompletableFuture

/**
 * Coroutine and future-friendly SES v2 operations for Ktor applications.
 *
 * ## Contract
 *
 * Convenience methods map bluetape4k request value objects to AWS SES v2
 * `SendEmail` requests and apply configured defaults. Raw AWS SDK request
 * methods send the request as-is.
 */
interface SesKtorOperations {

    /** Sends a simple email message. */
    suspend fun sendEmail(request: SesEmailRequest): SendEmailResponse

    /** Sends a templated email message. */
    suspend fun sendTemplateEmail(request: SesTemplateEmailRequest): SendEmailResponse

    /** Sends a raw MIME email message. */
    suspend fun sendRawEmail(request: SesRawEmailRequest): SendEmailResponse

    /** Sends a raw AWS SDK SES v2 request. */
    suspend fun send(request: SendEmailRequest): SendEmailResponse

    /** Starts sending a simple email message without awaiting the result. */
    fun sendEmailAsync(request: SesEmailRequest): CompletableFuture<SendEmailResponse>

    /** Starts sending a templated email message without awaiting the result. */
    fun sendTemplateEmailAsync(request: SesTemplateEmailRequest): CompletableFuture<SendEmailResponse>

    /** Starts sending a raw MIME email message without awaiting the result. */
    fun sendRawEmailAsync(request: SesRawEmailRequest): CompletableFuture<SendEmailResponse>

    /** Starts sending a raw AWS SDK SES v2 request without awaiting the result. */
    fun sendAsync(request: SendEmailRequest): CompletableFuture<SendEmailResponse>
}
