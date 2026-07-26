package io.bluetape4k.aws.sts.model

import io.bluetape4k.support.requireNotBlank
import software.amazon.awssdk.services.sts.model.AssumeRoleRequest

/**
 * Builds an [AssumeRoleRequest] with a DSL block.
 *
 * ## Behavior and contract
 * - Set `roleArn`, `roleSessionName`, and other fields directly inside [builder].
 *
 * ```kotlin
 * val req = assumeRoleRequest {
 *     roleArn("arn:aws:iam::123456789012:role/MyRole")
 *     roleSessionName("my-session")
 * }
 * ```
 */
inline fun assumeRoleRequest(
    builder: AssumeRoleRequest.Builder.() -> Unit,
): AssumeRoleRequest =
    AssumeRoleRequest.builder().apply(builder).build()

/**
 * Creates an [AssumeRoleRequest] from a role ARN and session name.
 *
 * ## Behavior and contract
 * - Throws `IllegalArgumentException` when [roleArn] is blank.
 * - Throws `IllegalArgumentException` when [sessionName] is blank.
 *
 * ```kotlin
 * val req = assumeRoleRequestOf(
 *     roleArn = "arn:aws:iam::123456789012:role/MyRole",
 *     sessionName = "my-session"
 * )
 * // req.roleArn() == "arn:aws:iam::123456789012:role/MyRole"
 * // req.roleSessionName() == "my-session"
 * ```
 */
inline fun assumeRoleRequestOf(
    roleArn: String,
    sessionName: String,
    builder: AssumeRoleRequest.Builder.() -> Unit = {},
): AssumeRoleRequest {
    roleArn.requireNotBlank("roleArn")
    sessionName.requireNotBlank("sessionName")

    return assumeRoleRequest {
        roleArn(roleArn)
        roleSessionName(sessionName)

        builder()
    }
}
