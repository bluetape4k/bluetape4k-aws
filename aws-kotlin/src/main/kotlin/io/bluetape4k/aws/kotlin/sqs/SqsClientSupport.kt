package io.bluetape4k.aws.kotlin.sqs

import aws.sdk.kotlin.services.sqs.SqsClient
import aws.smithy.kotlin.runtime.auth.awscredentials.CredentialsProvider
import aws.smithy.kotlin.runtime.http.engine.HttpClientEngine
import aws.smithy.kotlin.runtime.net.url.Url
import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.support.useSafe

/**
 * Creates an AWS Kotlin SDK [SqsClient] instance.
 *
 * ```kotlin
 * val client = sqsClientOf(
 *     endpointUrl = Url.parse("http://localhost:4566"),
 *     region = "us-east-1",
 *     credentialsProvider = credentialsProvider
 * )
 * ```
 *
 * @param endpointUrl SQS service endpoint URL. Uses the default AWS endpoint when null.
 * @param region AWS Region. Automatically detected from the environment when null.
 * @param credentialsProvider AWS credentials provider. Uses the default credentials chain when null.
 * @param httpClient optional externally managed HTTP engine. Omit it to let the SDK manage engine ownership.
 * @param builder Lambda for applying additional settings to [SqsClient.Config.Builder].
 * @return The configured [SqsClient] instance.
 */
inline fun sqsClientOf(
    endpointUrl: Url? = null,
    region: String? = null,
    credentialsProvider: CredentialsProvider? = null,
    httpClient: HttpClientEngine? = null,
    crossinline builder: SqsClient.Config.Builder.() -> Unit = {},
): SqsClient {
    // WHY: A null endpointUrl selects the default AWS endpoint, so no validation is needed.
    endpointUrl?.let {
        it.host.toString().requireNotBlank("endpointUrl.host")
    }

    return SqsClient {
        endpointUrl?.let { this.endpointUrl = it }
        region?.let { this.region = it }
        credentialsProvider?.let { this.credentialsProvider = it }
        httpClient?.let { this.httpClient = it }

        builder()
    }
}

/**
 * Creates an [SqsClient], executes [block], and closes the client automatically.
 *
 * The SDK owns its internal HTTP engine, so closing the client also shuts down the engine.
 *
 * ```kotlin
 * withSqsClient(endpointUrl, region, credentialsProvider) { client ->
 *     client.sendMessage { ... }
 * }
 * ```
 *
 * @param block Suspending block because all AWS SDK operations are suspend functions.
 */
suspend fun <R> withSqsClient(
    endpointUrl: Url? = null,
    region: String? = null,
    credentialsProvider: CredentialsProvider? = null,
    block: suspend (SqsClient) -> R,
): R = sqsClientOf(endpointUrl, region, credentialsProvider).useSafe { client ->
    block(client)
}
