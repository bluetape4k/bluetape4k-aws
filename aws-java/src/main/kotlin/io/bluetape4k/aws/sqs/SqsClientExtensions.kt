package io.bluetape4k.aws.sqs

import io.bluetape4k.aws.http.SdkHttpClientProvider
import io.bluetape4k.aws.sqs.model.sendMessageRequestOf
import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.utils.ShutdownQueue
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider
import software.amazon.awssdk.http.SdkHttpClient
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.sqs.SqsClient
import software.amazon.awssdk.services.sqs.SqsClientBuilder
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
import java.net.URI

private const val MIN_RECEIVE_MESSAGES = 1
private const val MAX_RECEIVE_MESSAGES = 10

/**
 * Creates a [SqsClient] with a DSL builder.
 *
 * The created client is registered with [ShutdownQueue] so it is closed automatically on JVM shutdown.
 *
 * ```kotlin
 * val client = sqsClient { region(Region.AP_NORTHEAST_2) }
 * // client != null
 * ```
 */
inline fun sqsClient(
    builder: SqsClientBuilder.() -> Unit,
): SqsClient =
    SqsClient
        .builder()
        .apply(builder)
        .build()
        .apply {
            ShutdownQueue.register(this)
        }

/**
 * Creates a [SqsClient] with convenience parameters.
 *
 * @param endpoint Endpoint URI, specified when overriding for LocalStack or similar.
 * @param region AWS region.
 * @param credentialsProvider Credentials provider.
 * @param httpClient HTTP client.
 *
 * ```kotlin
 * val client = sqsClientOf(endpoint = URI("http://localhost:4566"), region = Region.AP_NORTHEAST_2)
 * // client != null
 * ```
 */
inline fun sqsClientOf(
    endpoint: URI? = null,
    region: Region? = null,
    credentialsProvider: AwsCredentialsProvider? = null,
    httpClient: SdkHttpClient = SdkHttpClientProvider.defaultHttpClient,
    builder: SqsClientBuilder.() -> Unit = {},
): SqsClient =
    sqsClient {
        endpoint?.let { endpointOverride(it) }
        region?.let { region(it) }
        credentialsProvider?.let { credentialsProvider(it) }
        httpClient(httpClient)

        builder()
    }

/**
 * Creates an SQS queue with [queueName] and returns the queue URL.
 *
 * ```kotlin
 * val queueUrl = sqsClient.createQueue("my-queue")
 * // queueUrl.contains("my-queue") == true
 * ```
 */
fun SqsClient.createQueue(queueName: String): String {
    queueName.requireNotBlank("queueName")
    return createQueue { it.queueName(queueName) }.queueUrl()
}

/**
 * Lists SQS queues.
 *
 * ```kotlin
 * val response = sqsClient.listQueues(prefix = "my-")
 * // response.queueUrls().isNotEmpty() == true
 * ```
 */
fun SqsClient.listQueues(
    prefix: String? = null,
    nextToken: String? = null,
    maxResults: Int? = null,
): ListQueuesResponse =
    listQueues {
        prefix?.run { it.queueNamePrefix(prefix) }
        nextToken?.run { it.nextToken(nextToken) }
        maxResults?.run { it.maxResults(maxResults) }
    }

/**
 * Gets the SQS queue URL for [queueName].
 *
 * ```kotlin
 * val response = sqsClient.getQueueUrl("my-queue")
 * // response.queueUrl().contains("my-queue") == true
 * ```
 */
fun SqsClient.getQueueUrl(queueName: String): GetQueueUrlResponse {
    queueName.requireNotBlank("queueName")
    return getQueueUrl { it.queueName(queueName) }
}

/**
 * Sends [messageBody] to the [queueUrl] queue.
 *
 * ```kotlin
 * val response = sqsClient.send("https://sqs.ap-northeast-2.amazonaws.com/123/my-queue", "hello")
 * // response.messageId().isNotBlank() == true
 * ```
 */
fun SqsClient.send(
    queueUrl: String,
    messageBody: String,
): SendMessageResponse = sendMessage(sendMessageRequestOf(queueUrl, messageBody))

/**
 * Sends messages as a batch.
 *
 * If [entries] is not in the 1..10 range, throws [IllegalArgumentException] before making a network call.
 *
 * ```kotlin
 * val entry = sendMessageBatchRequestEntryOf(id = "1", messageGroupId = "default", messageBody = "hello")
 * val response = sqsClient.sendBatch(queueUrl, entry)
 * // response.successful().size == 1
 * ```
 */
fun SqsClient.sendBatch(
    queueUrl: String,
    vararg entries: SendMessageBatchRequestEntry,
): SendMessageBatchResponse {
    queueUrl.requireNotBlank("queueUrl")
    validateSqsBatchSize(entries.size, "entries")
    return sendMessageBatch {
        it.queueUrl(queueUrl)
        it.entries(*entries)
    }
}

/**
 * Sends messages as a batch.
 *
 * If [entries] is not in the 1..10 range, throws [IllegalArgumentException] before making a network call.
 *
 * ```kotlin
 * val entries = listOf(sendMessageBatchRequestEntryOf(id = "1", messageGroupId = "default", messageBody = "hello"))
 * val response = sqsClient.sendBatch(queueUrl, entries)
 * // response.failed().isEmpty() == true
 * ```
 */
fun SqsClient.sendBatch(
    queueUrl: String,
    entries: Collection<SendMessageBatchRequestEntry>,
): SendMessageBatchResponse {
    queueUrl.requireNotBlank("queueUrl")
    validateSqsBatchSize(entries.size, "entries")
    return sendMessageBatch {
        it.queueUrl(queueUrl)
        it.entries(entries)
    }
}

/**
 * Receives messages from a queue.
 *
 * When [maxResults] is set, validates the SQS 1..10 constraint up front and fails before the network call.
 *
 * ```kotlin
 * val response = sqsClient.receiveMessages(queueUrl, maxResults = 10) {
 *     waitTimeSeconds(5)
 * }
 * // response.messages().size <= 10
 * ```
 */
fun SqsClient.receiveMessages(
    queueUrl: String,
    maxResults: Int? = null,
    builder: ReceiveMessageRequest.Builder.() -> Unit = {},
): ReceiveMessageResponse {
    queueUrl.requireNotBlank("queueUrl")
    maxResults?.let { validateReceiveMessageCount(it) }

    return receiveMessage {
        it.queueUrl(queueUrl)
        maxResults?.run { it.maxNumberOfMessages(this) }
        it.builder()
    }
}

/**
 * Changes queue message visibility timeout.
 *
 * When [visibilityTimeout] is set, validates the SQS 0..43200 constraint up front and fails before the network call.
 *
 * ```kotlin
 * val response = sqsClient.changeMessageVisibility(queueUrl, receiptHandle = handle, visibilityTimeout = 30)
 * // response.sdkHttpResponse().isSuccessful == true
 * ```
 */
fun SqsClient.changeMessageVisibility(
    queueUrl: String,
    receiptHandle: String? = null,
    visibilityTimeout: Int? = null,
): ChangeMessageVisibilityResponse {
    queueUrl.requireNotBlank("queueUrl")
    visibilityTimeout?.validateSqsVisibilityTimeout("visibilityTimeout")
    return changeMessageVisibility {
        it.queueUrl(queueUrl)
        receiptHandle?.run { it.receiptHandle(this) }
        visibilityTimeout?.run { it.visibilityTimeout(this) }
    }
}

/**
 * Changes visibility timeouts for received messages as a batch.
 *
 * If [entries] is not in the 1..10 range, throws [IllegalArgumentException] before making a network call.
 *
 * ```kotlin
 * val entry = ChangeMessageVisibilityBatchRequestEntry.builder()
 *     .id("1")
 *     .receiptHandle(receiptHandle)
 *     .visibilityTimeout(30)
 *     .build()
 * val response = sqsClient.changeMessageVisibilityBatch(queueUrl, entry)
 * // response.successful().size == 1
 * ```
 */
fun SqsClient.changeMessageVisibilityBatch(
    queueUrl: String,
    vararg entries: ChangeMessageVisibilityBatchRequestEntry,
): ChangeMessageVisibilityBatchResponse {
    queueUrl.requireNotBlank("queueUrl")
    validateSqsBatchSize(entries.size, "entries")
    return changeMessageVisibilityBatch {
        it.queueUrl(queueUrl)
        it.entries(*entries)
    }
}

/**
 * Changes visibility timeouts for received messages as a batch.
 *
 * If [entries] is not in the 1..10 range, throws [IllegalArgumentException] before making a network call.
 */
fun SqsClient.changeMessageVisibilityBatch(
    queueUrl: String,
    entries: Collection<ChangeMessageVisibilityBatchRequestEntry>,
): ChangeMessageVisibilityBatchResponse {
    queueUrl.requireNotBlank("queueUrl")
    validateSqsBatchSize(entries.size, "entries")
    return changeMessageVisibilityBatch {
        it.queueUrl(queueUrl)
        it.entries(entries)
    }
}

/**
 * Deletes a message from a queue.
 *
 * ```kotlin
 * val response = sqsClient.deleteMessage(queueUrl, receiptHandle = handle)
 * // response.sdkHttpResponse().isSuccessful == true
 * ```
 */
fun SqsClient.deleteMessage(
    queueUrl: String,
    receiptHandle: String? = null,
): DeleteMessageResponse {
    queueUrl.requireNotBlank("queueUrl")
    return deleteMessage {
        it.queueUrl(queueUrl)
        receiptHandle?.run { it.receiptHandle(this) }
    }
}

/**
 * Deletes multiple messages as a batch.
 *
 * If [entries] is not in the 1..10 range, throws [IllegalArgumentException] before making a network call.
 *
 * ```kotlin
 * val entry = DeleteMessageBatchRequestEntry.builder()
 *     .id("1")
 *     .receiptHandle(receiptHandle)
 *     .build()
 * val response = sqsClient.deleteMessageBatch(queueUrl, entry)
 * // response.successful().size == 1
 * ```
 */
fun SqsClient.deleteMessageBatch(
    queueUrl: String,
    vararg entries: DeleteMessageBatchRequestEntry,
): DeleteMessageBatchResponse {
    queueUrl.requireNotBlank("queueUrl")
    validateSqsBatchSize(entries.size, "entries")
    return deleteMessageBatch {
        it.queueUrl(queueUrl)
        it.entries(*entries)
    }
}

/**
 * Deletes multiple messages as a batch.
 *
 * If [entries] is not in the 1..10 range, throws [IllegalArgumentException] before making a network call.
 */
fun SqsClient.deleteMessageBatch(
    queueUrl: String,
    entries: Collection<DeleteMessageBatchRequestEntry>,
): DeleteMessageBatchResponse {
    queueUrl.requireNotBlank("queueUrl")
    validateSqsBatchSize(entries.size, "entries")
    return deleteMessageBatch {
        it.queueUrl(queueUrl)
        it.entries(entries)
    }
}

/**
 * Deletes an SQS queue.
 *
 * ```kotlin
 * val response = sqsClient.deleteQueue("https://sqs.ap-northeast-2.amazonaws.com/123/my-queue")
 * // response.sdkHttpResponse().isSuccessful == true
 * ```
 */
fun SqsClient.deleteQueue(queueUrl: String): DeleteQueueResponse {
    queueUrl.requireNotBlank("queueUrl")

    return deleteQueue {
        it.queueUrl(queueUrl)
    }
}

private fun validateReceiveMessageCount(maxResults: Int) {
    require(maxResults in MIN_RECEIVE_MESSAGES..MAX_RECEIVE_MESSAGES) {
        "maxResults must be in $MIN_RECEIVE_MESSAGES..$MAX_RECEIVE_MESSAGES, but was $maxResults"
    }
}
