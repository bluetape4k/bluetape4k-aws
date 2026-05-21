package io.bluetape4k.aws.spring.ses

import software.amazon.awssdk.services.sesv2.model.SendEmailRequest
import software.amazon.awssdk.services.sesv2.model.SendEmailResponse
import java.util.concurrent.CompletableFuture

object NoopSesOperations: SesOperations {

    override suspend fun sendEmail(request: SesEmailRequest): SendEmailResponse =
        response()

    override suspend fun sendTemplateEmail(request: SesTemplateEmailRequest): SendEmailResponse =
        response()

    override suspend fun sendRawEmail(request: SesRawEmailRequest): SendEmailResponse =
        response()

    override suspend fun send(request: SendEmailRequest): SendEmailResponse =
        response()

    override fun sendEmailAsync(request: SesEmailRequest): CompletableFuture<SendEmailResponse> =
        CompletableFuture.completedFuture(response())

    override fun sendTemplateEmailAsync(request: SesTemplateEmailRequest): CompletableFuture<SendEmailResponse> =
        CompletableFuture.completedFuture(response())

    override fun sendRawEmailAsync(request: SesRawEmailRequest): CompletableFuture<SendEmailResponse> =
        CompletableFuture.completedFuture(response())

    override fun sendAsync(request: SendEmailRequest): CompletableFuture<SendEmailResponse> =
        CompletableFuture.completedFuture(response())

    private fun response(): SendEmailResponse =
        SendEmailResponse.builder()
            .messageId("noop")
            .build()
}
