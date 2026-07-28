package io.bluetape4k.aws.kotlin.sts.model

import aws.sdk.kotlin.services.sts.model.AssumeRoleRequest
import io.bluetape4k.support.requireNotBlank

/**
 * DSL block에서 [AssumeRoleRequest]를 생성한다.
 *
 * ## 계약
 * - [builder] block은 `roleArn`, `roleSessionName`과 다른 request field를 직접 설정한다.
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
 * role ARN과 session name으로 [AssumeRoleRequest]를 생성한다.
 *
 * ## 계약
 * - [roleArn]이 blank이면 [IllegalArgumentException]을 던진다.
 * - [sessionName]이 blank이면 [IllegalArgumentException]을 던진다.
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
