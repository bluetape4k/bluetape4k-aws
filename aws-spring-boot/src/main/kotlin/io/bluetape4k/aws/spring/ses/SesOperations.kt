package io.bluetape4k.aws.spring.ses

import software.amazon.awssdk.services.sesv2.model.SendEmailRequest
import software.amazon.awssdk.services.sesv2.model.SendEmailResponse
import java.util.concurrent.CompletableFuture

/**
 * Coroutine and future-friendly SES mail operations.
 *
 * ## Contract
 *
 * Convenience methods map bluetape4k request value objects to AWS SES v2
 * `SendEmail` requests and apply configured defaults. Raw AWS SDK request
 * methods send the request as-is.
 */
interface SesOperations {

    suspend fun sendEmail(request: SesEmailRequest): SendEmailResponse

    suspend fun sendTemplateEmail(request: SesTemplateEmailRequest): SendEmailResponse

    suspend fun sendRawEmail(request: SesRawEmailRequest): SendEmailResponse

    suspend fun send(request: SendEmailRequest): SendEmailResponse

    fun sendEmailAsync(request: SesEmailRequest): CompletableFuture<SendEmailResponse>

    fun sendTemplateEmailAsync(request: SesTemplateEmailRequest): CompletableFuture<SendEmailResponse>

    fun sendRawEmailAsync(request: SesRawEmailRequest): CompletableFuture<SendEmailResponse>

    fun sendAsync(request: SendEmailRequest): CompletableFuture<SendEmailResponse>
}
