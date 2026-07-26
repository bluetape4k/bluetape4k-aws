package io.bluetape4k.aws.kotlin.sns.model

import aws.sdk.kotlin.services.sns.model.ListSubscriptionsRequest
import io.bluetape4k.support.requireNotBlank

/**
 * Creates a request to list subscriptions, continuing from [nextToken] when provided.
 *
 * ```
 * val request = listSubscriptionsRequestOf("nextToken")
 * val subscriptions = client.listSubscriptions(request)
 * ```
 *
 * @param nextToken Token used to retrieve the next page.
 * @param builder Lambda for applying additional settings to [ListSubscriptionsRequest.Builder].
 * @return A [ListSubscriptionsRequest] instance.
 */
inline fun listSubscriptionsRequestOf(
    nextToken: String,
    crossinline builder: ListSubscriptionsRequest.Builder.() -> Unit = {},
): ListSubscriptionsRequest {
    nextToken.requireNotBlank("nextToken")

    return ListSubscriptionsRequest {
        this.nextToken = nextToken
        builder()
    }
}

@Deprecated(
    message = "Use listSubscriptionsRequestOf instead.",
    replaceWith = ReplaceWith("listSubscriptionsRequestOf(nextToken, builder)")
)
inline fun listSubscriptinosRequestOf(
    nextToken: String,
    crossinline builder: ListSubscriptionsRequest.Builder.() -> Unit = {},
): ListSubscriptionsRequest = listSubscriptionsRequestOf(nextToken, builder)
