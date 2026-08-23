package io.bluetape4k.aws.s3tables

import io.bluetape4k.aws.http.SdkAsyncHttpClientProvider
import io.bluetape4k.utils.ShutdownQueue
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider
import software.amazon.awssdk.http.async.SdkAsyncHttpClient
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3tables.S3TablesAsyncClient
import software.amazon.awssdk.services.s3tables.S3TablesAsyncClientBuilder
import java.net.URI

inline fun s3TablesAsyncClient(builder: S3TablesAsyncClientBuilder.() -> Unit): S3TablesAsyncClient =
    S3TablesAsyncClient.builder().apply(builder).build().apply(ShutdownQueue::register)

inline fun s3TablesAsyncClientOf(
    endpoint: URI? = null,
    region: Region? = null,
    credentialsProvider: AwsCredentialsProvider? = null,
    httpClient: SdkAsyncHttpClient = SdkAsyncHttpClientProvider.defaultHttpClient,
    noinline builder: S3TablesAsyncClientBuilder.() -> Unit = {},
): S3TablesAsyncClient = buildS3TablesAsyncClient(endpoint, region, credentialsProvider, httpClient, builder)
    .apply(ShutdownQueue::register)

suspend inline fun <R> withS3TablesAsyncClient(
    endpoint: URI? = null,
    region: Region? = null,
    credentialsProvider: AwsCredentialsProvider? = null,
    httpClient: SdkAsyncHttpClient = SdkAsyncHttpClientProvider.defaultHttpClient,
    noinline builder: S3TablesAsyncClientBuilder.() -> Unit = {},
    crossinline block: suspend (S3TablesAsyncClient) -> R,
): R {
    val client = buildS3TablesAsyncClient(endpoint, region, credentialsProvider, httpClient, builder)
    return try {
        block(client)
    } finally {
        client.close()
    }
}

internal suspend fun <R> withS3TablesAsyncClient(
    clientFactory: () -> S3TablesAsyncClient,
    block: suspend (S3TablesAsyncClient) -> R,
): R {
    val client = clientFactory()
    return try {
        block(client)
    } finally {
        client.close()
    }
}

@PublishedApi
internal fun buildS3TablesAsyncClient(
    endpoint: URI?,
    region: Region?,
    credentialsProvider: AwsCredentialsProvider?,
    httpClient: SdkAsyncHttpClient,
    builder: S3TablesAsyncClientBuilder.() -> Unit,
): S3TablesAsyncClient = S3TablesAsyncClient.builder().apply {
    endpoint?.let(::endpointOverride)
    region?.let(::region)
    credentialsProvider?.let(::credentialsProvider)
    httpClient(httpClient)
    builder()
}.build()
