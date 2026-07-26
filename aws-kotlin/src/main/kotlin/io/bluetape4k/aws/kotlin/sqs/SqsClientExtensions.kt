package io.bluetape4k.aws.kotlin.sqs

import aws.sdk.kotlin.services.sqs.SqsClient
import aws.sdk.kotlin.services.sqs.changeMessageVisibility
import aws.sdk.kotlin.services.sqs.changeMessageVisibilityBatch
import aws.sdk.kotlin.services.sqs.createQueue
import aws.sdk.kotlin.services.sqs.deleteMessage
import aws.sdk.kotlin.services.sqs.deleteMessageBatch
import aws.sdk.kotlin.services.sqs.deleteQueue
import aws.sdk.kotlin.services.sqs.getQueueUrl
import aws.sdk.kotlin.services.sqs.listQueues
import aws.sdk.kotlin.services.sqs.model.ChangeMessageVisibilityBatchRequestEntry
import aws.sdk.kotlin.services.sqs.model.ChangeMessageVisibilityBatchResponse
import aws.sdk.kotlin.services.sqs.model.ChangeMessageVisibilityResponse
import aws.sdk.kotlin.services.sqs.model.CreateQueueRequest
import aws.sdk.kotlin.services.sqs.model.CreateQueueResponse
import aws.sdk.kotlin.services.sqs.model.DeleteMessageBatchRequestEntry
import aws.sdk.kotlin.services.sqs.model.DeleteMessageBatchResponse
import aws.sdk.kotlin.services.sqs.model.DeleteMessageResponse
import aws.sdk.kotlin.services.sqs.model.DeleteQueueRequest
import aws.sdk.kotlin.services.sqs.model.DeleteQueueResponse
import aws.sdk.kotlin.services.sqs.model.GetQueueUrlRequest
import aws.sdk.kotlin.services.sqs.model.ListQueuesRequest
import aws.sdk.kotlin.services.sqs.model.ListQueuesResponse
import aws.sdk.kotlin.services.sqs.model.QueueDoesNotExist
import aws.sdk.kotlin.services.sqs.model.ReceiveMessageResponse
import aws.sdk.kotlin.services.sqs.model.ResourceNotFoundException
import aws.sdk.kotlin.services.sqs.model.SendMessageBatchRequestEntry
import aws.sdk.kotlin.services.sqs.model.SendMessageBatchResponse
import aws.sdk.kotlin.services.sqs.model.SendMessageRequest
import aws.sdk.kotlin.services.sqs.model.SendMessageResponse
import aws.sdk.kotlin.services.sqs.receiveMessage
import aws.sdk.kotlin.services.sqs.sendMessage
import aws.sdk.kotlin.services.sqs.sendMessageBatch
import aws.smithy.kotlin.runtime.ServiceException
import aws.smithy.kotlin.runtime.http.response.statusCode
import io.bluetape4k.logging.KotlinLogging
import kotlinx.coroutines.CancellationException
import io.bluetape4k.logging.info
import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.support.requireNotEmpty

@PublishedApi
internal val log = KotlinLogging.logger {}

@PublishedApi
internal const val MIN_RECEIVE_MESSAGES = 1

@PublishedApi
internal const val MAX_RECEIVE_MESSAGES = 10

/**
 * Creates the queue identified by [queueName].
 *
 * ```kotlin
 * val response = sqsClient.createQueue("my-queue")
 * ```
 *
 * @param queueName Name of the Amazon SQS queue.
 * @return A CreateQueueResponse instance.
 */
suspend inline fun SqsClient.createQueue(
    queueName: String,
    crossinline builder: CreateQueueRequest.Builder.() -> Unit = {},
): CreateQueueResponse {
    queueName.requireNotBlank("queueName")

    return createQueue {
        this.queueName = queueName
        builder()
    }.apply {
        log.info { "Create Queue. response=$this" }
    }
}

/**
 * Creates the queue identified by [queueName] when it does not already exist.
 *
 * ```kotlin
 * val queueUrl = sqsClient.ensureQueue("my-queue")
 * ```
 *
 * @param queueName Name of the Amazon SQS queue.
 * @return The queue URL.
 * @see [existsQueue]
 */
suspend inline fun SqsClient.ensureQueue(
    queueName: String,
    crossinline builder: CreateQueueRequest.Builder.() -> Unit = {},
): String? {
    queueName.requireNotBlank("queueName")

    if (!existsQueue(queueName)) {
        createQueue(queueName, builder)
    }
    return getQueueUrl(queueName)
}

/**
 * Checks whether the queue identified by [queueName] exists.
 *
 * Only missing-queue responses (`QueueDoesNotExist`, `ResourceNotFoundException`, or HTTP `404`) are normalized
 * to `false`; other failures, such as authentication or network errors, are propagated unchanged.
 *
 * ```kotlin
 * val exists = sqsClient.existsQueue("my-queue")
 * ```
 *
 * @param queueName Name of the Amazon SQS queue.
 * @return `true` when the queue exists; otherwise `false`.
 */
suspend inline fun SqsClient.existsQueue(queueName: String): Boolean =
    try {
        getQueueUrl(queueName)?.isNotBlank() ?: false
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        if (e.isMissingQueueError()) false else throw e
    }

/**
 * Lists queues whose names start with [queueNamePrefix].
 *
 * ```kotlin
 * val response = sqsClient.listQueues(queueNamePrefix = "my-queue")
 * val queueUrls = response.queueUrls
 * ```
 *
 * @param queueNamePrefix Amazon SQS queue name prefix.
 * @param nextToken Token for the next page. Defaults to null.
 * @param maxResults Maximum number of results to return. Defaults to null.
 * @return A ListQueuesResponse instance.
 */
suspend inline fun SqsClient.listQueues(
    queueNamePrefix: String,
    nextToken: String? = null,
    maxResults: Int? = null,
    crossinline builder: ListQueuesRequest.Builder.() -> Unit = {},
): ListQueuesResponse {
    queueNamePrefix.requireNotBlank("queueNamePrefix")

    return listQueues {
        this.queueNamePrefix = queueNamePrefix
        nextToken?.let { this.nextToken = it }
        maxResults?.let { this.maxResults = it }

        builder()
    }
}

/**
 * Returns the URL of the queue identified by [queueName].
 *
 * ```kotlin
 * val response = sqsClient.getQueueUrl("my-queue")
 * val queueUrl = response.queueUrl
 * ```
 *
 * @param queueName Name of the Amazon SQS queue.
 * @return The queue URL. Throws when the queue does not exist.
 */
suspend inline fun SqsClient.getQueueUrl(
    queueName: String,
    crossinline builder: GetQueueUrlRequest.Builder.() -> Unit = {},
): String? {
    queueName.requireNotBlank("queueName")

    return getQueueUrl {
        this.queueName = queueName
        builder()
    }.queueUrl
}

/**
 * Deletes the queue identified by [queueUrl].
 *
 * ```kotlin
 * val response = sqsClient.deleteQueue("https://sqs.ap-northeast-2.amazonaws.com/123456789012/my-queue")
 * ```
 *
 * @param queueUrl URL of the Amazon SQS queue.
 * @return A DeleteQueueResponse instance.
 */
suspend inline fun SqsClient.deleteQueue(
    queueUrl: String,
    crossinline builder: DeleteQueueRequest.Builder.() -> Unit = {},
): DeleteQueueResponse {
    queueUrl.requireNotBlank("queueUrl")

    return deleteQueue {
        this.queueUrl = queueUrl
        builder()
    }
}

/**
 * Sends a message to Amazon SQS.
 *
 * ```kotlin
 * val response = sqsClient.sendMessage(
 *      queueUrl = "https://sqs.ap-northeast-2.amazonaws.com/123456789012/MyQueue",
 *      messageBody = "Hello, World!",
 *      delaySeconds = 10,
 * )
 * ```
 *
 * @param queueUrl URL of the Amazon SQS queue to which the message is sent.
 * @param messageBody Body of the message. Throws [IllegalArgumentException] when blank.
 * @param delaySeconds Seconds to wait before sending the message. Defaults to null.
 * @param builder Lambda for initializing SendMessageRequest.Builder. Defaults to an empty lambda.
 * @return A SendMessageResponse instance.
 */
suspend inline fun SqsClient.sendMessage(
    queueUrl: String,
    messageBody: String,
    delaySeconds: Int? = null,
    crossinline builder: SendMessageRequest.Builder.() -> Unit = {},
): SendMessageResponse {
    queueUrl.requireNotBlank("queueUrl")
    messageBody.requireNotBlank("messageBody")

    return sendMessage {
        this.queueUrl = queueUrl
        this.messageBody = messageBody
        delaySeconds?.let { this.delaySeconds = it }

        builder()
    }
}

/**
 * Sends messages to Amazon SQS in a batch.
 *
 * ```kotlin
 * val entry1 = SendMessageBatchRequestEntry { id="id1"; messageBody="Hello, World!" }
 * val entry2 = SendMessageBatchRequestEntry { id="id2"; messageBody="Hello, World!" }
 *
 * val queueUrl = "https://sqs.ap-northeast-2.amazonaws.com/123456789012/MyQueue"
 *
 * val response = sqsClient.sendMessageBatch(queueUrl, entry1, entry2)
 * ```
 *
 * @param queueUrl URL of the Amazon SQS queue to which the messages are sent.
 * @param entries Messages to send.
 * @return A SendMessageBatchResponse instance.
 */
suspend inline fun SqsClient.sendMessageBatch(
    queueUrl: String,
    vararg entries: SendMessageBatchRequestEntry,
): SendMessageBatchResponse {
    queueUrl.requireNotBlank("queueUrl")
    entries.requireNotEmpty("entries")

    return sendMessageBatch {
        this.queueUrl = queueUrl
        this.entries = entries.toList()
    }
}

/**
 * Sends messages to Amazon SQS in a batch.
 *
 * ```kotlin
 * val entry1 = SendMessageBatchRequestEntry { id="id1"; messageBody="Hello, World!" }
 * val entry2 = SendMessageBatchRequestEntry { id="id2"; messageBody="Hello, World!" }
 * val entries = listOf(entry1, entry2)
 *
 * val queueUrl = "https://sqs.ap-northeast-2.amazonaws.com/123456789012/MyQueue"
 *
 * val response = sqsClient.sendMessageBatch(queueUrl, entries)
 * ```
 *
 * @param queueUrl URL of the Amazon SQS queue to which the messages are sent.
 * @param entries Messages to send.
 * @return A SendMessageBatchResponse instance.
 */
suspend inline fun SqsClient.sendMessageBatch(
    queueUrl: String,
    entries: Collection<SendMessageBatchRequestEntry>,
): SendMessageBatchResponse {
    queueUrl.requireNotBlank("queueUrl")
    entries.requireNotEmpty("entries")

    return sendMessageBatch {
        this.queueUrl = queueUrl
        this.entries = entries.toList()
    }
}

/**
 * Receives messages from an Amazon SQS queue using the supplied queueUrl.
 *
 * ```kotlin
 * val response = sqsClient.receiveMessage(queueUrl, maxNumberOfMessages = 10)
 * val messages = response.messages
 * ```
 *
 * @param queueUrl URL of the Amazon SQS queue from which to receive messages.
 * @param maxNumberOfMessages Maximum number of messages to receive at once. Defaults to null; when set, must be in 1..10.
 */
suspend inline fun SqsClient.receiveMessage(
    queueUrl: String,
    maxNumberOfMessages: Int? = null,
): ReceiveMessageResponse {
    queueUrl.requireNotBlank("queueUrl")
    maxNumberOfMessages?.let {
        require(it in MIN_RECEIVE_MESSAGES..MAX_RECEIVE_MESSAGES) {
            "maxNumberOfMessages must be in the range $MIN_RECEIVE_MESSAGES..$MAX_RECEIVE_MESSAGES."
        }
    }

    return receiveMessage {
        this.queueUrl = queueUrl
        maxNumberOfMessages?.let { this.maxNumberOfMessages = it }
    }
}

/**
 * Changes the visibility of the message identified by [receiptHandle] in [queueUrl].
 *
 * ```kotlin
 * val response = sqsClient.changeMessageVisibility(
 *     queueUrl = "https://sqs.ap-northeast-2.amazonaws.com/123456789012/MyQueue",
 *     receiptHandle = "receiptHandle",
 *     visibilityTimeout = 10
 * )
 * ```
 *
 * @param queueUrl URL of the Amazon SQS queue containing the message.
 * @param receiptHandle Receipt handle associated with the message.
 * @param visibilityTimeout New message visibility timeout in seconds. Defaults to null.
 *
 * @return A ChangeMessageVisibilityResponse instance.
 */
suspend inline fun SqsClient.changeMessageVisibility(
    queueUrl: String,
    receiptHandle: String? = null,
    visibilityTimeout: Int? = null,
): ChangeMessageVisibilityResponse {
    queueUrl.requireNotBlank("queueUrl")

    return changeMessageVisibility {
        this.queueUrl = queueUrl
        receiptHandle?.let { this.receiptHandle = it }
        visibilityTimeout?.let { this.visibilityTimeout = it }
    }
}

/**
 * Changes message visibility in batches for [entries] in [queueUrl].
 *
 * ```kotlin
 * val entry1 = ChangeMessageVisibilityBatchRequestEntry {
 *     id = "id1"
 *     receiptHandle = "receiptHandle1"
 *     visibilityTimeout = 10
 * }
 * val entry2 = ChangeMessageVisibilityBatchRequestEntry {
 *      id = "id2"
 *      receiptHandle = "receiptHandle2"
 *      visibilityTimeout = 20
 * }
 * val queueUrl = "https://sqs.ap-northeast-2.amazonaws.com/123456789012/MyQueue"
 *
 * val response = sqsClient.changeMessageVisibilityBatch(queueUrl, entry1, entry2)
 * ```
 *
 * @param queueUrl URL of the Amazon SQS queue containing the messages.
 * @param entries Collection of ChangeMessageVisibilityBatchRequestEntry instances.
 * @return A [ChangeMessageVisibilityBatchResponse] instance.
 */
suspend inline fun SqsClient.changeMessageVisibilityBatch(
    queueUrl: String,
    vararg entries: ChangeMessageVisibilityBatchRequestEntry,
): ChangeMessageVisibilityBatchResponse {
    queueUrl.requireNotBlank("queueUrl")
    entries.requireNotEmpty("entries")

    return changeMessageVisibilityBatch {
        this.queueUrl = queueUrl
        this.entries = entries.toList()
    }
}

/**
 * Changes message visibility in batches for [entries] in [queueUrl].
 *
 * ```kotlin
 * val entry1 = ChangeMessageVisibilityBatchRequestEntry {
 *     id = "id1"
 *     receiptHandle = "receiptHandle1"
 *     visibilityTimeout = 10
 * }
 * val entry2 = ChangeMessageVisibilityBatchRequestEntry {
 *      id = "id2"
 *      receiptHandle = "receiptHandle2"
 *      visibilityTimeout = 20
 * }
 * val queueUrl = "https://sqs.ap-northeast-2.amazonaws.com/123456789012/MyQueue"
 *
 * val response = sqsClient.changeMessageVisibilityBatch(queueUrl, listOf(entry1, entry2))
 * ```
 *
 * @param queueUrl URL of the Amazon SQS queue containing the messages.
 * @param entries Collection of ChangeMessageVisibilityBatchRequestEntry instances.
 * @return A [ChangeMessageVisibilityBatchResponse] instance.
 */
suspend inline fun SqsClient.changeMessageVisibilityBatch(
    queueUrl: String,
    entries: Collection<ChangeMessageVisibilityBatchRequestEntry>,
): ChangeMessageVisibilityBatchResponse {
    queueUrl.requireNotBlank("queueUrl")
    entries.requireNotEmpty("entries")

    return changeMessageVisibilityBatch {
        this.queueUrl = queueUrl
        this.entries = entries.toList()
    }
}

/**
 * Deletes a message using the supplied queueUrl and receiptHandle.
 *
 * ```kotlin
 * val response = sqsClient.deleteMessage(
 *      "https://sqs.ap-northeast-2.amazonaws.com/123456789012/MyQueue",
 *      "receiptHandle"
 * )
 * ```
 *
 * @param queueUrl URL of the Amazon SQS queue containing the message.
 * @param receiptHandle Receipt handle associated with the message to delete.
 * @return A [DeleteMessageResponse] instance.
 */
suspend inline fun SqsClient.deleteMessage(
    queueUrl: String,
    receiptHandle: String? = null,
): DeleteMessageResponse {
    queueUrl.requireNotBlank("queueUrl")

    return deleteMessage {
        this.queueUrl = queueUrl
        receiptHandle?.let { this.receiptHandle = it }
    }
}

/**
 * Deletes messages in a batch using the supplied queueUrl and entries.
 *
 * ```kotlin
 * val response = sqsClient.deleteMessageBatch(
 *     "https://sqs.ap-northeast-2.amazonaws.com/123456789012/MyQueue",
 *     deleteMessageBatchRequestEntryOf("id1", "receiptHandle1"),
 *     deleteMessageBatchRequestEntryOf("id2", "receiptHandle2"),
 * )
 * ```
 *
 * @param queueUrl URL of the Amazon SQS queue containing the messages.
 * @param entries Collection of DeleteMessageBatchRequestEntry instances.
 * @return A [DeleteMessageBatchResponse] instance.
 */
suspend inline fun SqsClient.deleteMessageBatch(
    queueUrl: String,
    vararg entries: DeleteMessageBatchRequestEntry,
): DeleteMessageBatchResponse {
    queueUrl.requireNotBlank("queueUrl")
    entries.requireNotEmpty("entries")

    return deleteMessageBatch {
        this.queueUrl = queueUrl
        this.entries = entries.toList()
    }
}

/**
 * Deletes messages in a batch using the supplied queueUrl and entries.
 *
 * ```kotlin
 * val entries = listOf(
 *      deleteMessageBatchRequestEntryOf("id1", "receiptHandle1"),
 *      deleteMessageBatchRequestEntryOf("id2", "receiptHandle2"),
 * )
 * val response = sqsClient.deleteMessageBatch(
 *    "https://sqs.ap-northeast-2.amazonaws.com/123456789012/MyQueue",
 *    entries
 * )
 * ```
 *
 * @param queueUrl URL of the Amazon SQS queue containing the messages.
 * @param entries Collection of DeleteMessageBatchRequestEntry instances.
 * @return A [DeleteMessageBatchResponse] instance.
 */
suspend inline fun SqsClient.deleteMessageBatch(
    queueUrl: String,
    entries: Collection<DeleteMessageBatchRequestEntry>,
): DeleteMessageBatchResponse {
    queueUrl.requireNotBlank("queueUrl")
    entries.requireNotEmpty("entries")

    return deleteMessageBatch {
        this.queueUrl = queueUrl
        this.entries = entries.toList()
    }
}

@PublishedApi
internal fun Throwable.isMissingQueueError(): Boolean =
    when (this) {
        is QueueDoesNotExist, is ResourceNotFoundException -> {
            true
        }
        is ServiceException -> {
            val errorCode = sdkErrorMetadata.errorCode
            val statusCode = sdkErrorMetadata.protocolResponse.statusCode()?.value
            errorCode in
                    setOf(
                        "QueueDoesNotExist",
                        "AWS.SimpleQueueService.NonExistentQueue",
                        "ResourceNotFoundException",
                        "NotFound"
                    ) || statusCode == 404
        }
        else                -> {
            false
        }
    }
