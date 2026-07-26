package io.bluetape4k.aws.sns.model

import io.bluetape4k.support.requireNotBlank
import software.amazon.awssdk.services.sns.model.UnsubscribeRequest

/**
 * Builds an [UnsubscribeRequest] with a DSL block.
 *
 * ## Behavior/Contract
 * - Sets `subscriptionArn` and other fields directly in the [builder] block.
 *
 * ```kotlin
 * val req = unsubscribeRequest {
 *     subscriptionArn("arn:aws:sns:ap-northeast-2:123456:my-topic:sub-id")
 * }
 * ```
 */
inline fun unsubscribeRequest(
    builder: UnsubscribeRequest.Builder.() -> Unit,
): UnsubscribeRequest = UnsubscribeRequest.builder().apply(builder).build()

/**
 * Creates an [UnsubscribeRequest] from a subscription ARN.
 *
 * ## Behavior/Contract
 * - Throws `IllegalArgumentException` when [subscriptionArn] is blank.
 *
 * ```kotlin
 * val req = unsubscribeRequestOf("arn:aws:sns:ap-northeast-2:123456:my-topic:sub-id")
 * // req.subscriptionArn().isNotBlank() == true
 * ```
 */
inline fun unsubscribeRequestOf(
    subscriptionArn: String,
    builder: UnsubscribeRequest.Builder.() -> Unit = {},
): UnsubscribeRequest {
    subscriptionArn.requireNotBlank("subscriptionArn")

    return unsubscribeRequest {
        this.subscriptionArn(subscriptionArn)

        builder()
    }
}
