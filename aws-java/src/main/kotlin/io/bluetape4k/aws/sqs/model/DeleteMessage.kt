package io.bluetape4k.aws.sqs.model

import io.bluetape4k.support.requireNotBlank
import software.amazon.awssdk.services.sqs.model.DeleteMessageBatchRequest
import software.amazon.awssdk.services.sqs.model.DeleteMessageBatchRequestEntry
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest

/**
 * Builds a [DeleteMessageRequest] with the provided initializer.
 *
 * @param builder Lambda that initializes [DeleteMessageRequest.Builder].
 * @return [DeleteMessageRequest] instance.
 *
 * ```kotlin
 * val request = deleteMessageRequest {
 *     queueUrl("https://sqs.ap-northeast-2.amazonaws.com/123/my-queue")
 *     receiptHandle("handle-xyz")
 * }
 * // request.receiptHandle() == "handle-xyz"
 * ```
 */
inline fun deleteMessageRequest(
    builder: DeleteMessageRequest.Builder.() -> Unit,
): DeleteMessageRequest {
    return DeleteMessageRequest.builder().apply(builder).build()
}

/**
 * Creates a [DeleteMessageRequest] from the provided queue URL and receipt handle.
 *
 * @param queueUrl URL of the Amazon SQS queue that contains the message to delete.
 * @param receiptHandle Receipt handle associated with the message to delete.
 * @return [DeleteMessageRequest] instance.
 *
 * ```kotlin
 * val request = deleteMessageRequestOf(
 *     queueUrl = "https://sqs.ap-northeast-2.amazonaws.com/123/my-queue",
 *     receiptHandle = "handle-xyz"
 * )
 * // request.queueUrl().contains("my-queue") == true
 * ```
 */
fun deleteMessageRequestOf(
    queueUrl: String,
    receiptHandle: String,
): DeleteMessageRequest {
    queueUrl.requireNotBlank("queueUrl")
    receiptHandle.requireNotBlank("receiptHandle")

    return deleteMessageRequest {
        queueUrl(queueUrl)
        receiptHandle(receiptHandle)
    }
}

/**
 * Builds a [DeleteMessageBatchRequest] with the provided initializer.
 *
 * @param builder Lambda that initializes [DeleteMessageBatchRequest.Builder].
 * @return [DeleteMessageBatchRequest] instance.
 *
 * ```kotlin
 * val request = deleteMessageBatchRequest {
 *     queueUrl("https://sqs.ap-northeast-2.amazonaws.com/123/my-queue")
 * }
 * // request.queueUrl().contains("my-queue") == true
 * ```
 */
inline fun deleteMessageBatchRequest(
    builder: DeleteMessageBatchRequest.Builder.() -> Unit,
): DeleteMessageBatchRequest {
    return DeleteMessageBatchRequest.builder().apply(builder).build()
}

/**
 * Creates a [DeleteMessageBatchRequest] from the provided queue URL and entries.
 *
 * @param queueUrl URL of the Amazon SQS queue that contains the messages to delete.
 * @param entries Collection of [DeleteMessageBatchRequestEntry] instances.
 * @return [DeleteMessageBatchRequest] instance.
 *
 * ```kotlin
 * val entry = deleteMessageBatchRequestEntryOf("msg-1", "handle-xyz")
 * val request = deleteMessageBatchRequestOf("https://sqs.ap-northeast-2.amazonaws.com/123/my-queue", listOf(entry))
 * // request.entries().size == 1
 * ```
 */
fun deleteMessageBatchRequestOf(
    queueUrl: String,
    entries: Collection<DeleteMessageBatchRequestEntry>,
): DeleteMessageBatchRequest {
    queueUrl.requireNotBlank("queueUrl")

    return deleteMessageBatchRequest {
        queueUrl(queueUrl)
        entries(entries)
    }
}

/**
 * Builds a [DeleteMessageBatchRequestEntry] with the provided initializer.
 *
 * @param builder Lambda that initializes [DeleteMessageBatchRequestEntry.Builder].
 * @return [DeleteMessageBatchRequestEntry] instance.
 *
 * ```kotlin
 * val entry = deleteMessageBatchRequestEntry {
 *     id("msg-1")
 *     receiptHandle("handle-xyz")
 * }
 * // entry.id() == "msg-1"
 * ```
 */
inline fun deleteMessageBatchRequestEntry(
    builder: DeleteMessageBatchRequestEntry.Builder.() -> Unit,
): DeleteMessageBatchRequestEntry {
    return DeleteMessageBatchRequestEntry.builder().apply(builder).build()
}

/**
 * Creates a [DeleteMessageBatchRequestEntry] from the provided ID and receipt handle.
 *
 * @param id Identifier of the message to delete.
 * @param receiptHandle Receipt handle associated with the message to delete.
 * @return [DeleteMessageBatchRequestEntry] instance.
 *
 * ```kotlin
 * val entry = deleteMessageBatchRequestEntryOf("msg-1", "handle-xyz")
 * // entry.receiptHandle() == "handle-xyz"
 * ```
 */
fun deleteMessageBatchRequestEntryOf(
    id: String,
    receiptHandle: String,
): DeleteMessageBatchRequestEntry {
    id.requireNotBlank("id")
    receiptHandle.requireNotBlank("receiptHandle")

    return deleteMessageBatchRequestEntry {
        id(id)
        receiptHandle(receiptHandle)
    }
}
