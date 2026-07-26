package io.bluetape4k.aws.kotlin.cloudwatch

import aws.sdk.kotlin.services.cloudwatch.CloudWatchClient
import aws.smithy.kotlin.runtime.auth.awscredentials.CredentialsProvider
import aws.smithy.kotlin.runtime.http.engine.HttpClientEngine
import aws.smithy.kotlin.runtime.net.url.Url
import io.bluetape4k.support.useSafe

/**
 * Creates an AWS Kotlin SDK [CloudWatchClient].
 *
 * ```kotlin
 * val client = cloudWatchClientOf(
 *     endpointUrl = Url.parse("http://localhost:4566"),
 *     region = "us-east-1",
 *     credentialsProvider = credentialsProvider
 * )
 * ```
 *
 * @param endpointUrl CloudWatch service endpoint URL; when null, uses the default AWS endpoint
 * @param region AWS Region; when null, resolves it from the environment
 * @param credentialsProvider AWS credentials provider; when null, uses the default credentials chain
 * @param httpClient optional externally managed HTTP engine. Omit it to let the SDK manage engine ownership.
 * @param builder additional configuration for [CloudWatchClient.Config.Builder]
 * @return the configured [CloudWatchClient]
 */
inline fun cloudWatchClientOf(
    endpointUrl: Url? = null,
    region: String? = null,
    credentialsProvider: CredentialsProvider? = null,
    httpClient: HttpClientEngine? = null,
    crossinline builder: CloudWatchClient.Config.Builder.() -> Unit = {},
): CloudWatchClient =
    CloudWatchClient {
        endpointUrl?.let { this.endpointUrl = it }
        region?.let { this.region = it }
        credentialsProvider?.let { this.credentialsProvider = it }
        httpClient?.let { this.httpClient = it }

        builder()
    }

/**
 * Creates a [CloudWatchClient], executes [block], and closes the client automatically.
 *
 * The SDK manages its internal HTTP engine, so closing the client also shuts down the engine.
 *
 * ```kotlin
 * withCloudWatchClient(endpointUrl, region, credentialsProvider) { client ->
 *     client.putMetricData(namespace, listOf(metricDatum))
 * }
 * ```
 *
 * @param block suspending block; AWS SDK operations are suspend functions
 */
suspend fun <R> withCloudWatchClient(
    endpointUrl: Url? = null,
    region: String? = null,
    credentialsProvider: CredentialsProvider? = null,
    block: suspend (CloudWatchClient) -> R,
): R =
    cloudWatchClientOf(endpointUrl, region, credentialsProvider).useSafe { client ->
        block(client)
    }
