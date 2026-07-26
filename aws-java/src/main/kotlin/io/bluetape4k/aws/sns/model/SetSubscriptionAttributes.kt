package io.bluetape4k.aws.sns.model

import software.amazon.awssdk.awscore.AwsRequestOverrideConfiguration
import software.amazon.awssdk.services.sns.model.SetSubscriptionAttributesRequest

/**
 * Builds a [SetSubscriptionAttributesRequest] with a DSL block.
 *
 * ## Behavior/Contract
 * - Sets `subscriptionArn`, `attributeName`, `attributeValue`, and other fields directly in the [builder] block.
 *
 * ```kotlin
 * val req = setSubscriptionAttributesRequest {
 *     subscriptionArn("arn:aws:sns:ap-northeast-2:123456:my-topic:sub-id")
 *     attributeName("RawMessageDelivery")
 *     attributeValue("true")
 * }
 * ```
 */
inline fun setSubscriptionAttributesRequest(
    builder: SetSubscriptionAttributesRequest.Builder.() -> Unit,
): SetSubscriptionAttributesRequest =
    SetSubscriptionAttributesRequest.builder().apply(builder).build()

/**
 * Creates a [SetSubscriptionAttributesRequest] for configuring subscription attributes.
 *
 * ## Behavior/Contract
 * - All parameters are optional and are not set when null.
 *
 * ```kotlin
 * val req = setSubscriptionAttributesRequestOf(
 *     subscriptionArn = "arn:aws:sns:ap-northeast-2:123456:my-topic:sub-id",
 *     attributeName = "RawMessageDelivery",
 *     attributeValue = "true"
 * )
 * ```
 */
inline fun setSubscriptionAttributesRequestOf(
    subscriptionArn: String? = null,
    attributeName: String? = null,
    attributeValue: String? = null,
    overrideConfiguration: AwsRequestOverrideConfiguration? = null,
    builder: SetSubscriptionAttributesRequest.Builder.() -> Unit = {},
): SetSubscriptionAttributesRequest = setSubscriptionAttributesRequest {
    subscriptionArn?.let { subscriptionArn(it) }
    attributeName?.let { attributeName(it) }
    attributeValue?.let { attributeValue(it) }
    overrideConfiguration?.let { overrideConfiguration(it) }

    builder()
}
