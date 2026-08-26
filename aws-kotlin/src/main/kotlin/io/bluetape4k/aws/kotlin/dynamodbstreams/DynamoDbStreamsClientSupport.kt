package io.bluetape4k.aws.kotlin.dynamodbstreams

import aws.sdk.kotlin.services.dynamodbstreams.DynamoDbStreamsClient
import aws.smithy.kotlin.runtime.auth.awscredentials.CredentialsProvider
import aws.smithy.kotlin.runtime.http.engine.HttpClientEngine
import aws.smithy.kotlin.runtime.net.url.Url
import io.bluetape4k.support.useSafe

/** AWS Kotlin SDK DynamoDB Streams client를 생성합니다. */
inline fun dynamoDbStreamsClientOf(
    endpointUrl: Url? = null,
    region: String? = null,
    credentialsProvider: CredentialsProvider? = null,
    httpClient: HttpClientEngine? = null,
    crossinline builder: DynamoDbStreamsClient.Config.Builder.() -> Unit = {},
): DynamoDbStreamsClient = DynamoDbStreamsClient {
    endpointUrl?.let { this.endpointUrl = it }
    region?.let { this.region = it }
    credentialsProvider?.let { this.credentialsProvider = it }
    httpClient?.let { this.httpClient = it }
    builder()
}

/** client를 생성해 [block]을 실행한 뒤 client와 내부 HTTP 자원을 닫습니다. */
suspend fun <R> withDynamoDbStreamsClient(
    endpointUrl: Url? = null,
    region: String? = null,
    credentialsProvider: CredentialsProvider? = null,
    httpClient: HttpClientEngine? = null,
    builder: DynamoDbStreamsClient.Config.Builder.() -> Unit = {},
    block: suspend (DynamoDbStreamsClient) -> R,
): R = dynamoDbStreamsClientOf(endpointUrl, region, credentialsProvider, httpClient, builder).useSafe { client ->
    block(client)
}
