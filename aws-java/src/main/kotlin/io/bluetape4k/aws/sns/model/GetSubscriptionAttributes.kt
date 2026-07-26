package io.bluetape4k.aws.sns.model

import io.bluetape4k.support.requireNotBlank
import software.amazon.awssdk.awscore.AwsRequestOverrideConfiguration
import software.amazon.awssdk.services.sns.model.GetSubscriptionAttributesRequest

/**
 * Builds a [GetSubscriptionAttributesRequest] with a DSL block.
 *
 * ## Behavior/Contract
 * - Sets `subscriptionArn` and other fields directly in the [builder] block.
 *
 * ```kotlin
 * val req = getSubscriptionAttributesRequest {
 *     subscriptionArn("arn:aws:sns:ap-northeast-2:123456:my-topic:sub-id")
 * }
 * ```
 */
inline fun getSubscriptionAttributesRequest(
    builder: GetSubscriptionAttributesRequest.Builder.() -> Unit,
): GetSubscriptionAttributesRequest =
    GetSubscriptionAttributesRequest.builder().apply(builder).build()

/**
 * Creates a [GetSubscriptionAttributesRequest] from a subscription ARN.
 *
 * ## Behavior/Contract
 * - Throws `IllegalArgumentException` when [subscriptionArn] is non-null and blank.
 *
 * ```kotlin
 * val req = getSubscriptionAttributesRequestOf("arn:aws:sns:ap-northeast-2:123456:my-topic:sub-id")
 * // req.subscriptionArn().isNotBlank() == true
 * ```
 */
inline fun getSubscriptionAttributesRequestOf(
    subscriptionArn: String? = null,
    overrideConfiguration: AwsRequestOverrideConfiguration? = null,
    builder: GetSubscriptionAttributesRequest.Builder.() -> Unit = {},
): GetSubscriptionAttributesRequest =
    getSubscriptionAttributesRequest {
        subscriptionArn?.let {
            it.requireNotBlank("subscriptionArn")
            subscriptionArn(it)
        }
        overrideConfiguration?.let { overrideConfiguration(it) }

        builder()
    }
