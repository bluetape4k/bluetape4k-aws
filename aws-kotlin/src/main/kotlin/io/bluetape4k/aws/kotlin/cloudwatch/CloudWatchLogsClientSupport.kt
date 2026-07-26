package io.bluetape4k.aws.kotlin.cloudwatch

import aws.sdk.kotlin.services.cloudwatchlogs.CloudWatchLogsClient
import aws.sdk.kotlin.services.cloudwatchlogs.CloudWatchLogsClient.Config.Builder
import aws.smithy.kotlin.runtime.auth.awscredentials.CredentialsProvider
import aws.smithy.kotlin.runtime.http.engine.HttpClientEngine
import aws.smithy.kotlin.runtime.net.url.Url
import io.bluetape4k.support.useSafe

/**
 * Creates an AWS Kotlin SDK [CloudWatchLogsClient].
 *
 * ```kotlin
 * val client = cloudWatchLogsClientOf(
 *     endpointUrl = Url.parse("http://localhost:4566"),
 *     region = "us-east-1",
 *     credentialsProvider = credentialsProvider
 * )
 * ```
 *
 * @param endpointUrl CloudWatch Logs service endpoint URL; when null, uses the default AWS endpoint
 * @param region AWS Region; when null, resolves it from the environment
 * @param credentialsProvider AWS credentials provider; when null, uses the default credentials chain
 * @param httpClient optional externally managed HTTP engine. Omit it to let the SDK manage engine ownership.
 * @param builder additional configuration for [CloudWatchLogsClient.Config.Builder]
 * @return the configured [CloudWatchLogsClient]
 */
inline fun cloudWatchLogsClientOf(
    endpointUrl: Url? = null,
    region: String? = null,
    credentialsProvider: CredentialsProvider? = null,
    httpClient: HttpClientEngine? = null,
    crossinline builder: Builder.() -> Unit = {},
): CloudWatchLogsClient =
    CloudWatchLogsClient {
        endpointUrl?.let { this.endpointUrl = it }
        region?.let { this.region = it }
        credentialsProvider?.let { this.credentialsProvider = it }
        httpClient?.let { this.httpClient = it }

        builder()
    }

/**
 * Creates a [CloudWatchLogsClient], executes [block], and closes the client automatically.
 *
 * The SDK manages its internal HTTP engine, so closing the client also shuts down the engine.
 *
 * ```kotlin
 * withCloudWatchLogsClient(endpointUrl, region, credentialsProvider) { client ->
 *     client.putLogEvents(logGroupName, logStreamName, logEvents)
 * }
 * ```
 *
 * @param block suspending block; AWS SDK operations are suspend functions
 */
suspend fun <R> withCloudWatchLogsClient(
    endpointUrl: Url? = null,
    region: String? = null,
    credentialsProvider: CredentialsProvider? = null,
    block: suspend (CloudWatchLogsClient) -> R,
): R =
    cloudWatchLogsClientOf(endpointUrl, region, credentialsProvider).useSafe { client ->
        block(client)
    } 
