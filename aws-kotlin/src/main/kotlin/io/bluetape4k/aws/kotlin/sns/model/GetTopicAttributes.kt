package io.bluetape4k.aws.kotlin.sns.model

import aws.sdk.kotlin.services.sns.model.GetTopicAttributesRequest
import io.bluetape4k.support.requireNotBlank

/**
 * Creates a request to retrieve the attributes of the topic identified by [topicArn].
 *
 * ```
 * val request = getTopicAttributesRequestOf("topicArn")
 * val attributes = client.getTopicAttributes(request)
 * ```
 *
 * @param topicArn ARN of the topic.
 * @param builder Lambda for applying additional settings to [GetTopicAttributesRequest.Builder].
 * @return A [GetTopicAttributesRequest] instance.
 */
inline fun getTopicAttributesRequestOf(
    topicArn: String,
    crossinline builder: GetTopicAttributesRequest.Builder.() -> Unit = {},
): GetTopicAttributesRequest {
    topicArn.requireNotBlank("topicArn")

    return GetTopicAttributesRequest {
        this.topicArn = topicArn
        builder()
    }
}
