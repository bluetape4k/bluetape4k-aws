package io.bluetape4k.aws.kotlin.sns.model

import aws.sdk.kotlin.services.sns.model.ListTopicsRequest
import io.bluetape4k.support.requireNotBlank

/**
 * Creates a request to list topics, continuing from [nextToken] when provided.
 *
 * ```
 * val request = listTopicsRequestOf("nextToken")
 * client.listTopics(request)
 * ```
 *
 * @param nextToken Token used to retrieve the next page.
 * @param builder Lambda for applying additional settings to [ListTopicsRequest.Builder].
 * @return A [ListTopicsRequest] instance.
 */
inline fun listTopicsRequestOf(
    nextToken: String,
    crossinline builder: ListTopicsRequest.Builder.() -> Unit = {},
): ListTopicsRequest {
    nextToken.requireNotBlank("nextToken")

    return ListTopicsRequest {
        this.nextToken = nextToken
        builder()
    }
}
