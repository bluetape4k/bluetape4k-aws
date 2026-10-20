package io.bluetape4k.aws.ktor.ses

import aws.sdk.kotlin.services.sesv2.SesV2Client
import aws.sdk.kotlin.services.sesv2.model.Attachment
import aws.sdk.kotlin.services.sesv2.model.Body
import aws.sdk.kotlin.services.sesv2.model.Content
import aws.sdk.kotlin.services.sesv2.model.Destination
import aws.sdk.kotlin.services.sesv2.model.EmailContent
import aws.sdk.kotlin.services.sesv2.model.Message
import aws.sdk.kotlin.services.sesv2.model.MessageHeader
import aws.sdk.kotlin.services.sesv2.model.RawMessage
import aws.sdk.kotlin.services.sesv2.model.SendEmailRequest
import aws.sdk.kotlin.services.sesv2.model.SendEmailResponse
import aws.sdk.kotlin.services.sesv2.model.Template

/**
 * [SesV2Client]를 사용하는 기본 [SesKtorOperations] 구현입니다.
 *
 * ## 계약
 *
 * Ktor 로컬 SES 값 객체를 `SendEmail` API에 매핑하고 구성된 기본 발신자와 구성 집합 값을
 * 적용합니다. AWS Kotlin SDK 예외는 변경하지 않고 호출자에게 전파합니다.
 */
class SesKtorTemplate(
    private val sesClient: SesV2Client,
    private val defaultFrom: String? = null,
    private val configurationSetName: String? = null,
): SesKtorOperations {

    override suspend fun sendEmail(request: SesEmailRequest): SendEmailResponse =
        sesClient.sendEmail(request.toSendEmailRequest())

    override suspend fun sendTemplateEmail(request: SesTemplateEmailRequest): SendEmailResponse =
        sesClient.sendEmail(request.toSendEmailRequest())

    override suspend fun sendRawEmail(request: SesRawEmailRequest): SendEmailResponse =
        sesClient.sendEmail(request.toSendEmailRequest())

    override suspend fun send(request: SendEmailRequest): SendEmailResponse =
        sesClient.sendEmail(request)

    private fun SesEmailRequest.toSendEmailRequest(): SendEmailRequest =
        SendEmailRequest {
            fromEmailAddress = resolveRequiredFrom(this@toSendEmailRequest.from)
            destination = this@toSendEmailRequest.destination.toSdkDestination()
            replyToAddresses = this@toSendEmailRequest.replyTo.takeIf { it.isNotEmpty() }
            configurationSetName = resolveConfigurationSet(this@toSendEmailRequest.configurationSetName)
            content = EmailContent {
                simple = Message {
                    subject = contentOf(
                        this@toSendEmailRequest.subject,
                        this@toSendEmailRequest.body.charset,
                    )
                    body = this@toSendEmailRequest.body.toSdkBody()
                    headers = this@toSendEmailRequest.headers.toSdkHeaders().takeIf { it.isNotEmpty() }
                    attachments = this@toSendEmailRequest.attachments.toSdkAttachments().takeIf { it.isNotEmpty() }
                }
            }
        }

    private fun SesTemplateEmailRequest.toSendEmailRequest(): SendEmailRequest =
        SendEmailRequest {
            fromEmailAddress = resolveRequiredFrom(this@toSendEmailRequest.from)
            destination = this@toSendEmailRequest.destination.toSdkDestination()
            replyToAddresses = this@toSendEmailRequest.replyTo.takeIf { it.isNotEmpty() }
            configurationSetName = resolveConfigurationSet(this@toSendEmailRequest.configurationSetName)
            content = EmailContent {
                template = Template {
                    templateName = this@toSendEmailRequest.templateName
                    templateArn = this@toSendEmailRequest.templateArn
                    templateData = this@toSendEmailRequest.templateData
                    headers = this@toSendEmailRequest.headers.toSdkHeaders().takeIf { it.isNotEmpty() }
                    attachments = this@toSendEmailRequest.attachments.toSdkAttachments().takeIf { it.isNotEmpty() }
                }
            }
        }

    private fun SesRawEmailRequest.toSendEmailRequest(): SendEmailRequest =
        SendEmailRequest {
            content = EmailContent {
                raw = RawMessage { data = rawContentForSdk.copyOf() }
            }
            fromEmailAddress = resolveOptionalFrom(from)
            this@toSendEmailRequest.destination?.let { destination = it.toSdkDestination() }
            configurationSetName = resolveConfigurationSet(this@toSendEmailRequest.configurationSetName)
        }

    private fun SesEmailAddressSet.toSdkDestination(): Destination =
        Destination {
            toAddresses = to.takeIf { it.isNotEmpty() }
            ccAddresses = cc.takeIf { it.isNotEmpty() }
            bccAddresses = bcc.takeIf { it.isNotEmpty() }
        }

    private fun SesEmailBody.toSdkBody(): Body =
        Body {
            this.text = this@toSdkBody.text?.let { contentOf(it, this@toSdkBody.charset) }
            this.html = this@toSdkBody.html?.let { contentOf(it, this@toSdkBody.charset) }
        }

    private fun contentOf(data: String, charset: String): Content =
        Content {
            this.data = data
            this.charset = charset
        }

    private fun Map<String, String>.toSdkHeaders(): List<MessageHeader> =
        map { (name, value) ->
            MessageHeader { this.name = name; this.value = value }
        }

    private fun List<SesEmailAttachment>.toSdkAttachments(): List<Attachment> =
        map { attachment ->
            Attachment {
                rawContent = attachment.contentForSdk.copyOf()
                fileName = attachment.fileName
                contentType = attachment.contentType
                contentDisposition = attachment.contentDisposition
                contentTransferEncoding = attachment.contentTransferEncoding
                contentDescription = attachment.contentDescription
                contentId = attachment.contentId
            }
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
