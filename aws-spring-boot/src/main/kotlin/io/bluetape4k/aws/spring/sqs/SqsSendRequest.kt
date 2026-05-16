package io.bluetape4k.aws.spring.sqs

import software.amazon.awssdk.services.sqs.model.MessageAttributeValue

/**
 * SQS message send request.
 */
data class SqsSendRequest(
    val queueUrl: String,
    val body: String,
    val delaySeconds: Int? = null,
    val messageGroupId: String? = null,
    val messageDeduplicationId: String? = null,
    val messageAttributes: Map<String, MessageAttributeValue> = emptyMap(),
) {
    init {
        require(queueUrl.isNotBlank()) { "queueUrl must not be blank." }
        require(body.isNotBlank()) { "body must not be blank." }
        delaySeconds?.let { require(it in 0..900) { "delaySeconds must be between 0 and 900." } }
        messageGroupId?.let { require(it.isNotBlank()) { "messageGroupId must not be blank." } }
        messageDeduplicationId?.let { require(it.isNotBlank()) { "messageDeduplicationId must not be blank." } }
    }
}
