package io.bluetape4k.aws.cloudwatch.model.cloudwatchlogs

import io.bluetape4k.support.requireNotBlank
import software.amazon.awssdk.services.cloudwatchlogs.model.CreateLogGroupRequest

/**
 * Builds a [CreateLogGroupRequest] with a DSL block.
 *
 * ```kotlin
 * val request = createLogGroupRequest {
 *     logGroupName("/aws/lambda/my-function")
 * }
 * ```
 */
inline fun createLogGroupRequest(
    builder: CreateLogGroupRequest.Builder.() -> Unit,
): CreateLogGroupRequest =
    CreateLogGroupRequest.builder().apply(builder).build()

/**
 * Creates a [CreateLogGroupRequest] from a log group name.
 *
 * ## Behavior/Contract
 * - Throws `IllegalArgumentException` when [logGroupName] is blank.
 *
 * ```kotlin
 * val request = createLogGroupRequestOf("/aws/lambda/my-function")
 * // request.logGroupName() == "/aws/lambda/my-function"
 * ```
 */
inline fun createLogGroupRequestOf(
    logGroupName: String,
    builder: CreateLogGroupRequest.Builder.() -> Unit = {},
): CreateLogGroupRequest {
    logGroupName.requireNotBlank("logGroupName")
    return createLogGroupRequest {
        logGroupName(logGroupName)
        builder()
    }
}
