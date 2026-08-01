package io.bluetape4k.aws.ktor.ses

import software.amazon.awssdk.services.sesv2.model.SendEmailRequest
import software.amazon.awssdk.services.sesv2.model.SendEmailResponse
import java.util.concurrent.CompletableFuture

/**
 * Ktor 애플리케이션을 위한 코루틴 및 Future 친화적인 SES v2 작업입니다.
 *
 * ## 계약
 *
 * 편의 메서드는 bluetape4k 요청 값 객체를 AWS SES v2 `SendEmail` 요청으로 매핑하고
 * 구성된 기본값을 적용합니다. 원본 AWS SDK 요청 메서드는 요청을 그대로 전송합니다.
 */
interface SesKtorOperations {

    /** 단순 이메일 메시지를 전송합니다. */
    suspend fun sendEmail(request: SesEmailRequest): SendEmailResponse

    /** 템플릿 이메일 메시지를 전송합니다. */
    suspend fun sendTemplateEmail(request: SesTemplateEmailRequest): SendEmailResponse

    /** 원본 MIME 이메일 메시지를 전송합니다. */
    suspend fun sendRawEmail(request: SesRawEmailRequest): SendEmailResponse

    /** 원본 AWS SDK SES v2 요청을 전송합니다. */
    suspend fun send(request: SendEmailRequest): SendEmailResponse

    /** 결과를 기다리지 않고 단순 이메일 메시지 전송을 시작합니다. */
    fun sendEmailAsync(request: SesEmailRequest): CompletableFuture<SendEmailResponse>

    /** 결과를 기다리지 않고 템플릿 이메일 메시지 전송을 시작합니다. */
    fun sendTemplateEmailAsync(request: SesTemplateEmailRequest): CompletableFuture<SendEmailResponse>

    /** 결과를 기다리지 않고 원본 MIME 이메일 메시지 전송을 시작합니다. */
    fun sendRawEmailAsync(request: SesRawEmailRequest): CompletableFuture<SendEmailResponse>

    /** 결과를 기다리지 않고 원본 AWS SDK SES v2 요청 전송을 시작합니다. */
    fun sendAsync(request: SendEmailRequest): CompletableFuture<SendEmailResponse>
}
