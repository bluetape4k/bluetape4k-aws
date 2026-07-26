package io.bluetape4k.aws.kotlin.sqs.model

import aws.sdk.kotlin.services.sqs.model.SendMessageBatchRequest
import aws.sdk.kotlin.services.sqs.model.SendMessageBatchRequestEntry
import aws.sdk.kotlin.services.sqs.model.SendMessageRequest
import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.support.requireNotEmpty

/**
 * Creates a SendMessageRequest from the supplied queueUrl and messageBody.
 *
 * ```kotlin
 * import aws.sdk.kotlin.services.sqs.SqsClient
 * import io.bluetape4k.aws.kotlin.sqs.model.messageAttributeValueOf
 * import io.bluetape4k.aws.kotlin.sqs.model.sendMessageRequestOf
 *
 * suspend fun sendOrder(sqsClient: SqsClient, queueUrl: String) {
 *     val request = sendMessageRequestOf(
 *         queueUrl = queueUrl,
 *         messageBody = "Hello, World!",
 *         delaySeconds = 0
 *     ) {
 *         messageAttributes = mapOf("source" to messageAttributeValueOf("web"))
 *     }
 *     sqsClient.sendMessage(request)
 * }
 * ```
 *
 * @param queueUrl URL of the Amazon SQS queue to which the message is sent.
 * @param messageBody Body of the message to send.
 * @param delaySeconds Seconds to wait before sending the message. Defaults to null.
 * @param builder Lambda for initializing SendMessageRequest.Builder. Defaults to an empty lambda.
 * @return A [SendMessageRequest] instance.
 */
inline fun sendMessageRequestOf(
    queueUrl: String,
    messageBody: String,
    delaySeconds: Int? = null,
    crossinline builder: SendMessageRequest.Builder.() -> Unit = {},
): SendMessageRequest {
    queueUrl.requireNotBlank("queueUrl")
    messageBody.requireNotBlank("messageBody")

    return SendMessageRequest {
        this.queueUrl = queueUrl
        this.messageBody = messageBody
        this.delaySeconds = delaySeconds

        builder()
    }
}

/**
 * Creates a SendMessageBatchRequestEntry from the supplied id, messageBody, and messageGroupId.
 *
 * ```kotlin
 * val entry = sendMessageBatchRequestEntryOf(
 *     id = "msg-001",
 *     messageBody = "Hello, World!",
 *     messageGroupId = "orders"   // Used for FIFO queues.
 * )
 * ```
 *
 * @param id Message identifier.
 * @param messageBody Body of the message to send.
 * @param messageGroupId Message group identifier.
 * @param delaySeconds Seconds to wait before sending the message. Defaults to null.
 * @param builder Lambda for initializing SendMessageBatchRequestEntry.Builder. Defaults to an empty lambda.
 *
 * @return A SendMessageBatchRequestEntry instance.
 */
inline fun sendMessageBatchRequestEntryOf(
    id: String,
    messageBody: String,
    messageGroupId: String? = null,
    delaySeconds: Int? = null,
    crossinline builder: SendMessageBatchRequestEntry.Builder.() -> Unit = {},
): SendMessageBatchRequestEntry {
    id.requireNotBlank("id")
    messageBody.requireNotBlank("messageBody")

    return SendMessageBatchRequestEntry {
        this.id = id
        this.messageBody = messageBody
        this.messageGroupId = messageGroupId
        this.delaySeconds = delaySeconds

        builder()
    }
}

/**
 * Creates a SendMessageBatchRequest from the supplied queueUrl and entries.
 *
 * ```kotlin
 * import aws.sdk.kotlin.services.sqs.SqsClient
 * import io.bluetape4k.aws.kotlin.sqs.model.sendMessageBatchRequestEntryOf
 * import io.bluetape4k.aws.kotlin.sqs.model.sendMessageBatchRequestOf
 *
 * suspend fun sendOrders(sqsClient: SqsClient, queueUrl: String) {
 *     val request = sendMessageBatchRequestOf(
 *         queueUrl = queueUrl,
 *         entries = listOf(
 *             sendMessageBatchRequestEntryOf("id1", "Hello!", messageGroupId = "orders")
 *         )
 *     )
 *     sqsClient.sendMessageBatch(request)
 * }
 * ```
 *
 * @param queueUrl URL of the Amazon SQS queue to which the messages are sent.
 * @param entries Collection of SendMessageBatchRequestEntry instances.
 * @param builder Lambda for initializing SendMessageBatchRequest.Builder. Defaults to an empty lambda.
 * @return A SendMessageBatchRequest instance.
 */
@JvmName("sendMessageBatchRequestOfCollection")
inline fun sendMessageBatchRequestOf(
    queueUrl: String,
    entries: Collection<SendMessageBatchRequestEntry>,
    crossinline builder: SendMessageBatchRequest.Builder.() -> Unit = {},
): SendMessageBatchRequest {
    queueUrl.requireNotBlank("queueUrl")
    entries.requireNotEmpty("entries")

    return SendMessageBatchRequest {
        this.queueUrl = queueUrl
        this.entries = entries.toList()

        builder()
    }
}

/**
 * Creates a SendMessageBatchRequest from the supplied queueUrl and entries.
 *
 * ```kotlin
 * import aws.sdk.kotlin.services.sqs.SqsClient
 * import io.bluetape4k.aws.kotlin.sqs.model.sendMessageBatchRequestEntryOf
 * import io.bluetape4k.aws.kotlin.sqs.model.sendMessageBatchRequestOf
 *
 * suspend fun sendOrders(sqsClient: SqsClient, queueUrl: String) {
 *     val request = sendMessageBatchRequestOf(
 *         queueUrl = queueUrl,
 *         sendMessageBatchRequestEntryOf("id1", "Hello!", messageGroupId = "orders"),
 *         sendMessageBatchRequestEntryOf("id2", "World!", messageGroupId = "orders")
 *     )
 *     sqsClient.sendMessageBatch(request)
 * }
 * ```
 *
 * @param queueUrl URL of the Amazon SQS queue to which the messages are sent.
 * @param entries Array of SendMessageBatchRequestEntry instances.
 * @param builder Lambda for initializing SendMessageBatchRequest.Builder. Defaults to an empty lambda.
 * @return A SendMessageBatchRequest instance.
 */
@JvmName("sendMessageBatchRequestOfArray")
inline fun sendMessageBatchRequestOf(
    queueUrl: String,
    vararg entries: SendMessageBatchRequestEntry,
    crossinline builder: SendMessageBatchRequest.Builder.() -> Unit = {},
): SendMessageBatchRequest {
    queueUrl.requireNotBlank("queueUrl")
    entries.requireNotEmpty("entries")

    return SendMessageBatchRequest {
        this.queueUrl = queueUrl
        this.entries = entries.toList()

        builder()
    }
}
