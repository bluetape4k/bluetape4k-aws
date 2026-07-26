package io.bluetape4k.aws.sqs

import io.bluetape4k.aws.http.SdkAsyncHttpClientProvider
import io.bluetape4k.aws.sqs.model.sendMessageRequestOf
import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.utils.ShutdownQueue
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider
import software.amazon.awssdk.http.async.SdkAsyncHttpClient
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.sqs.SqsAsyncClient
import software.amazon.awssdk.services.sqs.SqsAsyncClientBuilder
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
import java.util.concurrent.CompletableFuture

private const val MIN_RECEIVE_MESSAGES = 1
private const val MAX_RECEIVE_MESSAGES = 10

/**
 * Creates a [SqsAsyncClient] with a DSL builder.
 *
 * The created client is registered with [ShutdownQueue] so it is closed automatically on JVM shutdown.
 *
 * ```kotlin
 * val client = sqsAsyncClient { region(Region.AP_NORTHEAST_2) }
 * // client != null
 * ```
 */
inline fun sqsAsyncClient(
    builder: SqsAsyncClientBuilder.() -> Unit,
): SqsAsyncClient =
    SqsAsyncClient
        .builder()
        .apply(builder)
        .build()
        .apply {
            ShutdownQueue.register(this)
        }

/**
 * Creates a [SqsAsyncClient] with convenience parameters.
 *
 * @param endpoint Endpoint URI, specified when overriding for LocalStack or similar.
 * @param region AWS region.
 * @param credentialsProvider Credentials provider.
 * @param httpClient Asynchronous HTTP client.
 *
 * ```kotlin
 * val client = sqsAsyncClientOf(endpoint = URI("http://localhost:4566"), region = Region.AP_NORTHEAST_2)
 * // client != null
 * ```
 */
inline fun sqsAsyncClientOf(
    endpoint: URI? = null,
    region: Region? = null,
    credentialsProvider: AwsCredentialsProvider? = null,
    httpClient: SdkAsyncHttpClient = SdkAsyncHttpClientProvider.Netty.httpClient,
    builder: SqsAsyncClientBuilder.() -> Unit = {},
): SqsAsyncClient =
    sqsAsyncClient {
        endpoint?.let { endpointOverride(it) }
        region?.let { region(it) }
        credentialsProvider?.let { credentialsProvider(it) }
        httpClient(httpClient)

        builder()
    }

/**
 * Creates an SQS queue asynchronously with [queueName] and returns the queue URL.
 *
 * ```kotlin
 * val queueUrl = sqsAsyncClient.createQueueAsync("my-queue").join()
 * // queueUrl.contains("my-queue") == true
 * ```
 */
fun SqsAsyncClient.createQueueAsync(queueName: String): CompletableFuture<String> {
    queueName.requireNotBlank("queueName")
    return createQueue { it.queueName(queueName) }
        .thenApply { it.queueUrl() }
}

/**
 * Lists SQS queues asynchronously.
 *
 * ```kotlin
 * val response = sqsAsyncClient.listQueuesAsync(prefix = "my-").join()
 * // response.queueUrls().isNotEmpty() == true
 * ```
 */
fun SqsAsyncClient.listQueuesAsync(
    prefix: String? = null,
    nextToken: String? = null,
    maxResults: Int? = null,
): CompletableFuture<ListQueuesResponse> =
    listQueues {
        prefix?.run { it.queueNamePrefix(this) }
        nextToken?.run { it.nextToken(this) }
        maxResults?.run { it.maxResults(this) }
    }

/**
 * Gets the SQS queue URL for [queueName] asynchronously.
 *
 * ```kotlin
 * val response = sqsAsyncClient.getQueueUrlAsync("my-queue").join()
 * // response.queueUrl().contains("my-queue") == true
 * ```
 */
fun SqsAsyncClient.getQueueUrlAsync(
    queueName: String,
    queueOwnerAWSAccountId: String? = null,
): CompletableFuture<GetQueueUrlResponse> {
    queueName.requireNotBlank("queueName")
    return getQueueUrl {
        it.queueName(queueName)
        queueOwnerAWSAccountId?.run { it.queueOwnerAWSAccountId(this) }
    }
}

/**
 * Sends [messageBody] to the [queueUrl] queue asynchronously.
 *
 * ```kotlin
 * val response = sqsAsyncClient.sendAsync("https://sqs.ap-northeast-2.amazonaws.com/123/my-queue", "hello").join()
 * // response.messageId().isNotBlank() == true
 * ```
 */
fun SqsAsyncClient.sendAsync(
    queueUrl: String,
    messageBody: String,
    delaySeconds: Int? = null,
): CompletableFuture<SendMessageResponse> {
    queueUrl.requireNotBlank("queueUrl")
    return sendMessage(sendMessageRequestOf(queueUrl, messageBody, delaySeconds))
}

/**
 * Sends messages asynchronously as a batch.
 *
 * If [entries] is not in the 1..10 range, throws [IllegalArgumentException] before making a network call.
 *
 * ```kotlin
 * val entry = sendMessageBatchRequestEntryOf(id = "1", messageGroupId = "default", messageBody = "hello")
 * val response = sqsAsyncClient.sendBatchAsync(queueUrl, entry).join()
 * // response.successful().size == 1
 * ```
 */
fun SqsAsyncClient.sendBatchAsync(
    queueUrl: String,
    vararg entries: SendMessageBatchRequestEntry,
): CompletableFuture<SendMessageBatchResponse> {
    queueUrl.requireNotBlank("queueUrl")
    validateSqsBatchSize(entries.size, "entries")
    return sendMessageBatch {
        it.queueUrl(queueUrl)
        it.entries(*entries)
    }
}

/**
 * Sends messages asynchronously as a batch.
 *
 * If [entries] is not in the 1..10 range, throws [IllegalArgumentException] before making a network call.
 *
 * ```kotlin
 * val entries = listOf(sendMessageBatchRequestEntryOf(id = "1", messageGroupId = "default", messageBody = "hello"))
 * val response = sqsAsyncClient.sendBatchAsync(queueUrl, entries).join()
 * // response.failed().isEmpty() == true
 * ```
 */
fun SqsAsyncClient.sendBatchAsync(
    queueUrl: String,
    entries: Collection<SendMessageBatchRequestEntry>,
): CompletableFuture<SendMessageBatchResponse> {
    queueUrl.requireNotBlank("queueUrl")
    validateSqsBatchSize(entries.size, "entries")
    return sendMessageBatch {
        it.queueUrl(queueUrl)
        it.entries(entries)
    }
}

/**
 * Receives messages asynchronously from a queue.
 *
 * When [maxResults] is set, validates the SQS 1..10 constraint up front and fails before the network call.
 *
 * ```kotlin
 * val response = sqsAsyncClient.receiveMessagesAsync(queueUrl, maxResults = 10) {
 *     waitTimeSeconds(5)
 * }.join()
 * // response.messages().size <= 10
 * ```
 */
fun SqsAsyncClient.receiveMessagesAsync(
    queueUrl: String,
    maxResults: Int? = null,
    builder: ReceiveMessageRequest.Builder.() -> Unit = {},
): CompletableFuture<ReceiveMessageResponse> {
    queueUrl.requireNotBlank("queueUrl")
    maxResults?.let { validateReceiveMessageCount(it) }
    return receiveMessage {
        it.queueUrl(queueUrl)
        maxResults?.run { it.maxNumberOfMessages(this) }
        it.builder()
    }
}

/**
 * Changes queue message visibility timeout asynchronously.
 *
 * When [visibilityTimeout] is set, validates the SQS 0..43200 constraint up front and fails before the network call.
 *
 * ```kotlin
 * val response = sqsAsyncClient.changeMessageVisibilityAsync(queueUrl, receiptHandle = handle, visibilityTimeout = 30).join()
 * // response.sdkHttpResponse().isSuccessful == true
 * ```
 */
fun SqsAsyncClient.changeMessageVisibilityAsync(
    queueUrl: String,
    receiptHandle: String? = null,
    visibilityTimeout: Int? = null,
): CompletableFuture<ChangeMessageVisibilityResponse> {
    queueUrl.requireNotBlank("queueUrl")
    visibilityTimeout?.validateSqsVisibilityTimeout("visibilityTimeout")
    return changeMessageVisibility {
        it.queueUrl(queueUrl)
        receiptHandle?.run { it.receiptHandle(this) }
        visibilityTimeout?.run { it.visibilityTimeout(this) }
    }
}

/**
 * Changes message visibility timeouts asynchronously as a batch.
 *
 * If [entries] is not in the 1..10 range, throws [IllegalArgumentException] before making a network call.
 *
 * ```kotlin
 * val entry = ChangeMessageVisibilityBatchRequestEntry.builder()
 *     .id("1")
 *     .receiptHandle(receiptHandle)
 *     .visibilityTimeout(30)
 *     .build()
 * val response = sqsAsyncClient.changeMessageVisibilityBatchAsync(queueUrl, entry).join()
 * // response.successful().size == 1
 * ```
 */
fun SqsAsyncClient.changeMessageVisibilityBatchAsync(
    queueUrl: String,
    vararg entries: ChangeMessageVisibilityBatchRequestEntry,
): CompletableFuture<ChangeMessageVisibilityBatchResponse> {
    queueUrl.requireNotBlank("queueUrl")
    validateSqsBatchSize(entries.size, "entries")
    return changeMessageVisibilityBatch {
        it.queueUrl(queueUrl)
        it.entries(*entries)
    }
}

/**
 * Changes message visibility timeouts asynchronously as a batch.
 *
 * If [entries] is not in the 1..10 range, throws [IllegalArgumentException] before making a network call.
 */
fun SqsAsyncClient.changeMessageVisibilityBatchAsync(
    queueUrl: String,
    entries: Collection<ChangeMessageVisibilityBatchRequestEntry>,
): CompletableFuture<ChangeMessageVisibilityBatchResponse> {
    queueUrl.requireNotBlank("queueUrl")
    validateSqsBatchSize(entries.size, "entries")
    return changeMessageVisibilityBatch {
        it.queueUrl(queueUrl)
        it.entries(entries)
    }
}

/**
 * Deletes a message asynchronously from a queue.
 *
 * ```kotlin
 * val response = sqsAsyncClient.deleteMessageAsync(queueUrl, receiptHandle = handle).join()
 * // response.sdkHttpResponse().isSuccessful == true
 * ```
 */
fun SqsAsyncClient.deleteMessageAsync(
    queueUrl: String,
    receiptHandle: String? = null,
): CompletableFuture<DeleteMessageResponse> {
    queueUrl.requireNotBlank("queueUrl")
    return deleteMessage {
        it.queueUrl(queueUrl)
        receiptHandle?.run { it.receiptHandle(this) }
    }
}

/**
 * Deletes messages asynchronously as a batch.
 *
 * If [entries] is not in the 1..10 range, throws [IllegalArgumentException] before making a network call.
 *
 * ```kotlin
 * val entry = DeleteMessageBatchRequestEntry.builder()
 *     .id("1")
 *     .receiptHandle(receiptHandle)
 *     .build()
 * val response = sqsAsyncClient.deleteMessageBatchAsync(queueUrl, entry).join()
 * // response.successful().size == 1
 * ```
 */
fun SqsAsyncClient.deleteMessageBatchAsync(
    queueUrl: String,
    vararg entries: DeleteMessageBatchRequestEntry,
): CompletableFuture<DeleteMessageBatchResponse> {
    queueUrl.requireNotBlank("queueUrl")
    validateSqsBatchSize(entries.size, "entries")
    return deleteMessageBatch {
        it.queueUrl(queueUrl)
        it.entries(*entries)
    }
}

/**
 * Deletes messages asynchronously as a batch.
 *
 * If [entries] is not in the 1..10 range, throws [IllegalArgumentException] before making a network call.
 */
fun SqsAsyncClient.deleteMessageBatchAsync(
    queueUrl: String,
    entries: Collection<DeleteMessageBatchRequestEntry>,
): CompletableFuture<DeleteMessageBatchResponse> {
    queueUrl.requireNotBlank("queueUrl")
    validateSqsBatchSize(entries.size, "entries")
    return deleteMessageBatch {
        it.queueUrl(queueUrl)
        it.entries(entries)
    }
}

/**
 * Deletes an SQS queue asynchronously.
 *
 * ```kotlin
 * val response = sqsAsyncClient.deleteQueueAsync("https://sqs.ap-northeast-2.amazonaws.com/123/my-queue").join()
 * // response.sdkHttpResponse().isSuccessful == true
 * ```
 */
fun SqsAsyncClient.deleteQueueAsync(queueUrl: String): CompletableFuture<DeleteQueueResponse> {
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
