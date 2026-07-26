package io.bluetape4k.aws.sqs.model

import io.bluetape4k.aws.sqs.validateSqsDelaySeconds
import io.bluetape4k.support.requireNotBlank
import software.amazon.awssdk.services.sqs.model.SendMessageBatchRequestEntry
import software.amazon.awssdk.services.sqs.model.SendMessageRequest

/**
 * Creates a [SendMessageRequest].
 *
 * @param builder Lambda that initializes [SendMessageRequest] with [SendMessageRequest.Builder].
 *
 * ```kotlin
 * val request = sendMessageRequest {
 *     queueUrl("https://sqs.ap-northeast-2.amazonaws.com/123/my-queue")
 *     messageBody("hello")
 * }
 * // request.messageBody() == "hello"
 * ```
 */
inline fun sendMessageRequest(
    builder: SendMessageRequest.Builder.() -> Unit,
): SendMessageRequest {
    return SendMessageRequest.builder().apply(builder).build()
}

/**
 * Creates a [SendMessageRequest] from [queueUrl] and [messageBody].
 *
 * When [delaySeconds] is set, validates the SQS 0..900 constraint up front.
 *
 * ```kotlin
 * val request = sendMessageRequestOf(
 *     queueUrl = "https://sqs.ap-northeast-2.amazonaws.com/123/my-queue",
 *     messageBody = "hello",
 *     delaySeconds = 5
 * )
 * // request.delaySeconds() == 5
 * ```
 */
inline fun sendMessageRequestOf(
    queueUrl: String,
    messageBody: String,
    delaySeconds: Int? = null,
    builder: SendMessageRequest.Builder.() -> Unit = {},
): SendMessageRequest {
    queueUrl.requireNotBlank("queueUrl")
    messageBody.requireNotBlank("messageBody")
    delaySeconds?.validateSqsDelaySeconds("delaySeconds")

    return sendMessageRequest {
        queueUrl(queueUrl)
        messageBody(messageBody)
        delaySeconds?.let { delaySeconds(it) }
        builder()
    }
}

/**
 * Creates a [SendMessageBatchRequestEntry].
 *
 * @param builder Lambda that initializes [SendMessageBatchRequestEntry] with [SendMessageBatchRequestEntry.Builder].
 *
 * ```kotlin
 * val entry = sendMessageBatchRequestEntry {
 *     id("msg-1")
 *     messageBody("hello")
 * }
 * // entry.id() == "msg-1"
 * ```
 */
inline fun sendMessageBatchRequestEntry(
    builder: SendMessageBatchRequestEntry.Builder.() -> Unit,
): SendMessageBatchRequestEntry {
    return SendMessageBatchRequestEntry.builder().apply(builder).build()
}

/**
 * Build [SendMessageBatchRequestEntry]
 *
 * @param id                An identifier for the message in this batch.
 * @param messageGroupId    An identifier for the group of messages in this batch.
 * @param messageBody       The message to send.
 * @param delaySeconds      The length of time, in seconds, for which to delay a specific message. Range: 0..900.
 * @param builder       The lambda to initialize the builder.
 * @receiver            The builder to build the request.
 * @return            [SendMessageBatchRequestEntry] instance.
 */
inline fun sendMessageBatchRequestEntryOf(
    id: String,
    messageGroupId: String,
    messageBody: String,
    delaySeconds: Int? = null,
    builder: SendMessageBatchRequestEntry.Builder.() -> Unit = {},
): SendMessageBatchRequestEntry {
    id.requireNotBlank("id")
    messageGroupId.requireNotBlank("messageGroupId")
    messageBody.requireNotBlank("messageBody")
    delaySeconds?.validateSqsDelaySeconds("delaySeconds")

    return sendMessageBatchRequestEntry {
        id(id)
        messageGroupId(messageGroupId)
        messageBody(messageBody)
        delaySeconds?.let { delaySeconds(it) }

        builder()
    }
}
