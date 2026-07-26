package io.bluetape4k.aws.kotlin.ses

import aws.sdk.kotlin.services.ses.SesClient
import aws.sdk.kotlin.services.ses.createTemplate
import aws.sdk.kotlin.services.ses.getTemplate
import aws.sdk.kotlin.services.ses.model.CreateTemplateResponse
import aws.sdk.kotlin.services.ses.model.SendBulkTemplatedEmailRequest
import aws.sdk.kotlin.services.ses.model.SendBulkTemplatedEmailResponse
import aws.sdk.kotlin.services.ses.model.SendEmailRequest
import aws.sdk.kotlin.services.ses.model.SendEmailResponse
import aws.sdk.kotlin.services.ses.model.SendRawEmailRequest
import aws.sdk.kotlin.services.ses.model.SendRawEmailResponse
import aws.sdk.kotlin.services.ses.model.SendTemplatedEmailRequest
import aws.sdk.kotlin.services.ses.model.SendTemplatedEmailResponse
import aws.sdk.kotlin.services.ses.model.Template
import aws.sdk.kotlin.services.ses.model.TemplateDoesNotExistException
import aws.smithy.kotlin.runtime.ServiceException
import aws.smithy.kotlin.runtime.http.response.statusCode
import kotlinx.coroutines.CancellationException

/**
 * Sends an email from [emailRequest].
 *
 * ```kotlin
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
 * // Send the email.
 * val response = sesClient.send(request)
 * ```
 * @param emailRequest [SendEmailRequest] with the email delivery details.
 * @return [SendEmailResponse] with the email delivery result.
 */
suspend inline fun SesClient.send(emailRequest: SendEmailRequest): SendEmailResponse =
    sendEmail(emailRequest)


/**
 * Sends a raw email from [rawEmailRequest].
 *
 * ```kotlin
 * val request = SendRawEmailRequest {
 *     rawMessage {
 *         data = "From: noreply@example.com\nTo: user1@example.com\nSubject: Hello, world!\n\nHello, world!"
 *     }
 * }
 *
 * val response = sesClient.sendRaw(request)
 * ```
 * @param rawEmailRequest [SendRawEmailRequest] with the raw email delivery details.
 * @return [SendRawEmailResponse] with the raw email delivery result.
 */
suspend inline fun SesClient.sendRaw(rawEmailRequest: SendRawEmailRequest): SendRawEmailResponse =
    sendRawEmail(rawEmailRequest)

/**
 * Sends a templated email from [emailRequest].
 *
 * ```kotlin
 * val request = SendTemplatedEmailRequest {
 *    destination {
 *       toAddresses = listOf("user1@example.com", "user2@example.com")
 *    }
 *    template {
 *          templateName = "template-name"
 *          templateData = """{"name": "John Doe"}"""
 *          subject = "Hello, world!"
 *          html = "<h1>Hello, world!</h1>"
 *          text = "Hello, world!"
 *          replyToAddresses = listOf("no-reply@example.com")
 *    }
 *    source = "sender@example.com"
 * }
 *
 * val response = sesClient.sendTemplated(request)
 * ```
 *
 * @param emailRequest [SendTemplatedEmailRequest] with the templated email delivery details.
 * @return [SendTemplatedEmailResponse] with the templated email delivery result.
 */
suspend inline fun SesClient.sendTemplated(emailRequest: SendTemplatedEmailRequest): SendTemplatedEmailResponse =
    sendTemplatedEmail(emailRequest)

/**
 * Sends bulk templated email from [emailRequest].
 *
 * ```kotlin
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
 * @param emailRequest [SendBulkTemplatedEmailRequest] with the bulk templated email delivery details.
 * @return [SendBulkTemplatedEmailResponse] with the bulk templated email delivery result.
 */
suspend inline fun SesClient.sendBulkTemplated(emailRequest: SendBulkTemplatedEmailRequest): SendBulkTemplatedEmailResponse =
    sendBulkTemplatedEmail(emailRequest)


/**
 * Creates a new [Template].
 *
 * ```kotlin
 * val template = Template {
 *      templateName = "template-name"
 *      subjectPart = "Hello, {{name}}"
 *      htmlPart = "<h1>Hello, {{name}}</h1>"
 *      textPart = "Hello, {{name}}"
 *      replyToAddresses = listOf("no-reply@example.com")
 * }
 * val response = sesClient.createTemplate(template)
 * ```
 *
 * @param template [Template] definition.
 */
suspend inline fun SesClient.createTemplate(template: Template): CreateTemplateResponse =
    createTemplate { this.template = template }

/**
 * Returns the registered [Template] named [templateName], or `null` when it
 * does not exist.
 *
 * ```kotlin
 * val template = sesClient.getTemplate("template-name")
 * ```
 *
 * @param templateName template name.
 * @return template details, or `null` when the template does not exist.
 */
suspend inline fun SesClient.getTemplateOrNull(templateName: String): Template? =
    try {
        getTemplate { this.templateName = templateName }.template
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        if (e.isMissingSesTemplateError()) null else throw e
    }

@PublishedApi
internal fun Throwable.isMissingSesTemplateError(): Boolean {
    if (this is TemplateDoesNotExistException) return true

    val serviceError = this as? ServiceException ?: return false
    val errorCode = serviceError.sdkErrorMetadata.errorCode
    val statusCode = serviceError.sdkErrorMetadata.protocolResponse.statusCode()?.value
    return errorCode == "TemplateDoesNotExist" ||
            errorCode == "TemplateDoesNotExistException" ||
            statusCode == 404
}
