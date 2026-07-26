package io.bluetape4k.aws.ses

import software.amazon.awssdk.services.ses.SesClient
import software.amazon.awssdk.services.ses.model.SendBulkTemplatedEmailRequest
import software.amazon.awssdk.services.ses.model.SendBulkTemplatedEmailResponse
import software.amazon.awssdk.services.ses.model.SendEmailRequest
import software.amazon.awssdk.services.ses.model.SendEmailResponse
import software.amazon.awssdk.services.ses.model.SendRawEmailRequest
import software.amazon.awssdk.services.ses.model.SendRawEmailResponse
import software.amazon.awssdk.services.ses.model.SendTemplatedEmailRequest
import software.amazon.awssdk.services.ses.model.SendTemplatedEmailResponse

/**
 * Sends an email from [SendEmailRequest] data.
 *
 * ```kotlin
 * val response = client.send(request)
 * response.messageId().shouldNotBeEmpty()
 * log.debug { "response=$response" }
 * ```
 *
 * @param request [SendEmailRequest] email send request data.
 * @return [SendEmailResponse] email send response data.
 */
fun SesClient.send(request: SendEmailRequest): SendEmailResponse =
    sendEmail(request)

/**
 * Sends an email from [SendRawEmailRequest] data.
 *
 * ```kotlin
 * val response = client.sendRaw(rawEmailRequest)
 * log.debug { "response=$response" }
 * ```
 *
 * @param request [SendRawEmailRequest] email send request data.
 * @return [SendRawEmailResponse] email send response data.
 */
fun SesClient.sendRaw(request: SendRawEmailRequest): SendRawEmailResponse =
    sendRawEmail(request)

/**
 * Sends an email from [SendTemplatedEmailRequest] data.
 *
 * ```kotlin
 * val response = client.sendTemplated(templatedEmailRequest)
 * log.debug { "response=$response" }
 * ```
 *
 * @param request [SendTemplatedEmailRequest] email send request data.
 * @return [SendTemplatedEmailResponse] email send response data.
 */
fun SesClient.sendTemplated(request: SendTemplatedEmailRequest): SendTemplatedEmailResponse =
    sendTemplatedEmail(request)

/**
 * Sends an email from [SendBulkTemplatedEmailRequest] data.
 *
 * ```kotlin
 * val response = client.sendBulkTemplated(bulkTemplatedEmailRequest)
 * log.debug { "response=$response" }
 * ```
 *
 * @param request [SendBulkTemplatedEmailRequest] email send request data.
 * @return [SendBulkTemplatedEmailResponse] email send response data.
 */
fun SesClient.sendBulkTemplated(request: SendBulkTemplatedEmailRequest): SendBulkTemplatedEmailResponse =
    sendBulkTemplatedEmail(request)
