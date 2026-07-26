package io.bluetape4k.aws.sqs.model

import io.bluetape4k.support.requireNotBlank
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest

@PublishedApi
internal const val MIN_RECEIVE_MESSAGES = 1

@PublishedApi
internal const val MAX_RECEIVE_MESSAGES = 10

@PublishedApi
internal const val MIN_WAIT_TIME_SECONDS = 0

@PublishedApi
internal const val MAX_WAIT_TIME_SECONDS = 20

/**
 * Creates a [ReceiveMessageRequest].
 *
 * @param builder Lambda that initializes [ReceiveMessageRequest] with [ReceiveMessageRequest.Builder].
 *
 * ```kotlin
 * val request = receiveMessageRequest {
 *     queueUrl("https://sqs.ap-northeast-2.amazonaws.com/123/my-queue")
 *     maxNumberOfMessages(5)
 * }
 * // request.maxNumberOfMessages() == 5
 * ```
 */
inline fun receiveMessageRequest(
    builder: ReceiveMessageRequest.Builder.() -> Unit,
): ReceiveMessageRequest {
    return ReceiveMessageRequest.builder().apply(builder).build()
}

/**
 * Creates a [ReceiveMessageRequest] with `queueUrl`, `maxNumber`, `waitTimeSeconds`, and `attributeNames`.
 *
 * @param queueUrl URL of the Amazon SQS queue to receive messages from.
 * @param maxNumber Maximum number of messages to receive at once. Defaults to 3. (Allowed range: 1..10)
 * @param waitTimeSeconds Time to wait in seconds when no messages are available. Defaults to 20 seconds.
 * (Allowed range: 0..20)
 * @param attributeNames Collection of message attribute names to receive. Defaults to null.
 * @param builder Lambda that initializes [ReceiveMessageRequest.Builder]. Defaults to an empty lambda.
 * @return [ReceiveMessageRequest] instance.
 */
/**
 * Creates a [ReceiveMessageRequest] with `queueUrl`, `maxNumber`, `waitTimeSeconds`, and `attributeNames`.
 *
 * ```kotlin
 * val request = receiveMessageRequestOf(
 *     queueUrl = "https://sqs.ap-northeast-2.amazonaws.com/123/my-queue",
 *     maxNumber = 5,
 *     waitTimeSeconds = 10
 * )
 * // request.maxNumberOfMessages() == 5
 * ```
 */
inline fun receiveMessageRequestOf(
    queueUrl: String,
    maxNumber: Int = 3,
    waitTimeSeconds: Int = 20,
    attributeNames: Collection<String>? = null,
    builder: ReceiveMessageRequest.Builder.() -> Unit = {},
): ReceiveMessageRequest {
    queueUrl.requireNotBlank("queueUrl")
    require(maxNumber in MIN_RECEIVE_MESSAGES..MAX_RECEIVE_MESSAGES) {
        "maxNumber must be in $MIN_RECEIVE_MESSAGES..$MAX_RECEIVE_MESSAGES, but was $maxNumber"
    }
    require(waitTimeSeconds in MIN_WAIT_TIME_SECONDS..MAX_WAIT_TIME_SECONDS) {
        "waitTimeSeconds must be in $MIN_WAIT_TIME_SECONDS..$MAX_WAIT_TIME_SECONDS, but was $waitTimeSeconds"
    }

    return receiveMessageRequest {
        queueUrl(queueUrl)
        maxNumberOfMessages(maxNumber)
        waitTimeSeconds(waitTimeSeconds)
        attributeNames?.let { messageAttributeNames(it) }

        builder()
    }
}
