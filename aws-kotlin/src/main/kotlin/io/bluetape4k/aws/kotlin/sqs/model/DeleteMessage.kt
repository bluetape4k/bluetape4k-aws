package io.bluetape4k.aws.kotlin.sqs.model

import aws.sdk.kotlin.services.sqs.model.DeleteMessageBatchRequest
import aws.sdk.kotlin.services.sqs.model.DeleteMessageBatchRequestEntry
import aws.sdk.kotlin.services.sqs.model.DeleteMessageRequest
import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.support.requireNotEmpty

/**
 * Creates a DeleteMessageRequest from the supplied queueUrl and receiptHandle.
 *
 * ```kotlin
 * val request = deleteMessageRequestOf(
 *     queueUrl = "https://sqs.ap-northeast-2.amazonaws.com/123456789012/MyQueue",
 *     receiptHandle = "receiptHandle"
 * )
 * sqsClient.deleteMessage(request)
 * ```
 *
 * @param queueUrl URL of the Amazon SQS queue containing the message.
 * @param receiptHandle Receipt handle associated with the message to delete.
 * @param builder Lambda for configuring DeleteMessageRequest.Builder.
 */
inline fun deleteMessageRequestOf(
    queueUrl: String,
    receiptHandle: String? = null,
    crossinline builder: DeleteMessageRequest.Builder.() -> Unit = {},
): DeleteMessageRequest {
    queueUrl.requireNotBlank("queueUrl")

    return DeleteMessageRequest {
        this.queueUrl = queueUrl
        this.receiptHandle = receiptHandle

        builder()
    }
}

/**
 * Creates a DeleteMessageBatchRequestEntry from the supplied id and receiptHandle.
 *
 * ```kotlin
 * val entry = deleteMessageBatchRequestEntryOf(
 *     id = "msg-001",
 *     receiptHandle = "receiptHandle1"
 * )
 * ```
 *
 * @param id Identifier of the message to delete.
 * @param receiptHandle Receipt handle associated with the message to delete.
 * @return A DeleteMessageBatchRequestEntry instance.
 */
inline fun deleteMessageBatchRequestEntryOf(
    id: String,
    receiptHandle: String? = null,
    crossinline builder: DeleteMessageBatchRequestEntry.Builder.() -> Unit = {},
): DeleteMessageBatchRequestEntry {
    id.requireNotBlank("id")

    return DeleteMessageBatchRequestEntry {
        this.id = id
        this.receiptHandle = receiptHandle

        builder()
    }
}

/**
 * Creates a DeleteMessageBatchRequest from the supplied queueUrl and entries.
 *
 * ```kotlin
 * val request = deleteMessageBatchRequestOf(
 *     queueUrl = "https://sqs.ap-northeast-2.amazonaws.com/123456789012/MyQueue",
 *     entries = listOf(deleteMessageBatchRequestEntryOf("id1", "receiptHandle1"))
 * )
 * sqsClient.deleteMessageBatch(request)
 * ```
 *
 * @param queueUrl URL of the Amazon SQS queue containing the messages.
 * @param entries Collection of DeleteMessageBatchRequestEntry instances.
 * @return A DeleteMessageBatchRequest instance.
 */
inline fun deleteMessageBatchRequestOf(
    queueUrl: String,
    entries: Collection<DeleteMessageBatchRequestEntry>,
    crossinline builder: DeleteMessageBatchRequest.Builder.() -> Unit = {},
): DeleteMessageBatchRequest {
    queueUrl.requireNotBlank("queueUrl")
    entries.requireNotEmpty("entries")

    return DeleteMessageBatchRequest {
        this.queueUrl = queueUrl
        this.entries = entries.toList()

        builder()
    }
}

/**
 * Creates a DeleteMessageBatchRequest from the supplied queueUrl and entries.
 *
 * ```kotlin
 * val request = deleteMessageBatchRequestOf(
 *     queueUrl = "https://sqs.ap-northeast-2.amazonaws.com/123456789012/MyQueue",
 *     deleteMessageBatchRequestEntryOf("id1", "receiptHandle1"),
 *     deleteMessageBatchRequestEntryOf("id2", "receiptHandle2")
 * )
 * sqsClient.deleteMessageBatch(request)
 * ```
 *
 * @param queueUrl URL of the Amazon SQS queue containing the messages.
 * @param entries Collection of DeleteMessageBatchRequestEntry instances.
 * @return A DeleteMessageBatchRequest instance.
 */
inline fun deleteMessageBatchRequestOf(
    queueUrl: String,
    vararg entries: DeleteMessageBatchRequestEntry,
    crossinline builder: DeleteMessageBatchRequest.Builder.() -> Unit = {},
): DeleteMessageBatchRequest {
    queueUrl.requireNotBlank("queueUrl")
    entries.requireNotEmpty("entries")

    return DeleteMessageBatchRequest {
        this.queueUrl = queueUrl
        this.entries = entries.toList()

        builder()
    }
}
