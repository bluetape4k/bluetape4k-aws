package io.bluetape4k.aws.ses

import kotlinx.coroutines.future.await
import software.amazon.awssdk.services.ses.SesAsyncClient
import software.amazon.awssdk.services.ses.model.SendBulkTemplatedEmailRequest
import software.amazon.awssdk.services.ses.model.SendBulkTemplatedEmailResponse
import software.amazon.awssdk.services.ses.model.SendEmailRequest
import software.amazon.awssdk.services.ses.model.SendEmailResponse
import software.amazon.awssdk.services.ses.model.SendRawEmailRequest
import software.amazon.awssdk.services.ses.model.SendRawEmailResponse
import software.amazon.awssdk.services.ses.model.SendTemplatedEmailRequest
import software.amazon.awssdk.services.ses.model.SendTemplatedEmailResponse

/**
 * Sends a [SendEmailRequest] from a coroutine.
 *
 * ## Behavior and contract
 * - Calls [sendAsync] internally, then waits for completion with `await()`.
 * - The return value is the same [SendEmailResponse] returned by the async API.
 *
 * ```kotlin
 * val response = sesAsyncClient.send(sendEmailRequest)
 * val messageId = response.messageId()
 * // messageId.isNotBlank() == true
 * ```
 */
suspend inline fun SesAsyncClient.send(request: SendEmailRequest): SendEmailResponse =
    sendAsync(request).await()

/**
 * Sends a [SendRawEmailRequest] from a coroutine.
 *
 * ## Behavior and contract
 * - Calls [sendRawAsync] internally, then waits for completion with `await()`.
 * - The return value is the same [SendRawEmailResponse] returned by the async API.
 *
 * ```kotlin
 * val response = sesAsyncClient.sendRaw(sendRawEmailRequest)
 * val messageId = response.messageId()
 * // messageId.isNotBlank() == true
 * ```
 */
suspend inline fun SesAsyncClient.sendRaw(request: SendRawEmailRequest): SendRawEmailResponse =
    sendRawAsync(request).await()

/**
 * Sends a [SendTemplatedEmailRequest] from a coroutine.
 *
 * ## Behavior and contract
 * - Calls [sendTemplatedAsync] internally, then waits for completion with `await()`.
 * - The return value is the same [SendTemplatedEmailResponse] returned by the async API.
 *
 * ```kotlin
 * val response = sesAsyncClient.sendTemplated(sendTemplatedEmailRequest)
 * val messageId = response.messageId()
 * // messageId.isNotBlank() == true
 * ```
 */
suspend inline fun SesAsyncClient.sendTemplated(request: SendTemplatedEmailRequest): SendTemplatedEmailResponse =
    sendTemplatedAsync(request).await()

/**
 * Sends a [SendBulkTemplatedEmailRequest] from a coroutine.
 *
 * ## Behavior and contract
 * - Calls [sendBulkTemplatedAsync] internally, then waits for completion with `await()`.
 * - The return value is the same [SendBulkTemplatedEmailResponse] returned by the async API.
 *
 * ```kotlin
 * val response = sesAsyncClient.sendBulkTemplated(sendBulkTemplatedEmailRequest)
 * val statuses = response.status()
 * // statuses.isNotEmpty() == true
 * ```
 */
suspend inline fun SesAsyncClient.sendBulkTemplated(
    request: SendBulkTemplatedEmailRequest,
): SendBulkTemplatedEmailResponse =
    sendBulkTemplatedAsync(request).await()
