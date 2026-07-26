package io.bluetape4k.aws.sns.model

import io.bluetape4k.support.requireNotBlank
import software.amazon.awssdk.awscore.AwsRequestOverrideConfiguration
import software.amazon.awssdk.services.sns.model.CreatePlatformEndpointRequest

/**
 * Builds a [CreatePlatformEndpointRequest] with a DSL block.
 *
 * ## Behavior/Contract
 * - Sets `platformApplicationArn`, `token`, and other fields directly in the [builder] block.
 *
 * ```kotlin
 * val req = createPlatformEndpointRequest {
 *     platformApplicationArn("arn:aws:sns:ap-northeast-2:123456:app/GCM/my-app")
 *     token("device-token-xyz")
 * }
 * ```
 */
inline fun createPlatformEndpointRequest(
    builder: CreatePlatformEndpointRequest.Builder.() -> Unit,
): CreatePlatformEndpointRequest =
    CreatePlatformEndpointRequest.builder().apply(builder).build()

/**
 * Creates a [CreatePlatformEndpointRequest] from a platform application ARN and device token.
 *
 * ## Behavior/Contract
 * - Throws `IllegalArgumentException` when [platformApplicationArn] is blank.
 * - Throws `IllegalArgumentException` when [token] is blank.
 * - When [customUserData] is non-null, sets it as user data on the endpoint.
 *
 * ```kotlin
 * val req = createPlatformEndpointRequestOf(
 *     platformApplicationArn = "arn:aws:sns:ap-northeast-2:123456:app/GCM/my-app",
 *     token = "device-token-xyz"
 * )
 * // req.platformApplicationArn().isNotBlank() == true
 * ```
 */
inline fun createPlatformEndpointRequestOf(
    platformApplicationArn: String,
    token: String,
    customUserData: String? = null,
    attributes: Map<String, String>? = null,
    overrideConfiguration: AwsRequestOverrideConfiguration? = null,
    builder: CreatePlatformEndpointRequest.Builder.() -> Unit = {},
): CreatePlatformEndpointRequest {
    platformApplicationArn.requireNotBlank("platformApplicationArn")
    token.requireNotBlank("token")

    return createPlatformEndpointRequest {
        platformApplicationArn(platformApplicationArn)
        token(token)
        customUserData?.run { customUserData(this) }
        attributes?.run { attributes(this) }
        overrideConfiguration?.run { overrideConfiguration(this) }

        builder()
    }
}
