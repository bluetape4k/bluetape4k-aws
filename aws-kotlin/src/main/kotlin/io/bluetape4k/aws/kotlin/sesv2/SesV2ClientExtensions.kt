package io.bluetape4k.aws.kotlin.sesv2

import aws.sdk.kotlin.services.sesv2.SesV2Client
import aws.sdk.kotlin.services.sesv2.model.GetEmailTemplateRequest
import aws.sdk.kotlin.services.sesv2.model.NotFoundException
import aws.sdk.kotlin.services.sesv2.model.SendBulkEmailRequest
import aws.sdk.kotlin.services.sesv2.model.SendBulkEmailResponse
import aws.sdk.kotlin.services.sesv2.model.SendEmailRequest
import aws.sdk.kotlin.services.sesv2.model.SendEmailResponse
import aws.sdk.kotlin.services.sesv2.model.Template
import aws.smithy.kotlin.runtime.ServiceException
import aws.smithy.kotlin.runtime.http.response.statusCode
import kotlinx.coroutines.CancellationException

/**
 * Sends an email from [emailRequest].
 *
 * ```kotlin
 * val request = SendEmailRequest {
 *     fromEmailAddress = "noreply@example.com"
 *     // Configure destination and content here.
 * }
 * // Send the email.
 * val response = sesClient.send(request)
 * ```
 * @param emailRequest [SendEmailRequest] with the email delivery details.
 * @return [SendEmailResponse] with the email delivery result.
 */
suspend fun SesV2Client.send(emailRequest: SendEmailRequest): SendEmailResponse =
    sendEmail(emailRequest)

/**
 * Sends bulk templated email from [emailRequest].
 *
 * ```kotlin
 * val request = SendBulkEmailRequest {
 *     fromEmailAddress = "sender@example.com"
 *     // Configure bulk destinations and default content here.
 * }
 *
 * val response = sesClient.sendBulk(request)
 * ```
 *
 * @param emailRequest [SendBulkEmailRequest] with the bulk email delivery details.
 * @return [SendBulkEmailResponse] with the bulk email delivery result.
 */
suspend fun SesV2Client.sendBulk(emailRequest: SendBulkEmailRequest): SendBulkEmailResponse =
    sendBulkEmail(emailRequest)


/**
 * Returns the registered email [Template] named [templateName], or `null` when
 * it does not exist.
 *
 * ```
 * val template = sesClient.getTemplate("template-name")
 * ```
 *
 * @param templateName template name.
 * @return template details, or `null` when the template does not exist.
 */
suspend fun SesV2Client.getTemplateOrNull(templateName: String): Template? {
    return try {
        val response = getEmailTemplate(GetEmailTemplateRequest { this.templateName = templateName })
        response.templateContent?.let {
            Template {
                this.templateName = response.templateName
                this.templateContent = response.templateContent
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        if (e.isMissingSesV2TemplateError()) null else throw e
    }
}

internal fun Throwable.isMissingSesV2TemplateError(): Boolean {
    if (this is NotFoundException) return true

    val serviceError = this as? ServiceException ?: return false
    val errorCode = serviceError.sdkErrorMetadata.errorCode
    val statusCode = serviceError.sdkErrorMetadata.protocolResponse.statusCode()?.value
    return errorCode == "NotFound" ||
            errorCode == "NotFoundException" ||
            statusCode == 404
}
