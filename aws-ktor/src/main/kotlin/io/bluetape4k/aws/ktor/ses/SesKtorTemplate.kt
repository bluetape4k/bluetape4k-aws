package io.bluetape4k.aws.ktor.ses

import kotlinx.coroutines.future.await
import software.amazon.awssdk.core.SdkBytes
import software.amazon.awssdk.services.sesv2.SesV2AsyncClient
import software.amazon.awssdk.services.sesv2.model.Attachment
import software.amazon.awssdk.services.sesv2.model.Body
import software.amazon.awssdk.services.sesv2.model.Content
import software.amazon.awssdk.services.sesv2.model.Destination
import software.amazon.awssdk.services.sesv2.model.EmailContent
import software.amazon.awssdk.services.sesv2.model.Message
import software.amazon.awssdk.services.sesv2.model.MessageHeader
import software.amazon.awssdk.services.sesv2.model.RawMessage
import software.amazon.awssdk.services.sesv2.model.SendEmailRequest
import software.amazon.awssdk.services.sesv2.model.SendEmailResponse
import software.amazon.awssdk.services.sesv2.model.Template
import java.util.concurrent.CompletableFuture

/**
 * [SesV2AsyncClient]를 사용하는 기본 [SesKtorOperations] 구현입니다.
 *
 * ## 계약
 *
 * Ktor 로컬 SES 값 객체를 `SendEmail` API에 매핑하고 구성된 기본 발신자와 구성 집합 값을
 * 적용합니다. AWS SDK 예외는 변경하지 않고 호출자에게 전파합니다.
 */
class SesKtorTemplate(
    private val sesAsyncClient: SesV2AsyncClient,
    private val defaultFrom: String? = null,
    private val configurationSetName: String? = null,
): SesKtorOperations {

    override suspend fun sendEmail(request: SesEmailRequest): SendEmailResponse =
        sendEmailAsync(request).await()

    override suspend fun sendTemplateEmail(request: SesTemplateEmailRequest): SendEmailResponse =
        sendTemplateEmailAsync(request).await()

    override suspend fun sendRawEmail(request: SesRawEmailRequest): SendEmailResponse =
        sendRawEmailAsync(request).await()

    override suspend fun send(request: SendEmailRequest): SendEmailResponse =
        sendAsync(request).await()

    override fun sendEmailAsync(request: SesEmailRequest): CompletableFuture<SendEmailResponse> =
        sendAsync(request.toSendEmailRequest())

    override fun sendTemplateEmailAsync(request: SesTemplateEmailRequest): CompletableFuture<SendEmailResponse> =
        sendAsync(request.toSendEmailRequest())

    override fun sendRawEmailAsync(request: SesRawEmailRequest): CompletableFuture<SendEmailResponse> =
        sendAsync(request.toSendEmailRequest())

    override fun sendAsync(request: SendEmailRequest): CompletableFuture<SendEmailResponse> =
        sesAsyncClient.sendEmail(request)

    private fun SesEmailRequest.toSendEmailRequest(): SendEmailRequest =
        SendEmailRequest.builder()
            .fromEmailAddress(resolveRequiredFrom(from))
            .destination(destination.toSdkDestination())
            .apply {
                if (replyTo.isNotEmpty()) replyToAddresses(replyTo)
                resolveConfigurationSet(configurationSetName)?.let(::configurationSetName)
            }
            .content(
                EmailContent.builder()
                    .simple(
                        Message.builder()
                            .subject(content(subject, body.charset))
                            .body(body.toSdkBody())
                            .apply {
                                headers.toSdkHeaders().takeIf { it.isNotEmpty() }?.let(::headers)
                                attachments.toSdkAttachments().takeIf { it.isNotEmpty() }?.let(::attachments)
                            }
                            .build()
                    )
                    .build()
            )
            .build()

    private fun SesTemplateEmailRequest.toSendEmailRequest(): SendEmailRequest =
        SendEmailRequest.builder()
            .fromEmailAddress(resolveRequiredFrom(from))
            .destination(destination.toSdkDestination())
            .apply {
                if (replyTo.isNotEmpty()) replyToAddresses(replyTo)
                resolveConfigurationSet(configurationSetName)?.let(::configurationSetName)
            }
            .content(
                EmailContent.builder()
                    .template(
                        Template.builder()
                            .apply {
                                templateName?.let(::templateName)
                                templateArn?.let(::templateArn)
                                templateData?.let(::templateData)
                                headers.toSdkHeaders().takeIf { it.isNotEmpty() }?.let(::headers)
                                attachments.toSdkAttachments().takeIf { it.isNotEmpty() }?.let(::attachments)
                            }
                            .build()
                    )
                    .build()
            )
            .build()

    private fun SesRawEmailRequest.toSendEmailRequest(): SendEmailRequest =
        SendEmailRequest.builder()
            .content(
                EmailContent.builder()
                    .raw(RawMessage.builder().data(SdkBytes.fromByteArray(rawContentForSdk)).build())
                    .build()
            )
            .apply {
                resolveOptionalFrom(from)?.let(::fromEmailAddress)
                destination?.let { destination(it.toSdkDestination()) }
                resolveConfigurationSet(configurationSetName)?.let(::configurationSetName)
            }
            .build()

    private fun SesEmailAddressSet.toSdkDestination(): Destination =
        Destination.builder()
            .apply {
                if (to.isNotEmpty()) toAddresses(to)
                if (cc.isNotEmpty()) ccAddresses(cc)
                if (bcc.isNotEmpty()) bccAddresses(bcc)
            }
            .build()

    private fun SesEmailBody.toSdkBody(): Body =
        Body.builder()
            .apply {
                text?.let { text(content(it, charset)) }
                html?.let { html(content(it, charset)) }
            }
            .build()

    private fun content(data: String, charset: String): Content =
        Content.builder()
            .data(data)
            .charset(charset)
            .build()

    private fun Map<String, String>.toSdkHeaders(): List<MessageHeader> =
        map { (name, value) ->
            MessageHeader.builder()
                .name(name)
                .value(value)
                .build()
        }

    private fun List<SesEmailAttachment>.toSdkAttachments(): List<Attachment> =
        map { attachment ->
            Attachment.builder()
                .rawContent(SdkBytes.fromByteArray(attachment.contentForSdk))
                .fileName(attachment.fileName)
                .contentType(attachment.contentType)
                .contentDisposition(attachment.contentDisposition)
                .contentTransferEncoding(attachment.contentTransferEncoding)
                .apply {
                    attachment.contentDescription?.let(::contentDescription)
                    attachment.contentId?.let(::contentId)
                }
                .build()
        }

    private fun resolveRequiredFrom(from: String?): String =
        resolveOptionalFrom(from)
            ?: throw IllegalArgumentException("from is required when defaultFrom is not configured.")

    private fun resolveOptionalFrom(from: String?): String? =
        (from ?: defaultFrom)?.also { it.requireEmailHeaderValue("from") }

    private fun resolveConfigurationSet(requestConfigurationSetName: String?): String? =
        (requestConfigurationSetName ?: configurationSetName)?.also {
            require(it.isNotBlank()) { "configurationSetName must not be blank." }
        }
}
