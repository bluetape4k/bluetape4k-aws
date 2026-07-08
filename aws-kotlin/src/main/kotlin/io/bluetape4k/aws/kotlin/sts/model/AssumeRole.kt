package io.bluetape4k.aws.kotlin.sts.model

import aws.sdk.kotlin.services.sts.model.AssumeRoleRequest
import io.bluetape4k.support.requireNotBlank

/**
 * Builds an [AssumeRoleRequest] from a DSL block.
 *
 * ## Contract
 * - The [builder] block sets `roleArn`, `roleSessionName`, and any other request fields directly.
 *
 * ```kotlin
 * val req = assumeRoleRequest {
 *     roleArn = "arn:aws:iam::123456789012:role/MyRole"
 *     roleSessionName = "my-session"
 * }
 * ```
 */
inline fun assumeRoleRequest(
    crossinline builder: AssumeRoleRequest.Builder.() -> Unit,
): AssumeRoleRequest =
    AssumeRoleRequest { builder() }

/**
 * Creates an [AssumeRoleRequest] from a role ARN and session name.
 *
 * ## Contract
 * - Throws [IllegalArgumentException] when [roleArn] is blank.
 * - Throws [IllegalArgumentException] when [sessionName] is blank.
 *
 * ```kotlin
 * val req = assumeRoleRequestOf(
 *     roleArn = "arn:aws:iam::123456789012:role/MyRole",
 *     sessionName = "my-session"
 * )
 * // req.roleArn == "arn:aws:iam::123456789012:role/MyRole"
 * // req.roleSessionName == "my-session"
 * ```
 */
inline fun assumeRoleRequestOf(
    roleArn: String,
    sessionName: String,
    crossinline builder: AssumeRoleRequest.Builder.() -> Unit = {},
): AssumeRoleRequest {
    roleArn.requireNotBlank("roleArn")
    sessionName.requireNotBlank("sessionName")

    return assumeRoleRequest {
        this.roleArn = roleArn
        this.roleSessionName = sessionName

        builder()
    }
}
