package io.bluetape4k.aws.kotlin.sqs.model

import aws.sdk.kotlin.services.sqs.model.ChangeMessageVisibilityBatchRequest
import aws.sdk.kotlin.services.sqs.model.ChangeMessageVisibilityBatchRequestEntry
import aws.sdk.kotlin.services.sqs.model.ChangeMessageVisibilityRequest
import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.support.requireNotEmpty

/**
 * Creates a [ChangeMessageVisibilityRequest] from the supplied queueUrl and receiptHandle.
 *
 * ```kotlin
 * val request = changeMessageVisibilityRequestOf(
 *     queueUrl = "https://sqs.ap-northeast-2.amazonaws.com/123456789012/MyQueue",
 *     receiptHandle = "receiptHandle",
 *     visibilityTimeout = 30
 * )
 * sqsClient.changeMessageVisibility(request)
 * ```
 *
 * @param queueUrl URL of the Amazon SQS queue containing the message.
 * @param receiptHandle Receipt handle associated with the message.
 * @param visibilityTimeout New message visibility timeout in seconds. Defaults to null.
 * @return A [ChangeMessageVisibilityRequest] instance.
 */
inline fun changeMessageVisibilityRequestOf(
    queueUrl: String,
    receiptHandle: String,
    visibilityTimeout: Int? = null,
    crossinline builder: ChangeMessageVisibilityRequest.Builder.() -> Unit = {},
): ChangeMessageVisibilityRequest {
    queueUrl.requireNotBlank("queueUrl")
    receiptHandle.requireNotBlank("receiptHandle")

    return ChangeMessageVisibilityRequest {
        this.queueUrl = queueUrl
        this.receiptHandle = receiptHandle
        this.visibilityTimeout = visibilityTimeout

        builder()
    }
}


/**
 * Creates a [ChangeMessageVisibilityBatchRequestEntry] from the supplied id and receiptHandle.
 *
 * ```kotlin
 * val entry = changeMessageVisibilityBatchRequestEntryOf(
 *     id = "msg-001",
 *     receiptHandle = "receiptHandle1",
 *     visibilityTimeout = 30
 * )
 * ```
 *
 * @param id Message identifier.
 * @param receiptHandle Receipt handle associated with the message.
 * @param visibilityTimeout New message visibility timeout in seconds. Defaults to null.
 * @return A [ChangeMessageVisibilityBatchRequestEntry] instance.
 */
inline fun changeMessageVisibilityBatchRequestEntryOf(
    id: String,
    receiptHandle: String,
    visibilityTimeout: Int? = null,
    crossinline builder: ChangeMessageVisibilityBatchRequestEntry.Builder.() -> Unit = {},
): ChangeMessageVisibilityBatchRequestEntry {
    id.requireNotBlank("id")
    receiptHandle.requireNotBlank("receiptHandle")

    return ChangeMessageVisibilityBatchRequestEntry {
        this.id = id
        this.receiptHandle = receiptHandle
        this.visibilityTimeout = visibilityTimeout

        builder()
    }
}

/**
 * Creates a [ChangeMessageVisibilityBatchRequest] from the supplied queueUrl and entries.
 *
 * ```kotlin
 * val request = changeMessageVisibilityBatchRequestOf(
 *     queueUrl = "https://sqs.ap-northeast-2.amazonaws.com/123456789012/MyQueue",
 *     entries = listOf(changeMessageVisibilityBatchRequestEntryOf("id1", "receiptHandle1", 30))
 * )
 * sqsClient.changeMessageVisibilityBatch(request)
 * ```
 *
 * @param queueUrl URL of the Amazon SQS queue containing the messages.
 * @param entries Collection of ChangeMessageVisibilityBatchRequestEntry instances.
 * @return A [ChangeMessageVisibilityBatchRequest] instance.
 */
@JvmName("changeMessageVisibilityBatchRequestOfCollection")
inline fun changeMessageVisibilityBatchRequestOf(
    queueUrl: String,
    entries: Collection<ChangeMessageVisibilityBatchRequestEntry>,
    crossinline builder: ChangeMessageVisibilityBatchRequest.Builder.() -> Unit = {},
): ChangeMessageVisibilityBatchRequest {
    queueUrl.requireNotBlank("queueUrl")
    entries.requireNotEmpty("entries")

    return ChangeMessageVisibilityBatchRequest {
        this.queueUrl = queueUrl
        this.entries = entries.toList()

        builder()
    }
}


/**
 * Creates a [ChangeMessageVisibilityBatchRequest] from the supplied queueUrl and entries.
 *
 * ```kotlin
 * val request = changeMessageVisibilityBatchRequestOf(
 *     queueUrl = "https://sqs.ap-northeast-2.amazonaws.com/123456789012/MyQueue",
 *     changeMessageVisibilityBatchRequestEntryOf("id1", "receiptHandle1", 30),
 *     changeMessageVisibilityBatchRequestEntryOf("id2", "receiptHandle2", 60)
 * )
 * sqsClient.changeMessageVisibilityBatch(request)
 * ```
 *
 * @param queueUrl URL of the Amazon SQS queue containing the messages.
 * @param entries Collection of ChangeMessageVisibilityBatchRequestEntry instances.
 * @return A [ChangeMessageVisibilityBatchRequest] instance.
 */
@JvmName("changeMessageVisibilityBatchRequestOfVararg")
inline fun changeMessageVisibilityBatchRequestOf(
    queueUrl: String,
    vararg entries: ChangeMessageVisibilityBatchRequestEntry,
    crossinline builder: ChangeMessageVisibilityBatchRequest.Builder.() -> Unit = {},
): ChangeMessageVisibilityBatchRequest {
    queueUrl.requireNotBlank("queueUrl")
    entries.requireNotEmpty("entries")

    return ChangeMessageVisibilityBatchRequest {
        this.queueUrl = queueUrl
        this.entries = entries.toList()

        builder()
    }
}
