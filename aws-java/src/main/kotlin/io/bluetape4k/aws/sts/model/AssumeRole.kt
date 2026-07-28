package io.bluetape4k.aws.sts.model

import io.bluetape4k.support.requireNotBlank
import software.amazon.awssdk.services.sts.model.AssumeRoleRequest

/**
 * DSL block으로 [AssumeRoleRequest]를 생성한다.
 *
 * ## 동작과 계약
 * - [builder] 안에서 `roleArn`, `roleSessionName`과 다른 field를 직접 설정한다.
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
 * role ARN과 session name으로 [AssumeRoleRequest]를 생성한다.
 *
 * ## 동작과 계약
 * - [roleArn]이 blank이면 `IllegalArgumentException`을 던진다.
 * - [sessionName]이 blank이면 `IllegalArgumentException`을 던진다.
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
