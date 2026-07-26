package io.bluetape4k.aws.kotlin.sqs.model

import aws.sdk.kotlin.services.sqs.model.ReceiveMessageRequest
import io.bluetape4k.support.requireNotBlank

@PublishedApi
internal const val MIN_RECEIVE_MESSAGES = 1

@PublishedApi
internal const val MAX_RECEIVE_MESSAGES = 10

@PublishedApi
internal const val MIN_WAIT_TIME_SECONDS = 0

@PublishedApi
internal const val MAX_WAIT_TIME_SECONDS = 20

/**
 * Creates a ReceiveMessageRequest from queueUrl, maxNumber, waitTimeSeconds, and attributeNames.
 *
 * ```kotlin
 * import aws.sdk.kotlin.services.sqs.SqsClient
 * import io.bluetape4k.aws.kotlin.sqs.model.receiveMessageRequestOf
 *
 * suspend fun receiveOrders(sqsClient: SqsClient, queueUrl: String) {
 *     val request = receiveMessageRequestOf(
 *         queueUrl = queueUrl,
 *         maxNumberOfMessages = 5,
 *         waitTimeSeconds = 10
 *     ) {
 *         receiveRequestAttemptId = "attempt-001"  // Prevents duplicate delivery during FIFO queue retries.
 *     }
 *     val response = sqsClient.receiveMessage(request)
 *     val messages = response.messages
 *     check(messages != null)
 * }
 * ```
 *
 * @param queueUrl URL of the Amazon SQS queue from which to receive messages.
 * @param maxNumberOfMessages Maximum number of messages to receive at once. Defaults to 3 and must be in 1..10.
 * @param waitTimeSeconds Seconds to wait when no message is available. Defaults to 20 and must be in 0..20.
 * @param visibilityTimeout Seconds to hide a message while it is being processed. Defaults to null.
 * @param attributeNames Names of message attributes to receive. Defaults to null.
 * @param builder Lambda for initializing ReceiveMessageRequest.Builder. Defaults to an empty lambda.
 * @return A ReceiveMessageRequest instance.
 */
inline fun receiveMessageRequestOf(
    queueUrl: String,
    maxNumberOfMessages: Int = 3,
    waitTimeSeconds: Int = 20,
    visibilityTimeout: Int? = null,
    attributeNames: Collection<String>? = null,
    crossinline builder: ReceiveMessageRequest.Builder.() -> Unit = {},
): ReceiveMessageRequest {
    queueUrl.requireNotBlank("queueUrl")
    require(maxNumberOfMessages in MIN_RECEIVE_MESSAGES..MAX_RECEIVE_MESSAGES) {
        "maxNumberOfMessages must be in the range $MIN_RECEIVE_MESSAGES..$MAX_RECEIVE_MESSAGES."
    }
    require(waitTimeSeconds in MIN_WAIT_TIME_SECONDS..MAX_WAIT_TIME_SECONDS) {
        "waitTimeSeconds must be in the range $MIN_WAIT_TIME_SECONDS..$MAX_WAIT_TIME_SECONDS."
    }

    return ReceiveMessageRequest {
        this.queueUrl = queueUrl
        this.maxNumberOfMessages = maxNumberOfMessages
        this.waitTimeSeconds = waitTimeSeconds
        this.visibilityTimeout = visibilityTimeout
        this.messageAttributeNames = attributeNames?.toList()

        builder()
    }
}
