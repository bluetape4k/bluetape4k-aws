package io.bluetape4k.aws.kotlin.s3tables

import aws.sdk.kotlin.services.s3tables.S3TablesClient
import aws.smithy.kotlin.runtime.auth.awscredentials.CredentialsProvider
import aws.smithy.kotlin.runtime.http.engine.HttpClientEngine
import aws.smithy.kotlin.runtime.net.url.Url
import io.bluetape4k.support.useSafe

inline fun s3TablesClientOf(
    endpointUrl: Url? = null,
    region: String? = null,
    credentialsProvider: CredentialsProvider? = null,
    httpClient: HttpClientEngine? = null,
    crossinline builder: S3TablesClient.Config.Builder.() -> Unit = {},
): S3TablesClient = S3TablesClient {
    endpointUrl?.let { this.endpointUrl = it }
    region?.let { this.region = it }
    credentialsProvider?.let { this.credentialsProvider = it }
    httpClient?.let { this.httpClient = it }
    builder()
}

suspend fun <R> withS3TablesClient(
    endpointUrl: Url? = null,
    region: String? = null,
    credentialsProvider: CredentialsProvider? = null,
    httpClient: HttpClientEngine? = null,
    builder: S3TablesClient.Config.Builder.() -> Unit = {},
    block: suspend (S3TablesClient) -> R,
): R = withS3TablesClient(
    clientFactory = { s3TablesClientOf(endpointUrl, region, credentialsProvider, httpClient, builder) },
    block = block,
)

internal suspend fun <R> withS3TablesClient(
    clientFactory: () -> S3TablesClient,
    block: suspend (S3TablesClient) -> R,
): R = clientFactory().useSafe { client -> block(client) }
