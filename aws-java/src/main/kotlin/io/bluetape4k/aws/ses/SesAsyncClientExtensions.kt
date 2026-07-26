package io.bluetape4k.aws.ses

import software.amazon.awssdk.services.ses.SesAsyncClient
import software.amazon.awssdk.services.ses.model.SendBulkTemplatedEmailRequest
import software.amazon.awssdk.services.ses.model.SendBulkTemplatedEmailResponse
import software.amazon.awssdk.services.ses.model.SendEmailRequest
import software.amazon.awssdk.services.ses.model.SendEmailResponse
import software.amazon.awssdk.services.ses.model.SendRawEmailRequest
import software.amazon.awssdk.services.ses.model.SendRawEmailResponse
import software.amazon.awssdk.services.ses.model.SendTemplatedEmailRequest
import software.amazon.awssdk.services.ses.model.SendTemplatedEmailResponse
import java.util.concurrent.CompletableFuture

/**
 * Sends an email asynchronously from [SendEmailRequest] data.
 *
 * ```kotlin
 * val response = client.send(request).await()
 * response.messageId().shouldNotBeEmpty()
 * log.debug { "response=$response" }
 * ```
 *
 * @param emailRequest [SendEmailRequest] email send request data.
 * @return [CompletableFuture]<[SendEmailResponse]> email send response data.
 */
fun SesAsyncClient.sendAsync(
    emailRequest: SendEmailRequest,
): CompletableFuture<SendEmailResponse> =
    sendEmail(emailRequest)

/**
 * Sends an email asynchronously from [SendRawEmailRequest] data.
 *
 * ```kotlin
 * val response = client.sendRaw(request).await()
 * response.messageId().shouldNotBeEmpty()
 * log.debug { "response=$response" }
 * ```
 *
 * @param rawEmailRequest [SendRawEmailRequest] email send request data.
 * @return [CompletableFuture]<[SendRawEmailResponse]> email send response data.
 */
fun SesAsyncClient.sendRawAsync(
    rawEmailRequest: SendRawEmailRequest,
): CompletableFuture<SendRawEmailResponse> =
    sendRawEmail(rawEmailRequest)

/**
 * Sends an email asynchronously from [SendTemplatedEmailRequest] data.
 *
 * ```kotlin
 * val response = client.sendTemplated(request).await()
 * response.messageId().shouldNotBeEmpty()
 * log.debug { "response=$response" }
 * ```
 *
 * @param templatedEmailRequest [SendTemplatedEmailRequest] email send request data.
 * @return [CompletableFuture]<[SendTemplatedEmailResponse]> email send response data.
 */
fun SesAsyncClient.sendTemplatedAsync(
    templatedEmailRequest: SendTemplatedEmailRequest,
): CompletableFuture<SendTemplatedEmailResponse> =
    sendTemplatedEmail(templatedEmailRequest)

/**
 * Sends an email asynchronously from [SendBulkTemplatedEmailRequest] data.
 *
 * ```kotlin
 * val response = client.sendBulkTemplated(request).await()
 * response.messageId().shouldNotBeEmpty()
 * log.debug { "response=$response" }
 * ```
 *
 * @param bulkTemplatedEmailRequest [SendBulkTemplatedEmailRequest] email send request data.
 * @return [CompletableFuture]<[SendBulkTemplatedEmailResponse]> email send response data.
 */
fun SesAsyncClient.sendBulkTemplatedAsync(
    bulkTemplatedEmailRequest: SendBulkTemplatedEmailRequest,
): CompletableFuture<SendBulkTemplatedEmailResponse> =
    sendBulkTemplatedEmail(bulkTemplatedEmailRequest)
