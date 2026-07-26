package io.bluetape4k.aws.kotlin.sns.model

import aws.sdk.kotlin.services.sns.model.CreateTopicRequest
import aws.sdk.kotlin.services.sns.model.DeleteTopicRequest
import aws.sdk.kotlin.services.sns.model.Tag
import io.bluetape4k.support.requireNotBlank

/**
 * Creates a topic named [name].
 *
 * ```
 * val request = createTopicRequestOf("MyTopic")
 * client.createTopic(request)
 * ```
 *
 * @param name Name of the topic to create.
 * @param tags Tags to add to the topic.
 * @param attributes Attributes to add to the topic.
 * @param builder Lambda for applying additional settings to [CreateTopicRequest.Builder].
 * @return A [CreateTopicRequest] instance.
 */
inline fun createTopicRequestOf(
    name: String,
    tags: List<Tag>? = null,
    attributes: Map<String, String>? = null,
    crossinline builder: CreateTopicRequest.Builder.() -> Unit = {},
): CreateTopicRequest {
    name.requireNotBlank("name")

    return CreateTopicRequest {
        this.name = name
        tags?.let { this.tags = it }
        attributes?.let { this.attributes = it }

        builder()
    }
}

/**
 * Deletes the topic identified by [topicArn].
 *
 * ```
 * val request = deleteTopicRequestOf("arn:aws:sns:us-east-1:123456789012:MyTopic")
 * client.deleteTopic(request)
 * ```
 *
 * @param topicArn ARN of the topic to delete.
 * @param builder Lambda for applying additional settings to [DeleteTopicRequest.Builder].
 * @return A [DeleteTopicRequest] instance.
 */
inline fun deleteTopicRequestOf(
    topicArn: String,
    crossinline builder: DeleteTopicRequest.Builder.() -> Unit = {},
): DeleteTopicRequest {
    topicArn.requireNotBlank("topicArn")

    return DeleteTopicRequest {
        this.topicArn = topicArn
        builder()
    }
}
