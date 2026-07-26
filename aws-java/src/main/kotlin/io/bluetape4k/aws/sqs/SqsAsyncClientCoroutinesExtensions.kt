package io.bluetape4k.aws.sqs

import kotlinx.coroutines.future.await
import software.amazon.awssdk.services.sqs.SqsAsyncClient
import software.amazon.awssdk.services.sqs.model.ChangeMessageVisibilityBatchRequestEntry
import software.amazon.awssdk.services.sqs.model.ChangeMessageVisibilityBatchResponse
import software.amazon.awssdk.services.sqs.model.ChangeMessageVisibilityResponse
import software.amazon.awssdk.services.sqs.model.DeleteMessageBatchRequestEntry
import software.amazon.awssdk.services.sqs.model.DeleteMessageBatchResponse
import software.amazon.awssdk.services.sqs.model.DeleteMessageResponse
import software.amazon.awssdk.services.sqs.model.DeleteQueueResponse
import software.amazon.awssdk.services.sqs.model.GetQueueUrlResponse
import software.amazon.awssdk.services.sqs.model.ListQueuesResponse
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest
import software.amazon.awssdk.services.sqs.model.ReceiveMessageResponse
import software.amazon.awssdk.services.sqs.model.SendMessageBatchRequestEntry
import software.amazon.awssdk.services.sqs.model.SendMessageBatchResponse
import software.amazon.awssdk.services.sqs.model.SendMessageResponse

/**
 * Creates a queue and returns its queue URL.
 *
 * ```kotlin
 * val queueUrl = client.createQueue("my-queue")
 * // queueUrl.startsWith("http") == true
 * ```
 */
suspend fun SqsAsyncClient.createQueue(queueName: String): String =
    createQueueAsync(queueName).await()

/**
 * Lists queues.
 *
 * ```kotlin
 * val response = client.listQueuesSuspend(prefix = "my-")
 * // response.queueUrls().isNotEmpty() == true
 * ```
 */
suspend fun SqsAsyncClient.listQueuesSuspend(
    prefix: String? = null,
    nextToken: String? = null,
    maxResults: Int? = null,
): ListQueuesResponse =
    listQueuesAsync(prefix, nextToken, maxResults).await()

/**
 * Gets a queue URL by queue name.
 *
 * ```kotlin
 * val response = client.getQueueUrl("my-queue")
 * // response.queueUrl().isNotEmpty() == true
 * ```
 */
suspend fun SqsAsyncClient.getQueueUrl(
    queueName: String,
    queueOwnerAWSAccountId: String? = null,
): GetQueueUrlResponse =
    getQueueUrlAsync(queueName, queueOwnerAWSAccountId).await()

/**
 * Sends a message to a queue.
 *
 * ```kotlin
 * val response = client.send(queueUrl, "hello world")
 * // response.messageId().isNotEmpty() == true
 * ```
 */
suspend fun SqsAsyncClient.send(
    queueUrl: String,
    messageBody: String,
    delaySeconds: Int? = null,
): SendMessageResponse =
    sendAsync(queueUrl, messageBody, delaySeconds).await()

/**
 * Sends multiple messages to a queue as a batch.
 *
 * ```kotlin
 * val response = client.sendBatch(queueUrl, entry1, entry2)
 * // response.successful().size == 2
 * ```
 */
suspend fun SqsAsyncClient.sendBatch(
    queueUrl: String,
    vararg entries: SendMessageBatchRequestEntry,
): SendMessageBatchResponse =
    sendBatchAsync(queueUrl, *entries).await()

/**
 * Sends a collection of messages as a batch.
 *
 * ```kotlin
 * val response = client.sendBatch(queueUrl, listOf(entry1, entry2))
 * // response.successful().size == 2
 * ```
 */
suspend fun SqsAsyncClient.sendBatch(
    queueUrl: String,
    entries: Collection<SendMessageBatchRequestEntry>,
): SendMessageBatchResponse =
    sendBatchAsync(queueUrl, entries).await()

/**
 * Receives messages from a queue.
 *
 * ```kotlin
 * val response = client.receiveMessages(queueUrl, maxResults = 5)
 * // response.messages().size <= 5
 * ```
 */
suspend fun SqsAsyncClient.receiveMessages(
    queueUrl: String,
    maxResults: Int? = null,
    builder: ReceiveMessageRequest.Builder.() -> Unit = {},
): ReceiveMessageResponse =
    receiveMessagesAsync(queueUrl, maxResults, builder).await()

/**
 * Changes a message visibility timeout.
 *
 * ```kotlin
 * val response = client.changeMessageVisibility(queueUrl, receiptHandle, visibilityTimeout = 30)
 * // response.sdkHttpResponse().isSuccessful == true
 * ```
 */
suspend fun SqsAsyncClient.changeMessageVisibility(
    queueUrl: String,
    receiptHandle: String? = null,
    visibilityTimeout: Int? = null,
): ChangeMessageVisibilityResponse =
    changeMessageVisibilityAsync(queueUrl, receiptHandle, visibilityTimeout).await()

/**
 * Changes visibility timeouts for multiple messages as a batch.
 *
 * ```kotlin
 * val response = client.changeMessageVisibilityBatch(queueUrl, entry1, entry2)
 * // response.successful().size == 2
 * ```
 */
suspend fun SqsAsyncClient.changeMessageVisibilityBatch(
    queueUrl: String,
    vararg entries: ChangeMessageVisibilityBatchRequestEntry,
): ChangeMessageVisibilityBatchResponse =
    changeMessageVisibilityBatchAsync(queueUrl, *entries).await()

/**
 * Changes visibility timeouts for a collection of messages as a batch.
 *
 * ```kotlin
 * val response = client.changeMessageVisibilityBatch(queueUrl, listOf(entry1, entry2))
 * // response.successful().size == 2
 * ```
 */
suspend fun SqsAsyncClient.changeMessageVisibilityBatch(
    queueUrl: String,
    entries: Collection<ChangeMessageVisibilityBatchRequestEntry>,
): ChangeMessageVisibilityBatchResponse =
    changeMessageVisibilityBatchAsync(queueUrl, entries).await()

/**
 * Deletes a message from a queue.
 *
 * ```kotlin
 * val response = client.deleteMessage(queueUrl, receiptHandle)
 * // response.sdkHttpResponse().isSuccessful == true
 * ```
 */
suspend fun SqsAsyncClient.deleteMessage(
    queueUrl: String,
    receiptHandle: String? = null,
): DeleteMessageResponse =
    deleteMessageAsync(queueUrl, receiptHandle).await()

/**
 * Deletes multiple messages as a batch.
 *
 * ```kotlin
 * val response = client.deleteMessageBatch(queueUrl, entry1, entry2)
 * // response.successful().size == 2
 * ```
 */
suspend fun SqsAsyncClient.deleteMessageBatch(
    queueUrl: String,
    vararg entries: DeleteMessageBatchRequestEntry,
): DeleteMessageBatchResponse =
    deleteMessageBatchAsync(queueUrl, *entries).await()

/**
 * Deletes a collection of messages as a batch.
 *
 * ```kotlin
 * val response = client.deleteMessageBatch(queueUrl, listOf(entry1, entry2))
 * // response.successful().size == 2
 * ```
 */
suspend fun SqsAsyncClient.deleteMessageBatch(
    queueUrl: String,
    entries: Collection<DeleteMessageBatchRequestEntry>,
): DeleteMessageBatchResponse =
    deleteMessageBatchAsync(queueUrl, entries).await()

/**
 * Deletes a queue.
 *
 * ```kotlin
 * val response = client.deleteQueue(queueUrl)
 * // response.sdkHttpResponse().isSuccessful == true
 * ```
 */
suspend fun SqsAsyncClient.deleteQueue(queueUrl: String): DeleteQueueResponse =
    deleteQueueAsync(queueUrl).await()
