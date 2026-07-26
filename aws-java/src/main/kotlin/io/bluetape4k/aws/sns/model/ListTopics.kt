package io.bluetape4k.aws.sns.model

import io.bluetape4k.support.requireNotBlank
import software.amazon.awssdk.awscore.AwsRequestOverrideConfiguration
import software.amazon.awssdk.services.sns.model.ListTopicsRequest

/**
 * Builds a [ListTopicsRequest] with a DSL block.
 *
 * ## Behavior/Contract
 * - Sets `nextToken` and other fields directly in the [builder] block.
 *
 * ```kotlin
 * val req = listTopicsRequest { nextToken("token123") }
 * ```
 */
inline fun listTopicsRequest(
    builder: ListTopicsRequest.Builder.() -> Unit,
): ListTopicsRequest =
    ListTopicsRequest.builder().apply(builder).build()

/**
 * Creates a [ListTopicsRequest] from a pagination token.
 *
 * ## Behavior/Contract
 * - Throws `IllegalArgumentException` when [nextToken] is non-null and blank.
 * - When [nextToken] is null, retrieves the first page.
 *
 * ```kotlin
 * val req = listTopicsRequestOf()
 * // Request the first page of topics
 * ```
 */
inline fun listTopicsRequestOf(
    nextToken: String? = null,
    overrideConfiguration: AwsRequestOverrideConfiguration? = null,
    builder: ListTopicsRequest.Builder.() -> Unit = {},
): ListTopicsRequest =
    listTopicsRequest {
        nextToken?.let {
            nextToken.requireNotBlank("nextToken")
            nextToken(it)
        }
        overrideConfiguration?.let { overrideConfiguration(it) }

        builder()
    }
