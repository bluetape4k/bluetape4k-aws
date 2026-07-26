package io.bluetape4k.aws.dynamodb.enhanced

import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClientExtension
import software.amazon.awssdk.enhanced.dynamodb.internal.client.ExtensionResolver
import software.amazon.awssdk.services.dynamodb.DynamoDbClient

/**
 * Creates a [DynamoDbEnhancedClient] by configuring [DynamoDbEnhancedClient.Builder].
 *
 * ```kotlin
 * val enhanced = dynamoDbEnhancedClient {
 *     dynamoDbClient(DynamoDbClient.create())
 * }
 *
 * check(enhanced != null)
 * ```
 */
inline fun dynamoDbEnhancedClient(
    builder: DynamoDbEnhancedClient.Builder.() -> Unit,
): DynamoDbEnhancedClient =
    DynamoDbEnhancedClient.builder().apply(builder).build()

/**
 * Creates a [DynamoDbEnhancedClient] from an existing [DynamoDbClient].
 *
 * The default builder applies the AWS default extension set ([ExtensionResolver.defaultExtensions]).
 *
 * ```kotlin
 * val baseClient = DynamoDbClient.create()
 * val enhanced = dynamoDbEnhancedClientOf(baseClient)
 *
 * check(enhanced != null)
 * ```
 */
inline fun dynamoDbEnhancedClientOf(
    client: DynamoDbClient,
    builder: DynamoDbEnhancedClient.Builder.() -> Unit =
        { extensions(ExtensionResolver.defaultExtensions()) },
): DynamoDbEnhancedClient = dynamoDbEnhancedClient {
    dynamoDbClient(client)
    builder()
}

/**
 * Creates a [DynamoDbEnhancedClient] with an explicit [DynamoDbEnhancedClientExtension] list.
 *
 * ```kotlin
 * val baseClient = DynamoDbClient.create()
 * val enhanced = dynamoDbEnhancedClientOf(baseClient, *ExtensionResolver.defaultExtensions().toTypedArray())
 *
 * check(enhanced != null)
 * ```
 */
fun dynamoDbEnhancedClientOf(
    client: DynamoDbClient,
    vararg extensions: DynamoDbEnhancedClientExtension = ExtensionResolver.defaultExtensions().toTypedArray(),
): DynamoDbEnhancedClient = dynamoDbEnhancedClient {
    dynamoDbClient(client)
    extensions(*extensions)
}
