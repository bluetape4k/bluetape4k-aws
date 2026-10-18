package io.bluetape4k.aws.spring.ses

import io.bluetape4k.concurrent.completableFutureOf
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import software.amazon.awssdk.services.sesv2.model.SendEmailRequest
import software.amazon.awssdk.services.sesv2.model.SendEmailResponse
import java.util.concurrent.CompletableFuture

object NoopSesOperations: SesOperations, KLogging() {

    override suspend fun sendEmail(request: SesEmailRequest): SendEmailResponse =
        response()

    override suspend fun sendTemplateEmail(request: SesTemplateEmailRequest): SendEmailResponse =
        response()

    override suspend fun sendRawEmail(request: SesRawEmailRequest): SendEmailResponse =
        response()

    override suspend fun send(request: SendEmailRequest): SendEmailResponse =
        response()

    override fun sendEmailAsync(request: SesEmailRequest): CompletableFuture<SendEmailResponse> =
        completableFutureOf(response())

    override fun sendTemplateEmailAsync(request: SesTemplateEmailRequest): CompletableFuture<SendEmailResponse> =
        completableFutureOf(response())

    override fun sendRawEmailAsync(request: SesRawEmailRequest): CompletableFuture<SendEmailResponse> =
        completableFutureOf(response())

    override fun sendAsync(request: SendEmailRequest): CompletableFuture<SendEmailResponse> =
        completableFutureOf(response())

    private fun response(): SendEmailResponse =
        SendEmailResponse.builder()
            .messageId("noop")
            .build()
            .also {
                log.debug { "Noop SendMailResponse" }
            }
}
