package io.bluetape4k.aws.kotlin.kms

import aws.sdk.kotlin.services.kms.KmsClient
import aws.smithy.kotlin.runtime.auth.awscredentials.CredentialsProvider
import aws.smithy.kotlin.runtime.http.engine.HttpClientEngine
import aws.smithy.kotlin.runtime.net.url.Url
import io.bluetape4k.support.useSafe

/**
 * Creates an AWS Kotlin SDK KMS client.
 *
 * AWS Key Management Service (KMS) is a managed service for creating and managing
 * encryption keys used across AWS services and applications.
 *
 * Example:
 * ```kotlin
 * val client = kmsClientOf(
 *     endpointUrl = Url.parse("http://localhost:4566"),
 *     region = "us-east-1",
 *     credentialsProvider = myCredentialsProvider
 * )
 * ```
 *
 * @param endpointUrl KMS service endpoint URL; when null, uses the default AWS endpoint
 * @param region AWS Region; when null, resolves it from the environment
 * @param credentialsProvider AWS credentials provider; when null, uses the default credentials chain
 * @param httpClient optional externally managed HTTP engine. Omit it to let the SDK manage engine ownership.
 * @param builder additional configuration for [KmsClient.Config.Builder]
 * @return the configured [KmsClient]
 */
inline fun kmsClientOf(
    endpointUrl: Url? = null,
    region: String? = null,
    credentialsProvider: CredentialsProvider? = null,
    httpClient: HttpClientEngine? = null,
    crossinline builder: KmsClient.Config.Builder.() -> Unit = {},
): KmsClient = KmsClient {
    endpointUrl?.let { this.endpointUrl = it }
    region?.let { this.region = it }
    credentialsProvider?.let { this.credentialsProvider = it }
    httpClient?.let { this.httpClient = it }

    builder()
}

/**
 * Creates a [KmsClient], executes [block], and closes the client automatically.
 *
 * The SDK manages its internal HTTP engine, so closing the client also shuts down the engine.
 *
 * ```kotlin
 * withKmsClient(endpointUrl, region, credentialsProvider) { client ->
 *     client.encrypt { ... }
 * }
 * ```
 *
 * @param block suspending block; AWS SDK operations are suspend functions
 */
suspend fun <R> withKmsClient(
    endpointUrl: Url? = null,
    region: String? = null,
    credentialsProvider: CredentialsProvider? = null,
    block: suspend (KmsClient) -> R,
): R = kmsClientOf(endpointUrl, region, credentialsProvider).useSafe { client ->
    block(client)
}
