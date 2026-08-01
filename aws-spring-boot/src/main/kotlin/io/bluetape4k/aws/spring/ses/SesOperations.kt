package io.bluetape4k.aws.spring.ses

import software.amazon.awssdk.services.sesv2.model.SendEmailRequest
import software.amazon.awssdk.services.sesv2.model.SendEmailResponse
import java.util.concurrent.CompletableFuture

/**
 * 코루틴 및 Future 친화적인 SES 메일 작업입니다.
 *
 * ## 계약
 *
 * 편의 메서드는 bluetape4k 요청 값 객체를 AWS SES v2 `SendEmail` 요청으로 매핑하고
 * 구성된 기본값을 적용합니다. 원본 AWS SDK 요청 메서드는 요청을 그대로 전송합니다.
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
