package io.bluetape4k.aws.dynamodb.enhanced

import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedAsyncClient
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClientExtension
import software.amazon.awssdk.enhanced.dynamodb.internal.client.ExtensionResolver
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient

/**
 * [DynamoDbEnhancedAsyncClient] 를 생성합니다.
 *
 * ```kotlin
 * val client = dynamoDbEnhancedAsyncClient {
 *    dynamoDbClient(DynamoDbAsyncClient.create())
 * }
 * ```
 *
 * @param builder [DynamoDbEnhancedAsyncClient.Builder] 를 초기화하는 람다 함수
 * @return [DynamoDbEnhancedAsyncClient] 인스턴스
 */
inline fun dynamoDbEnhancedAsyncClient(
    builder: DynamoDbEnhancedAsyncClient.Builder.() -> Unit,
): DynamoDbEnhancedAsyncClient {
    return DynamoDbEnhancedAsyncClient.builder().apply(builder).build()
}

/**
 * [DynamoDbEnhancedAsyncClient] 를 생성합니다.
 *
 * ```kotlin
 * val client = dynamoDbEnhancedAsyncClientOf(DynamoDbAsyncClient.create()) {
 *   extensions(ExtensionResolver.defaultExtensions())
 * }
 * ```
 *
 * @param client [DynamoDbAsyncClient] 인스턴스
 * @param builder [DynamoDbEnhancedAsyncClient.Builder] 를 초기화하는 람다 함수
 * @return [DynamoDbEnhancedAsyncClient] 인스턴스
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
 * [DynamoDbEnhancedAsyncClient] 를 생성합니다.
 *
 * ```kotlin
 * val client = dynamoDbEnhancedAsyncClientOf(
 *      DynamoDbAsyncClient.create(),
 *      *ExtensionResolver.defaultExtensions().toTypedArray()
 * )
 * ```
 *
 * @param client [DynamoDbAsyncClient] 인스턴스
 * @param extensions [DynamoDbEnhancedClientExtension] 확장 목록
 * @return [DynamoDbEnhancedAsyncClient] 인스턴스
 */
fun dynamoDbEnhancedAsyncClientOf(
    client: DynamoDbAsyncClient,
    vararg extensions: DynamoDbEnhancedClientExtension = ExtensionResolver.defaultExtensions().toTypedArray(),
): DynamoDbEnhancedAsyncClient = dynamoDbEnhancedAsyncClient {
    dynamoDbClient(client)
    extensions(*extensions)
}
