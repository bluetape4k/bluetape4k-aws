package io.bluetape4k.aws.spring.ses

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
 * Coroutine-friendly [SesOperations] implementation backed by AWS SDK v2 [SesV2AsyncClient].
 *
 * ## Contract
 *
 * Maps bluetape4k SES value objects to the `SendEmail` API, applies configured
 * default sender/configuration set values, and lets AWS SDK exceptions
 * propagate to callers.
 */
class SesCoroutinesMailSender(
    private val sesAsyncClient: SesV2AsyncClient,
    private val properties: SesProperties,
): SesOperations {

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
                    .raw(RawMessage.builder().data(SdkBytes.fromByteArray(rawContent)).build())
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
                .rawContent(SdkBytes.fromByteArray(attachment.content))
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
            ?: throw IllegalArgumentException("from is required when bluetape4k.aws.ses.default-from is not configured.")

    private fun resolveOptionalFrom(from: String?): String? =
        (from ?: properties.defaultFrom)?.also { it.requireEmailHeaderValue("from") }

    private fun resolveConfigurationSet(configurationSetName: String?): String? =
        (configurationSetName ?: properties.configurationSetName)?.also {
            require(it.isNotBlank()) { "configurationSetName must not be blank." }
        }
}
