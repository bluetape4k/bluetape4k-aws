package io.bluetape4k.aws.s3tables

import io.bluetape4k.aws.http.SdkHttpClientProvider
import io.bluetape4k.utils.ShutdownQueue
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider
import software.amazon.awssdk.http.SdkHttpClient
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3tables.S3TablesClient
import software.amazon.awssdk.services.s3tables.S3TablesClientBuilder
import java.net.URI

inline fun s3TablesClient(builder: S3TablesClientBuilder.() -> Unit): S3TablesClient =
    S3TablesClient.builder().apply(builder).build().apply(ShutdownQueue::register)

inline fun s3TablesClientOf(
    endpoint: URI? = null,
    region: Region? = null,
    credentialsProvider: AwsCredentialsProvider? = null,
    httpClient: SdkHttpClient = SdkHttpClientProvider.defaultHttpClient,
    noinline builder: S3TablesClientBuilder.() -> Unit = {},
): S3TablesClient = buildS3TablesClient(endpoint, region, credentialsProvider, httpClient, builder)
    .apply(ShutdownQueue::register)

inline fun <R> withS3TablesClient(
    endpoint: URI? = null,
    region: Region? = null,
    credentialsProvider: AwsCredentialsProvider? = null,
    httpClient: SdkHttpClient = SdkHttpClientProvider.defaultHttpClient,
    noinline builder: S3TablesClientBuilder.() -> Unit = {},
    block: (S3TablesClient) -> R,
): R {
    val client = buildS3TablesClient(endpoint, region, credentialsProvider, httpClient, builder)
    return try {
        block(client)
    } finally {
        client.close()
    }
}

internal inline fun <R> withS3TablesClient(
    clientFactory: () -> S3TablesClient,
    block: (S3TablesClient) -> R,
): R {
    val client = clientFactory()
    return try {
        block(client)
    } finally {
        client.close()
    }
}

@PublishedApi
internal fun buildS3TablesClient(
    endpoint: URI?,
    region: Region?,
    credentialsProvider: AwsCredentialsProvider?,
    httpClient: SdkHttpClient,
    builder: S3TablesClientBuilder.() -> Unit,
): S3TablesClient = S3TablesClient.builder().apply {
    endpoint?.let(::endpointOverride)
    region?.let(::region)
    credentialsProvider?.let(::credentialsProvider)
    httpClient(httpClient)
    builder()
}.build()
