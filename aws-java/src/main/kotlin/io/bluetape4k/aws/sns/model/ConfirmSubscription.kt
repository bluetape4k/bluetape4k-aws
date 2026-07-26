package io.bluetape4k.aws.sns.model

import io.bluetape4k.support.requireNotBlank
import software.amazon.awssdk.services.sns.model.ConfirmSubscriptionRequest

/**
 * Builds a [ConfirmSubscriptionRequest] with a DSL block.
 *
 * ## Behavior/Contract
 * - Sets `topicArn`, `token`, and other fields directly in the [builder] block.
 *
 * ```kotlin
 * val req = confirmSubscriptionRequest {
 *     topicArn("arn:aws:sns:ap-northeast-2:123456:my-topic")
 *     token("abc123token")
 * }
 * ```
 */
inline fun confirmSubscriptionRequest(
    builder: ConfirmSubscriptionRequest.Builder.() -> Unit,
): ConfirmSubscriptionRequest =
    ConfirmSubscriptionRequest.builder().apply(builder).build()

/**
 * Creates a [ConfirmSubscriptionRequest] from a topic ARN and confirmation token.
 *
 * ## Behavior/Contract
 * - Throws `IllegalArgumentException` when [topicArn] is blank.
 * - Throws `IllegalArgumentException` when [token] is blank.
 *
 * ```kotlin
 * val req = confirmSubscriptionRequestOf(
 *     topicArn = "arn:aws:sns:ap-northeast-2:123456:my-topic",
 *     token = "abc123token"
 * )
 * // req.topicArn().isNotBlank() == true
 * ```
 */
inline fun confirmSubscriptionRequestOf(
    topicArn: String,
    token: String,
    builder: ConfirmSubscriptionRequest.Builder.() -> Unit = {},
): ConfirmSubscriptionRequest {
    topicArn.requireNotBlank("topicArn")
    token.requireNotBlank("token")

    return confirmSubscriptionRequest {
        topicArn(topicArn)
        token(token)
        builder()
    }
}
