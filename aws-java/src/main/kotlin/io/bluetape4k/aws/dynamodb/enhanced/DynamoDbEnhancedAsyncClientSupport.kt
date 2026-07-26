package io.bluetape4k.aws.dynamodb.enhanced

import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedAsyncClient
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClientExtension
import software.amazon.awssdk.enhanced.dynamodb.internal.client.ExtensionResolver
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient

/**
 * Creates a [DynamoDbEnhancedAsyncClient].
 *
 * ```kotlin
 * val client = dynamoDbEnhancedAsyncClient {
 *    dynamoDbClient(DynamoDbAsyncClient.create())
 * }
 * ```
 *
 * @param builder Lambda that initializes [DynamoDbEnhancedAsyncClient.Builder].
 * @return [DynamoDbEnhancedAsyncClient] instance
 */
inline fun dynamoDbEnhancedAsyncClient(
    builder: DynamoDbEnhancedAsyncClient.Builder.() -> Unit,
): DynamoDbEnhancedAsyncClient {
    return DynamoDbEnhancedAsyncClient.builder().apply(builder).build()
}

/**
 * Creates a [DynamoDbEnhancedAsyncClient].
 *
 * ```kotlin
 * val client = dynamoDbEnhancedAsyncClientOf(DynamoDbAsyncClient.create()) {
 *   extensions(ExtensionResolver.defaultExtensions())
 * }
 * ```
 *
 * @param client [DynamoDbAsyncClient] instance
 * @param builder Lambda that initializes [DynamoDbEnhancedAsyncClient.Builder].
 * @return [DynamoDbEnhancedAsyncClient] instance
 */
inline fun dynamoDbEnhancedAsyncClientOf(
    client: DynamoDbAsyncClient,
    builder: DynamoDbEnhancedAsyncClient.Builder.() -> Unit =
        { extensions(ExtensionResolver.defaultExtensions()) },
): DynamoDbEnhancedAsyncClient =
    dynamoDbEnhancedAsyncClient {
        dynamoDbClient(client)
        builder()
    }

/**
 * Creates a [DynamoDbEnhancedAsyncClient].
 *
 * ```kotlin
 * val client = dynamoDbEnhancedAsyncClientOf(
 *      DynamoDbAsyncClient.create(),
 *      *ExtensionResolver.defaultExtensions().toTypedArray()
 * )
 * ```
 *
 * @param client [DynamoDbAsyncClient] instance
 * @param extensions [DynamoDbEnhancedClientExtension] extensions
 * @return [DynamoDbEnhancedAsyncClient] instance
 */
fun dynamoDbEnhancedAsyncClientOf(
    client: DynamoDbAsyncClient,
    vararg extensions: DynamoDbEnhancedClientExtension = ExtensionResolver.defaultExtensions().toTypedArray(),
): DynamoDbEnhancedAsyncClient = dynamoDbEnhancedAsyncClient {
    dynamoDbClient(client)
    extensions(*extensions)
}
