package io.bluetape4k.aws.sns.model

import software.amazon.awssdk.awscore.AwsRequestOverrideConfiguration
import software.amazon.awssdk.services.sns.model.SetSmsAttributesRequest

/**
 * Builds a [SetSmsAttributesRequest] with a DSL block.
 *
 * ## Behavior/Contract
 * - Sets `attributes` and other fields directly in the [builder] block.
 *
 * ```kotlin
 * val req = setSmsAttributesRequest {
 *     attributes(mapOf("DefaultSMSType" to "Transactional"))
 * }
 * ```
 */
inline fun setSmsAttributesRequest(
    builder: SetSmsAttributesRequest.Builder.() -> Unit,
): SetSmsAttributesRequest =
    SetSmsAttributesRequest.builder().apply(builder).build()

/**
 * Creates a [SetSmsAttributesRequest] from an SMS attributes map.
 *
 * ## Behavior/Contract
 * - The [attributes] map contains SMS delivery settings such as `DefaultSMSType` and `MonthlySpendLimit`.
 *
 * ```kotlin
 * val req = setSmsAttributesRequestOf(
 *     attributes = mapOf("DefaultSMSType" to "Transactional")
 * )
 * // req.attributes()["DefaultSMSType"] == "Transactional"
 * ```
 */
inline fun setSmsAttributesRequestOf(
    attributes: Map<String, String>,
    overrideConfiguration: AwsRequestOverrideConfiguration? = null,
    builder: SetSmsAttributesRequest.Builder.() -> Unit = {},
): SetSmsAttributesRequest = setSmsAttributesRequest {
    attributes(attributes)
    overrideConfiguration?.let { overrideConfiguration(it) }

    builder()
}
