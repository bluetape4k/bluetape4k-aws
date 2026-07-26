package io.bluetape4k.aws.kotlin.dynamodb

import aws.sdk.kotlin.services.dynamodb.DynamoDbClient
import aws.smithy.kotlin.runtime.auth.awscredentials.CredentialsProvider
import aws.smithy.kotlin.runtime.http.engine.HttpClientEngine
import aws.smithy.kotlin.runtime.net.url.Url
import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.support.useSafe


/**
 * Creates a [DynamoDbClient].
 *
 * @param endpointUrl DynamoDB endpoint URL.
 * @param region AWS region. Required because the AWS SDK for Kotlin fails at runtime without it.
 * @param credentialsProvider AWS credentials provider.
 * @param httpClient optional externally managed HTTP engine. Omit it to let the SDK manage engine ownership.
 * @param builder configures [DynamoDbClient.Config] through [DynamoDbClient.Config.Builder].
 *
 * @return a [DynamoDbClient] instance.
 * @throws IllegalArgumentException if [region] is blank.
 */
inline fun dynamoDbClientOf(
    endpointUrl: Url? = null,
    region: String,
    credentialsProvider: CredentialsProvider? = null,
    httpClient: HttpClientEngine? = null,
    crossinline builder: DynamoDbClient.Config.Builder.() -> Unit = {},
): DynamoDbClient {
    // WHY: the AWS SDK for Kotlin requires region; fail fast before client initialization.
    region.requireNotBlank("region")

    return DynamoDbClient {
        endpointUrl?.let { this.endpointUrl = it }
        this.region = region
        credentialsProvider?.let { this.credentialsProvider = it }
        httpClient?.let { this.httpClient = it }

        builder()
    }
}

/**
 * Creates a [DynamoDbClient], runs [block], and closes the client automatically.
 *
 * When the SDK manages the internal HTTP engine, closing the client also closes that engine.
 *
 * ```kotlin
 * withDynamoDbClient(endpointUrl, region = "us-east-1", credentialsProvider) { client ->
 *     client.putItem(tableName, item)
 * }
 * ```
 *
 * @param region AWS region. Required.
 * @param block suspend block. AWS SDK operations are suspend functions, so this block is suspend too.
 * @throws IllegalArgumentException if [region] is blank.
 */
suspend fun <R> withDynamoDbClient(
    endpointUrl: Url? = null,
    region: String,
    credentialsProvider: CredentialsProvider? = null,
    block: suspend (DynamoDbClient) -> R,
): R {
    return dynamoDbClientOf(endpointUrl, region, credentialsProvider).useSafe { client ->
        block(client)
    }
}
