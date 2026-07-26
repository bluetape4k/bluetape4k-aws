package io.bluetape4k.aws.sns.model

import io.bluetape4k.support.requireNotBlank
import software.amazon.awssdk.awscore.AwsRequestOverrideConfiguration
import software.amazon.awssdk.services.sns.model.ListSubscriptionsRequest

/**
 * Builds a [ListSubscriptionsRequest] with a DSL block.
 *
 * ## Behavior/Contract
 * - Sets `nextToken` and other fields directly in the [builder] block.
 *
 * ```kotlin
 * val req = listSubscriptionsRequest { nextToken("token123") }
 * ```
 */
inline fun listSubscriptionsRequest(
    builder: ListSubscriptionsRequest.Builder.() -> Unit,
): ListSubscriptionsRequest =
    ListSubscriptionsRequest.builder().apply(builder).build()

/**
 * Creates a [ListSubscriptionsRequest] from a pagination token.
 *
 * ## Behavior/Contract
 * - Throws `IllegalArgumentException` when [nextToken] is non-null and blank.
 * - When [nextToken] is null, retrieves the first page.
 *
 * ```kotlin
 * val req = listSubscriptionsRequestOf()
 * // Request the first page of subscriptions
 * ```
 */
inline fun listSubscriptionsRequestOf(
    nextToken: String? = null,
    overrideConfiguration: AwsRequestOverrideConfiguration? = null,
    builder: ListSubscriptionsRequest.Builder.() -> Unit = {},
): ListSubscriptionsRequest =
    listSubscriptionsRequest {
        nextToken?.let {
            nextToken.requireNotBlank("nextToken")
            nextToken(it)
        }
        overrideConfiguration?.let { overrideConfiguration(it) }
        builder()
    }
