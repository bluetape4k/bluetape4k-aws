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
 * [emailRequest]를 바탕으로 email 을 전송합니다.
 *
 * ```
 * val request = SendEmailRequest {
 *      destination {
 *         toAddresses = listOf("user1@example.com", "user2@example.com")
 *      }
 *      message {
 *          subject {
 *             data = "Hello, world!"
 *          }
 *          body {
 *             text {
 *                 data = "Hello, world!"
 *             }
 *             html {
 *                 data = "<h1>Hello, world!</h1>"
 *             }
 *          }
 *     }
 *     source = "noreply@example.com"
 *  }
 * // 메일 발송
 * val response = sesClient.send(request)
 * ```
 * @param emailRequest [SendEmailRequest] email 전송 요청 정보
 * @return [SendEmailResponse] email 전송 결과
 */
suspend fun SesV2Client.send(emailRequest: SendEmailRequest): SendEmailResponse =
    sendEmail(emailRequest)

/**
 * [emailRequest]를 바탕으로 템플릿을 사용한 email 을 벌크로 전송합니다.
 *
 * ```
 * val request = SendBulkTemplatedEmailRequest {
 *      defaultTemplate {
 *          templateName = "default-template"
 *          templateData = """{"name": "John Doe"}"""
 *          subject = "Hello, world!"
 *          html = "<h1>Hello, world!</h1>"
 *          text = "Hello, world!"
 *          replyToAddresses = listOf("no-reply@example.com")
 *    }
 *    source = "sender@example.com"
 * }
 *
 * val response = sesClient.sendBulkTemplated(request)
 * ```
 *
 * @param emailRequest [SendBulkTemplatedEmailRequest] email 전송 요청 정보
 * @return [SendBulkTemplatedEmailResponse] email 전송 결과
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
