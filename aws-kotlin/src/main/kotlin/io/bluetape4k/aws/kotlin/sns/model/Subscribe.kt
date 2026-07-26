package io.bluetape4k.aws.kotlin.sns.model

import aws.sdk.kotlin.services.sns.model.SubscribeRequest
import aws.sdk.kotlin.services.sns.model.UnsubscribeRequest
import io.bluetape4k.support.requireNotBlank

/**
 * Creates a [SubscribeRequest] for the topic identified by [topicArn].
 *
 * ```
 * val request = subscribeRequestOf(
 *    topicArn = "arn:aws:sns:ap-northeast-2:123456789012:MyTopic",
 *    endpoint = "+821012345678",
 *    protocol = "sms"
 * )
 * client.subscribe(request)
 * ```
 *
 * @param topicArn ARN of the topic to subscribe to.
 * @param endpoint Endpoint to subscribe.
 * @param protocol Protocol used by the endpoint.
 * @param builder Lambda for applying additional settings to [SubscribeRequest.Builder].
 * @return A [SubscribeRequest] instance.
 */
inline fun subscribeRequestOf(
    topicArn: String,
    endpoint: String,
    protocol: String = "sms",
    crossinline builder: SubscribeRequest.Builder.() -> Unit = {},
): SubscribeRequest {
    topicArn.requireNotBlank("topicArn")
    protocol.requireNotBlank("protocol")
    endpoint.requireNotBlank("endpoint")

    return SubscribeRequest {
        this.topicArn = topicArn
        this.protocol = protocol
        this.endpoint = endpoint

        builder()
    }
}

/**
 * Creates an [UnsubscribeRequest] for the subscription identified by [subscriptionArn].
 *
 * ```
 * val request = unsubscribeRequestOf("arn:aws:sns:ap-northeast-2:123456789012:MyTopic:12345678-1234-1234-1234-123456789012")
 * client.unsubscribe(request)
 * ```
 *
 * @param subscriptionArn ARN of the subscription to cancel.
 * @param builder Lambda for applying additional settings to [UnsubscribeRequest.Builder].
 * @return An [UnsubscribeRequest] instance.
 */
inline fun unsubscribeRequestOf(
    subscriptionArn: String,
    crossinline builder: UnsubscribeRequest.Builder.() -> Unit = {},
): UnsubscribeRequest {
    subscriptionArn.requireNotBlank("subscriptionArn")

    return UnsubscribeRequest {
        this.subscriptionArn = subscriptionArn
        builder()
    }
}
