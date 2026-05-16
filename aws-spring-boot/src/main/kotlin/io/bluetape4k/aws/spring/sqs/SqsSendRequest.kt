package io.bluetape4k.aws.spring.sqs

import software.amazon.awssdk.services.sqs.model.MessageAttributeValue
import java.io.Serializable

/**
 * SQS message send request.
 *
 * ## Behavior / Contract
 * - [queueUrl] and [body] must not be blank.
 * - [delaySeconds] must be in 0–900 when provided; applies to standard queues only.
 * - [messageGroupId] and [messageDeduplicationId] apply to FIFO queues only and must not be blank when provided.
 *
 * ```kotlin
 * val request = SqsSendRequest(
 *     queueUrl = "https://sqs.us-east-1.amazonaws.com/123456789012/my-queue",
 *     body = """{"event":"order.placed","orderId":"42"}""",
 *     delaySeconds = 0,
 * )
 * ```
 */
data class SqsSendRequest(
    val queueUrl: String,
    val body: String,
    val delaySeconds: Int? = null,
    val messageGroupId: String? = null,
    val messageDeduplicationId: String? = null,
    val messageAttributes: Map<String, MessageAttributeValue> = emptyMap(),
) : Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }

    init {
        require(queueUrl.isNotBlank()) { "queueUrl must not be blank." }
        require(body.isNotBlank()) { "body must not be blank." }
        delaySeconds?.let { require(it in 0..900) { "delaySeconds must be between 0 and 900." } }
        messageGroupId?.let { require(it.isNotBlank()) { "messageGroupId must not be blank." } }
        messageDeduplicationId?.let { require(it.isNotBlank()) { "messageDeduplicationId must not be blank." } }
    }
}
